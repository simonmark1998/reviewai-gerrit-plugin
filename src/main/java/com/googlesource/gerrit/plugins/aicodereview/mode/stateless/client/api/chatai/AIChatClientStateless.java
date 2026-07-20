// Copyright (C) 2024 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.aicodereview.mode.stateless.client.api.chatai;

import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.length;
import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.summarize;
import static com.googlesource.gerrit.plugins.aicodereview.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.aicodereview.utils.GsonUtils.getNoEscapedGson;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.aicodereview.config.Configuration;
import com.googlesource.gerrit.plugins.aicodereview.interfaces.mode.common.client.api.openapi.ChatAIClient;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.openai.AIChatClient;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.openai.AIChatParameters;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.openai.AIChatTools;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.http.HttpClientWithRetry;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatCompletionRequest;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatRequestMessage;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatResponseContent;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatTool;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.agent.AgentConversationRequest;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.agent.AgentConversationResponse;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.agent.AgentMessageItem;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.agent.AgentReference;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.agent.AgentResponseRequest;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.aicodereview.mode.stateless.client.api.UriResourceLocatorStateless;
import com.googlesource.gerrit.plugins.aicodereview.mode.stateless.client.prompt.AIChatPromptStateless;
import com.googlesource.gerrit.plugins.aicodereview.settings.Settings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.NameValuePair;
import org.apache.http.entity.ContentType;

@Slf4j
@Singleton
public class AIChatClientStateless extends AIChatClient implements ChatAIClient {
  private static final int REVIEW_ATTEMPT_LIMIT = 3;

  private final HttpClientWithRetry httpClientWithRetry = new HttpClientWithRetry();

  @VisibleForTesting
  @Inject
  public AIChatClientStateless(Configuration config) {
    super(config);
  }

  public AIChatResponseContent ask(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    isCommentEvent = change.getIsCommentEvent();
    String changeId = change.getFullChangeId();
    log.info(
        "Processing STATELESS AIChat Request with changeId: {}, patchSetChars: {}, patchSetLines: {}",
        changeId,
        length(patchSet),
        patchSet.split("\n").length);
    log.debug(
        "STATELESS AIChat request context: change={}, aiType={}, aiMode={}, isCommentEvent={}, forcedReview={}, replyFilterEnabled={}, debugReviewMode={}, votingEnabled={}",
        changeId,
        config.getAIType(),
        config.getAIMode(),
        isCommentEvent,
        changeSetData.getForcedReview(),
        changeSetData.getReplyFilterEnabled(),
        changeSetData.getDebugReviewMode(),
        config.isVotingEnabled());
    log.debug("STATELESS AIChat patchSet summary: {}", summarize(patchSet));
    if (config.getAIType() == Settings.AIType.AZUREAGENT) {
      log.debug("Routing STATELESS AIChat request through Azure Agent flow");
      return askAgent(changeSetData, change, patchSet);
    }
    for (int attemptInd = 0; attemptInd < REVIEW_ATTEMPT_LIMIT; attemptInd++) {
      log.debug("Starting AIChat completion attempt {}/{}", attemptInd + 1, REVIEW_ATTEMPT_LIMIT);
      HttpRequest request = createRequest(config, changeSetData, patchSet);
      log.debug("AIChat request: {}", request.toString());

      HttpResponse<String> response = httpClientWithRetry.execute(request);

      String body = response.body();
      log.debug(
          "Chat response received: status={}, bodyChars={}, body={}",
          response.statusCode(),
          length(body),
          summarize(body));
      if (body == null) {
        throw new IOException("AIChat response body is null");
      }

      AIChatResponseContent contentExtracted = extractContent(config, body);
      if (validateResponse(contentExtracted, changeId, attemptInd)) {
        log.debug(
            "AIChat completion attempt {} accepted: replies={}, messageContentChars={}",
            attemptInd + 1,
            contentExtracted.getReplies() == null ? null : contentExtracted.getReplies().size(),
            length(contentExtracted.getMessageContent()));
        return contentExtracted;
      }
      log.debug("AIChat completion attempt {} rejected by validation", attemptInd + 1);
    }
    throw new RuntimeException("Failed to receive valid AIChat response");
  }

