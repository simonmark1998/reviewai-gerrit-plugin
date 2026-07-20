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

import static com.googlesource.gerrit.plugins.aicodereview.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.aicodereview.utils.GsonUtils.getNoEscapedGson;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HttpHeaders;
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
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.agent.AgentOutputContent;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.agent.AgentOutputItem;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.agent.AgentReference;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.agent.AgentResponse;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.agent.AgentResponseRequest;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.aicodereview.mode.stateless.client.api.UriResourceLocatorStateless;
import com.googlesource.gerrit.plugins.aicodereview.mode.stateless.client.prompt.AIChatPromptStateless;
import com.googlesource.gerrit.plugins.aicodereview.settings.Settings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
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
        "Processing STATELESS AIChat Request with changeId: {}, Patch Set: {}", changeId, patchSet);
    if (config.getAIType() == Settings.AIType.AZUREAGENT) {
      return askAgent(changeSetData, change, patchSet);
    }
    for (int attemptInd = 0; attemptInd < REVIEW_ATTEMPT_LIMIT; attemptInd++) {
      HttpRequest request = createRequest(config, changeSetData, patchSet);
      log.debug("AIChat request: {}", request.toString());

      HttpResponse<String> response = httpClientWithRetry.execute(request);

      String body = response.body();
      log.debug("Chat response body: {}", body);
      if (body == null) {
        throw new IOException("AIChat response body is null");
      }

      AIChatResponseContent contentExtracted = extractContent(config, body);
      if (validateResponse(contentExtracted, changeId, attemptInd)) {
        return contentExtracted;
      }
    }
    throw new RuntimeException("Failed to receive valid AIChat response");
  }

  protected HttpRequest createRequest(
      Configuration config, ChangeSetData changeSetData, String patchSet) {
    URI uri =
        URI.create(config.getAIDomain() + UriResourceLocatorStateless.getChatResourceUri(config));
    log.debug("AIChat request URI: {}", uri);
    requestBody = createRequestBody(config, changeSetData, patchSet);
    log.debug("AIChat request body: {}", requestBody);

    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
            .uri(uri)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody));

    // depending on the aiType, add appropriate authorization header ( if required ).
    NameValuePair authHeader = config.getAuthorizationHeaderInfo();
    if (authHeader != null) {
      builder.header(authHeader.getName(), authHeader.getValue());
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
   * Completions flow this does not use the format_replies tool: the agent returns free-form review
   * text which is posted as a single review message.
   */
  private AIChatResponseContent askAgent(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    AIChatPromptStateless prompt = new AIChatPromptStateless(config, isCommentEvent);
    String content =
        prompt.getAISystemPrompt() + "\n\n" + prompt.getGptUserPrompt(changeSetData, patchSet);

    // e.g. https://<resource>.services.ai.azure.com + /api/projects/<project>/openai/v1
    String base = config.getAIDomain() + config.getChatEndpoint();

    String conversationId = createAgentConversation(base, content);
    String outputText = createAgentResponse(base, conversationId);
    if (outputText == null || outputText.isEmpty()) {
      throw new IOException("Azure agent returned an empty response");
    }
    return convertResponseContentFromText(outputText);
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
    log.debug("Agent conversation response body: {}", body);
    if (body == null) {
      throw new IOException("Azure agent conversation response body is null");
    }
    AgentConversationResponse parsed = getGson().fromJson(body, AgentConversationResponse.class);
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
    log.debug("Agent response body: {}", body);
    if (body == null) {
      throw new IOException("Azure agent response body is null");
    }
    return extractAgentOutputText(body);
  }

  private String extractAgentOutputText(String body) {
    AgentResponse response = getGson().fromJson(body, AgentResponse.class);
    if (response == null) {
      return null;
    }
    if (response.getOutputText() != null && !response.getOutputText().isEmpty()) {
      return response.getOutputText();
    }
    if (response.getOutput() == null) {
      return null;
    }
    StringBuilder builder = new StringBuilder();
    for (AgentOutputItem item : response.getOutput()) {
      if (item.getContent() == null) {
        continue;
      }
      for (AgentOutputContent outputContent : item.getContent()) {
        if ("output_text".equals(outputContent.getType()) && outputContent.getText() != null) {
          builder.append(outputContent.getText());
        }
      }
    }
    return builder.toString();
  }

  private HttpRequest buildAgentPost(String uri, String body) {
    log.debug("Agent request URI: {}, body: {}", uri, body);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
            .uri(URI.create(uri))
            .POST(HttpRequest.BodyPublishers.ofString(body));
    NameValuePair authHeader = config.getAuthorizationHeaderInfo();
    if (authHeader != null) {
      builder.header(authHeader.getName(), authHeader.getValue());
    }
    return builder.build();
  }
}
