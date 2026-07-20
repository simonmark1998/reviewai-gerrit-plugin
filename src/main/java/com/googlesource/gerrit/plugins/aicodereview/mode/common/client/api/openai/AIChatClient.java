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

package com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.openai;

import static com.googlesource.gerrit.plugins.aicodereview.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.aicodereview.utils.JsonTextUtils.isJsonString;
import static com.googlesource.gerrit.plugins.aicodereview.utils.JsonTextUtils.unwrapJsonCode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.googlesource.gerrit.plugins.aicodereview.config.Configuration;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.ClientBase;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatReplyItem;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatResponseContent;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatResponseMessage;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatResponseStreamed;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatResponseUnstreamed;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatToolCall;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AIChatClient extends ClientBase {
  private static final List<String> RESPONSE_ARRAY_KEYS =
      Arrays.asList("replies", "comments", "reviews", "findings", "issues", "suggestions");
  private static final List<String> RESPONSE_TEXT_KEYS =
      Arrays.asList("response", "content", "message", "text", "output_text");
  private static final List<String> REPLY_TEXT_KEYS =
      Arrays.asList("reply", "comment", "message", "text", "body", "content");
  private static final List<String> FILENAME_KEYS =
      Arrays.asList("filename", "file", "fileName", "path");
  private static final List<String> LINE_NUMBER_KEYS =
      Arrays.asList("lineNumber", "line", "line_number");
  private static final List<String> CODE_SNIPPET_KEYS =
      Arrays.asList("codeSnippet", "snippet", "code", "code_snippet");
  private static final List<String> CODE_TOKEN_KEYS =
      Arrays.asList("codeToken", "token", "identifier", "symbol", "code_token");

  protected boolean isCommentEvent = false;
  @Getter protected String requestBody;

  public AIChatClient(Configuration config) {
    super(config);
  }

  protected AIChatResponseContent extractContent(Configuration config, String body)
      throws Exception {
    if (config.getAIStreamOutput() && !isCommentEvent) {
      StringBuilder finalContent = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
        String line;
        while ((line = reader.readLine()) != null) {
          extractContentFromLine(line).ifPresent(finalContent::append);
        }
      }
      return convertResponseContentFromJson(finalContent.toString());
    } else {
      AIChatResponseUnstreamed AIChatResponseUnstreamed =
          getGson().fromJson(body, AIChatResponseUnstreamed.class);
      AIChatResponseMessage message = AIChatResponseUnstreamed.getChoices().get(0).getMessage();
      if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
        return getResponseContent(message.getToolCalls());
      }
      if (message.getContent() != null && !message.getContent().isEmpty()) {
        return convertResponseContentFromText(message.getContent());
      }
      throw new IOException("AIChat response contains neither tool_calls nor message content");
    }
  }

  protected boolean validateResponse(
      AIChatResponseContent AIChatResponseContent, String changeId, int attemptInd) {
    String returnedChangeId = AIChatResponseContent.getChangeId();
    // A response is considered valid if either no changeId is returned or the changeId returned
    // matches the one
    // provided in the request
    boolean isValidated = returnedChangeId == null || changeId.equals(returnedChangeId);
    if (!isValidated) {
      log.error(
          "ChangedId mismatch error (attempt #{}).\nExpected value: {}\nReturned value: {}",
          attemptInd,
          changeId,
          returnedChangeId);
    }
    return isValidated;
  }

  protected AIChatResponseContent getResponseContent(List<AIChatToolCall> toolCalls) {
    if (toolCalls.size() > 1) {
      return mergeToolCalls(toolCalls);
    } else {
      return getArgumentAsResponse(toolCalls, 0);
    }
  }

  protected Optional<String> extractContentFromLine(String line) {
    String dataPrefix = "data: {\"id\"";

    if (!line.startsWith(dataPrefix)) {
      return Optional.empty();
    }
    AIChatResponseStreamed AIChatResponseStreamed =
        getGson().fromJson(line.substring("data: ".length()), AIChatResponseStreamed.class);
    AIChatResponseMessage delta = AIChatResponseStreamed.getChoices().get(0).getDelta();
    if (delta == null || delta.getToolCalls() == null) {
      return Optional.empty();
    }
    String content = getArgumentAsString(delta.getToolCalls(), 0);
    return Optional.ofNullable(content);
  }

  protected AIChatResponseContent convertResponseContentFromText(String content) {
    if (content == null) {
      return new AIChatResponseContent("");
    }
    String trimmed = content.trim();
    if (isJsonString(trimmed)) {
      try {
        return convertResponseContentFromJson(unwrapJsonCode(trimmed));
      } catch (JsonSyntaxException e) {
        log.warn("AIChat message content looked like JSON but could not be parsed. Skipping.", e);
        return new AIChatResponseContent();
      }
    }
    return new AIChatResponseContent(content);
  }

  private AIChatResponseContent convertResponseContentFromJson(String content) {
    JsonElement parsed = JsonParser.parseString(content);
    if (parsed.isJsonArray()) {
      return convertReplyArray(parsed.getAsJsonArray());
    }
    if (!parsed.isJsonObject()) {
      log.warn("AIChat JSON response was not an object. Skipping.");
      return new AIChatResponseContent();
    }
    JsonObject object = parsed.getAsJsonObject();
    for (String key : RESPONSE_ARRAY_KEYS) {
      Optional<AIChatResponseContent> responseContent = convertReplyArrayProperty(object, key);
      if (responseContent.isPresent()) {
        return responseContent.get();
      }
    }
    Optional<AIChatReplyItem> replyItem = convertReplyObject(object);
    if (replyItem.isPresent()) {
      return convertSingleReply(replyItem.get(), object);
    }
    for (String key : RESPONSE_TEXT_KEYS) {
      Optional<String> text = getStringProperty(object, key);
      if (text.isPresent()) {
        return convertResponseContentFromText(text.get());
      }
    }
    log.warn("AIChat JSON response contained neither `replies` nor `reply`. Skipping.");
    return new AIChatResponseContent();
  }

  private Optional<AIChatResponseContent> convertReplyArrayProperty(JsonObject object, String key) {
    if (!object.has(key)) {
      return Optional.empty();
    }
    JsonElement element = object.get(key);
    if (element.isJsonArray()) {
      return Optional.of(convertReplyArray(element.getAsJsonArray(), object));
    }
    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      return Optional.of(convertResponseContentFromText(element.getAsString()));
    }
    return Optional.of(new AIChatResponseContent());
  }

  private AIChatResponseContent convertReplyArray(JsonArray array) {
    return convertReplyArray(array, null);
  }

  private AIChatResponseContent convertReplyArray(JsonArray array, JsonObject sourceObject) {
    AIChatResponseContent responseContent = new AIChatResponseContent();
    List<AIChatReplyItem> replies = new ArrayList<>();
    for (JsonElement element : array) {
      Optional<AIChatReplyItem> replyItem = convertReplyElement(element);
      replyItem.ifPresent(replies::add);
    }
    responseContent.setReplies(replies);
    if (sourceObject != null) {
      getStringProperty(sourceObject, "changeId").ifPresent(responseContent::setChangeId);
    }
    return responseContent;
  }

  private AIChatResponseContent convertSingleReply(
      AIChatReplyItem replyItem, JsonObject sourceObject) {
    AIChatResponseContent responseContent = new AIChatResponseContent();
    List<AIChatReplyItem> replies = new ArrayList<>();
    replies.add(replyItem);
    responseContent.setReplies(replies);
    getStringProperty(sourceObject, "changeId").ifPresent(responseContent::setChangeId);
    return responseContent;
  }

  private Optional<AIChatReplyItem> convertReplyElement(JsonElement element) {
    if (element.isJsonObject()) {
      return convertReplyObject(element.getAsJsonObject());
    }
    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      AIChatReplyItem replyItem = new AIChatReplyItem();
      replyItem.setReply(element.getAsString());
      return Optional.of(replyItem);
    }
    return Optional.empty();
  }

  private Optional<AIChatReplyItem> convertReplyObject(JsonObject object) {
    AIChatReplyItem replyItem = getGson().fromJson(object, AIChatReplyItem.class);
    if (replyItem.getReply() == null) {
      getStringProperty(object, REPLY_TEXT_KEYS).ifPresent(replyItem::setReply);
    }
    if (replyItem.getFilename() == null) {
      getStringProperty(object, FILENAME_KEYS).ifPresent(replyItem::setFilename);
    }
    if (replyItem.getLineNumber() == null) {
      getIntegerProperty(object, LINE_NUMBER_KEYS).ifPresent(replyItem::setLineNumber);
    }
    if (replyItem.getCodeSnippet() == null) {
      getStringProperty(object, CODE_SNIPPET_KEYS).ifPresent(replyItem::setCodeSnippet);
    }
    if (replyItem.getCodeToken() == null) {
      getStringProperty(object, CODE_TOKEN_KEYS).ifPresent(replyItem::setCodeToken);
    }
    return replyItem.getReply() == null ? Optional.empty() : Optional.of(replyItem);
  }

  private Optional<String> getStringProperty(JsonObject object, List<String> keys) {
    for (String key : keys) {
      Optional<String> value = getStringProperty(object, key);
      if (value.isPresent()) {
        return value;
      }
    }
    return Optional.empty();
  }

  private Optional<String> getStringProperty(JsonObject object, String key) {
    if (!object.has(key)) {
      return Optional.empty();
    }
    JsonElement element = object.get(key);
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
      return Optional.empty();
    }
    String value = element.getAsString();
    return value.isEmpty() ? Optional.empty() : Optional.of(value);
  }

  private Optional<Integer> getIntegerProperty(JsonObject object, List<String> keys) {
    for (String key : keys) {
      if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
        continue;
      }
      try {
        return Optional.of(object.get(key).getAsInt());
      } catch (NumberFormatException | UnsupportedOperationException e) {
        log.warn("AIChat line number field `{}` was not an integer", key, e);
      }
    }
    return Optional.empty();
  }

  private String getArgumentAsString(List<AIChatToolCall> toolCalls, int ind) {
    return toolCalls.get(ind).getFunction().getArguments();
  }

  private AIChatResponseContent getArgumentAsResponse(List<AIChatToolCall> toolCalls, int ind) {
    return convertResponseContentFromJson(getArgumentAsString(toolCalls, ind));
  }

  private AIChatResponseContent mergeToolCalls(List<AIChatToolCall> toolCalls) {
    AIChatResponseContent responseContent = getArgumentAsResponse(toolCalls, 0);
    for (int ind = 1; ind < toolCalls.size(); ind++) {
      responseContent.getReplies().addAll(getArgumentAsResponse(toolCalls, ind).getReplies());
    }
    return responseContent;
  }
}