  protected HttpRequest createRequest(
      Configuration config, ChangeSetData changeSetData, String patchSet) {
    URI uri =
        URI.create(config.getAIDomain() + UriResourceLocatorStateless.getChatResourceUri(config));
    log.debug("AIChat request URI: {}", uri);
    requestBody = createRequestBody(config, changeSetData, patchSet);
    log.debug("AIChat request body: {}", summarize(requestBody));

    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
            .uri(uri)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody));

    // depending on the aiType, add appropriate authorization header ( if required ).
    NameValuePair authHeader = config.getAuthorizationHeaderInfo();
    if (authHeader != null) {
      log.debug("AIChat request auth header configured: {}", authHeader.getName());
      builder.header(authHeader.getName(), authHeader.getValue());
    } else {
      log.debug("AIChat request has no auth header configured");
    }
    return builder.build();
  }

  private String createRequestBody(
      Configuration config, ChangeSetData changeSetData, String patchSet) {
    AIChatPromptStateless AIChatPromptStateless = new AIChatPromptStateless(config, isCommentEvent);
    AIChatRequestMessage systemMessage =
        AIChatRequestMessage.builder()
            .role("system")
            .content(AIChatPromptStateless.getAISystemPrompt())
            .build();
    AIChatRequestMessage userMessage =
        AIChatRequestMessage.builder()
            .role("user")
            .content(AIChatPromptStateless.getGptUserPrompt(changeSetData, patchSet))
            .build();

    AIChatParameters AIChatParameters = new AIChatParameters(config, isCommentEvent);
    log.debug(
        "Created stateless AIChat prompts: systemPromptChars={}, userPromptChars={}, temperature={}, stream={}, seed={}",
        length(systemMessage.getContent()),
        length(userMessage.getContent()),
        AIChatParameters.getGptTemperature(),
        AIChatParameters.getStreamOutput(),
        AIChatParameters.getRandomSeed());
    log.debug("AIChat system prompt: {}", summarize(systemMessage.getContent()));
    log.debug("AIChat user prompt: {}", summarize(userMessage.getContent()));
    AIChatTool[] tools = new AIChatTool[] {AIChatTools.retrieveFormatRepliesTool()};
    AIChatCompletionRequest chatGptCompletionRequest =
        AIChatCompletionRequest.builder()
            .model(config.getAIModel())
            .messages(List.of(systemMessage, userMessage))
            .temperature(AIChatParameters.getGptTemperature())
            .stream(AIChatParameters.getStreamOutput())
            // Seed value is Utilized to prevent ChatGPT from mixing up separate API calls that
            // occur in close
            // temporal proximity.
            .seed(AIChatParameters.getRandomSeed())
            .tools(tools)
            .toolChoice(AIChatTools.retrieveFormatRepliesToolChoice())
            .build();

    return getNoEscapedGson().toJson(chatGptCompletionRequest);
  }

  /**
   * Runs a review through an Azure AI Foundry Agent using the Responses API. Unlike the Chat
   * Completions flow this does not use the format_replies tool call; the agent response text is
   * parsed into the same JSON review format when possible.
   */
  private AIChatResponseContent askAgent(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    AIChatPromptStateless prompt = new AIChatPromptStateless(config, isCommentEvent);
    String content =
        prompt.getAISystemPrompt() + "\n\n" + prompt.getGptUserPrompt(changeSetData, patchSet);

    // e.g. https://<resource>.services.ai.azure.com + /api/projects/<project>/openai/v1
    String base = config.getAIDomain() + config.getChatEndpoint();
    log.debug(
        "Azure Agent review request prepared: change={}, base={}, agentName={}, agentVersion={}, promptChars={}, prompt={}",
        change.getFullChangeId(),
        base,
        config.getAzureAgentName(),
        config.getAzureAgentVersion(),
        length(content),
        summarize(content));

    String conversationId = createAgentConversation(base, content);
    log.debug(
        "Azure Agent conversation created: change={}, conversationId={}",
        change.getFullChangeId(),
        conversationId);
    String outputText = createAgentResponse(base, conversationId);
    log.debug(
        "Azure Agent output text extracted: change={}, outputTextChars={}, outputText={}",
        change.getFullChangeId(),
        length(outputText),
        summarize(outputText));
    if (outputText == null || outputText.isEmpty()) {
      throw new IOException("Azure agent returned an empty response");
    }
    AIChatResponseContent responseContent = convertResponseContentFromText(outputText);
    log.debug(
        "Azure Agent output converted: change={}, replies={}, messageContentChars={}, changeId={}",
        change.getFullChangeId(),
        responseContent.getReplies() == null ? null : responseContent.getReplies().size(),
        length(responseContent.getMessageContent()),
        responseContent.getChangeId());
    return responseContent;
  }

  private String createAgentConversation(String base, String content) throws Exception {
    AgentMessageItem item =
        AgentMessageItem.builder().type("message").role("user").content(content).build();
    AgentConversationRequest requestBody =
        AgentConversationRequest.builder().items(List.of(item)).build();
    HttpRequest request =
        buildAgentPost(base + "/conversations", getNoEscapedGson().toJson(requestBody));
    HttpResponse<String> response = httpClientWithRetry.execute(request);
    String body = response.body();
    log.debug(
        "Agent conversation response received: status={}, bodyChars={}, body={}",
        response.statusCode(),
        length(body),
        summarize(body));
    if (body == null) {
      throw new IOException("Azure agent conversation response body is null");
    }
    AgentConversationResponse parsed = getGson().fromJson(body, AgentConversationResponse.class);
    log.debug("Agent conversation response parsed: id={}", parsed == null ? null : parsed.getId());
    if (parsed == null || parsed.getId() == null) {
      throw new IOException("Azure agent conversation id missing in response: " + body);
    }
    return parsed.getId();
  }

  private String createAgentResponse(String base, String conversationId) throws Exception {
    AgentReference agentReference =
        AgentReference.builder()
            .name(config.getAzureAgentName())
            .version(config.getAzureAgentVersion())
            .type("agent_reference")
            .build();
    AgentResponseRequest requestBody =
        AgentResponseRequest.builder()
            .conversation(conversationId)
            .input(Collections.emptyList())
            .agentReference(agentReference)
            .build();
    HttpRequest request =
        buildAgentPost(base + "/responses", getNoEscapedGson().toJson(requestBody));
    HttpResponse<String> response = httpClientWithRetry.execute(request);
    String body = response.body();
    log.debug(
        "Agent response received: status={}, bodyChars={}, body={}",
        response.statusCode(),
        length(body),
        summarize(body));
    if (body == null) {
      throw new IOException("Azure agent response body is null");
    }
    return extractAgentOutputText(body);
  }

  private String extractAgentOutputText(String body) {
    log.debug("Extracting Azure Agent output text from response body: chars={}", length(body));
    JsonElement response = JsonParser.parseString(body);
    Optional<String> outputText = getObjectString(response, "output_text");
    if (outputText.isPresent()) {
      log.debug(
          "Azure Agent response used top-level output_text: chars={}", length(outputText.get()));
      return outputText.get();
    }

    Optional<String> embeddedReviewJson = findEmbeddedReviewJson(response);
    if (embeddedReviewJson.isPresent()) {
      log.debug(
          "Azure Agent response contained embedded review JSON: chars={}",
          length(embeddedReviewJson.get()));
      return embeddedReviewJson.get();
    }

    List<String> textParts = new ArrayList<>();
    if (response.isJsonObject() && response.getAsJsonObject().has("output")) {
      log.debug("Azure Agent response has output array/object; collecting output text");
      collectOutputText(response.getAsJsonObject().get("output"), textParts);
    }
    if (textParts.isEmpty()) {
      log.debug("Azure Agent output collection found no text; recursively collecting typed text");
      collectTypedText(response, textParts);
    }
    log.debug(
        "Azure Agent text extraction complete: textParts={}, totalChars={}",
        textParts.size(),
        textParts.stream().mapToInt(String::length).sum());
    return textParts.isEmpty() ? null : String.join("\n", textParts);
  }

  private void collectOutputText(JsonElement element, List<String> textParts) {
    if (element == null || element.isJsonNull()) {
      return;
    }
    if (element.isJsonArray()) {
      for (JsonElement item : element.getAsJsonArray()) {
        collectOutputText(item, textParts);
      }
      return;
    }
    if (!element.isJsonObject()) {
      return;
    }
    JsonObject object = element.getAsJsonObject();
    if (object.has("content")) {
      collectTypedText(object.get("content"), textParts);
    }
    getTextValue(object.get("text"))
        .ifPresent(
            text -> {
              log.debug("Collected Azure Agent output text field: chars={}", length(text));
              textParts.add(text);
            });
    getTextValue(object.get("output_text"))
        .ifPresent(
            text -> {
              log.debug("Collected Azure Agent output_text field: chars={}", length(text));
              textParts.add(text);
            });
  }

  private void collectTypedText(JsonElement element, List<String> textParts) {
    if (element == null || element.isJsonNull()) {
      return;
    }
    if (element.isJsonArray()) {
      for (JsonElement item : element.getAsJsonArray()) {
        collectTypedText(item, textParts);
      }
      return;
    }
    if (!element.isJsonObject()) {
      return;
    }
    JsonObject object = element.getAsJsonObject();
    String type = getObjectString(object, "type").orElse("");
    if ("output_text".equals(type) || "text".equals(type) || "message".equals(type)) {
      log.debug(
          "Inspecting Azure Agent typed text object: type={}, keys={}", type, object.keySet());
      getTextValue(object.get("text"))
          .ifPresent(
              text -> {
                log.debug("Collected Azure Agent typed `text`: chars={}", length(text));
                textParts.add(text);
              });
      getTextValue(object.get("output_text"))
          .ifPresent(
              text -> {
                log.debug("Collected Azure Agent typed `output_text`: chars={}", length(text));
                textParts.add(text);
              });
      getTextValue(object.get("content"))
          .ifPresent(
              text -> {
                log.debug("Collected Azure Agent typed `content`: chars={}", length(text));
                textParts.add(text);
              });
    }
    if (object.has("content")) {
      collectTypedText(object.get("content"), textParts);
    }
  }

  private Optional<String> findEmbeddedReviewJson(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return Optional.empty();
    }
    if (element.isJsonObject()) {
      JsonObject object = element.getAsJsonObject();
      if (object.has("replies") || object.has("comments") || object.has("inlineComments")) {
        log.debug("Found Azure Agent embedded review JSON object with keys: {}", object.keySet());
        return Optional.of(getNoEscapedGson().toJson(object));
      }
      for (String key : object.keySet()) {
        Optional<String> result = findEmbeddedReviewJson(object.get(key));
        if (result.isPresent()) {
          return result;
        }
      }
    } else if (element.isJsonArray()) {
      JsonArray array = element.getAsJsonArray();
      for (JsonElement item : array) {
        Optional<String> result = findEmbeddedReviewJson(item);
        if (result.isPresent()) {
          return result;
        }
      }
    } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      String text = element.getAsString();
      if (convertEmbeddedJsonContent(text).isPresent()) {
        log.debug("Found Azure Agent embedded review JSON inside string field");
        return Optional.of(text);
      }
    }
    return Optional.empty();
  }

  private Optional<String> getTextValue(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return Optional.empty();
    }
    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      String value = element.getAsString();
      log.debug("Read Azure Agent string text value: chars={}", length(value));
      return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
    if (element.isJsonObject()) {
      JsonObject object = element.getAsJsonObject();
      Optional<String> value = getObjectString(object, "value");
      if (value.isPresent()) {
        log.debug("Read Azure Agent nested text.value: chars={}", length(value.get()));
        return value;
      }
      log.debug(
          "Azure Agent text object has no value field; trying text key. keys={}", object.keySet());
      return getObjectString(object, "text");
    }
    return Optional.empty();
  }

  private Optional<String> getObjectString(JsonElement element, String key) {
    if (element == null || !element.isJsonObject()) {
      return Optional.empty();
    }
    return getObjectString(element.getAsJsonObject(), key);
  }

  private Optional<String> getObjectString(JsonObject object, String key) {
    if (!object.has(key)) {
      return Optional.empty();
    }
    JsonElement value = object.get(key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      return Optional.empty();
    }
    String text = value.getAsString();
    return text.isEmpty() ? Optional.empty() : Optional.of(text);
  }

  private HttpRequest buildAgentPost(String uri, String body) {
    log.debug("Agent request URI: {}, bodyChars={}, body={}", uri, length(body), summarize(body));
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
            .uri(URI.create(uri))
            .POST(HttpRequest.BodyPublishers.ofString(body));
    NameValuePair authHeader = config.getAuthorizationHeaderInfo();
    if (authHeader != null) {
      log.debug("Agent request auth header configured: {}", authHeader.getName());
      builder.header(authHeader.getName(), authHeader.getValue());
    } else {
      log.debug("Agent request has no auth header configured");
    }
    return builder.build();
  }
}
