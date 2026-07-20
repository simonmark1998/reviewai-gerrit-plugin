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

import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.length;
import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.summarize;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AIChatClient extends ClientBase {
  private static final Pattern JSON_CODE_BLOCK =
      Pattern.compile("```(?:json)?\\s*(\\{.*?\\}|\\[.*?\\])\\s*```", Pattern.DOTALL);
  private static final Pattern JSON_REPLIES_BLOCK =
      Pattern.compile(
          "(\\{\\s*\"(?:replies|comments|inlineComments|inline_comments)\"\\s*:.*\\})",
          Pattern.DOTALL);

  private static final List<String> RESPONSE_ARRAY_KEYS =
      Arrays.asList(
          "replies",
          "comments",
          "inlineComments",
          "inline_comments",
          "reviewComments",
          "review_comments",
          "reviews",
          "findings",
          "issues",
          "suggestions");
  private static final List<String> RESPONSE_TEXT_KEYS =
      Arrays.asList("response", "content", "message", "text", "output_text", "value");
  private static final List<String> REPLY_TEXT_KEYS =
      Arrays.asList(
          "reply",
          "comment",
          "message",
          "text",
          "body",
          "content",
          "feedback",
          "description",
          "suggestion",
          "review",
          "finding",
          "issue",
          "problem",
          "reason",
          "rationale",
          "details",
          "summary",
          "title",
          "commentText",
          "comment_text",
          "reviewText",
          "review_text",
          "reviewComment",
          "review_comment");
  private static final List<String> FILENAME_KEYS =
      Arrays.asList("filename", "file", "fileName", "file_name", "filePath", "file_path", "path");
  private static final List<String> LINE_NUMBER_KEYS =
      Arrays.asList(
          "lineNumber",
          "line",
          "line_number",
          "lineNo",
          "line_no",
          "startLine",
          "start_line",
          "endLine",
          "end_line");
  private static final List<String> CODE_SNIPPET_KEYS =
      Arrays.asList("codeSnippet", "snippet", "code", "code_snippet", "excerpt");
  private static final List<String> CODE_TOKEN_KEYS =
      Arrays.asList("codeToken", "token", "identifier", "symbol", "code_token");
  private static final List<String> SCORE_KEYS =
      Arrays.asList("score", "vote", "rating", "codeReview", "code_review", "label");

  protected boolean isCommentEvent = false;
  @Getter protected String requestBody;

  public AIChatClient(Configuration config) {
    super(config);
  }

  protected AIChatResponseContent extractContent(Configuration config, String body)
      throws Exception {
    log.debug(
        "Extracting AIChat content: streamOutput={}, isCommentEvent={}, responseChars={}",
        config.getAIStreamOutput(),
        isCommentEvent,
        length(body));
    if (config.getAIStreamOutput() && !isCommentEvent) {
      StringBuilder finalContent = new StringBuilder();
      int streamedLines = 0;
      try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
        String line;
        while ((line = reader.readLine()) != null) {
          streamedLines++;
          extractContentFromLine(line).ifPresent(finalContent::append);
        }
      }
      log.debug(
          "Extracted streamed AIChat tool-call content: streamedLines={}, finalContentChars={}, content={}",
          streamedLines,
          finalContent.length(),
          summarize(finalContent.toString()));
      return convertResponseContentFromJson(finalContent.toString());
    } else {
      AIChatResponseUnstreamed AIChatResponseUnstreamed =
          getGson().fromJson(body, AIChatResponseUnstreamed.class);
      AIChatResponseMessage message = AIChatResponseUnstreamed.getChoices().get(0).getMessage();
      log.debug(
          "Parsed unstreamed AIChat response envelope: choices={}, hasMessage={}, toolCalls={}, messageContentChars={}",
          AIChatResponseUnstreamed.getChoices() == null
              ? null
              : AIChatResponseUnstreamed.getChoices().size(),
          message != null,
          message == null || message.getToolCalls() == null ? 0 : message.getToolCalls().size(),
          message == null ? 0 : length(message.getContent()));
      if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
        log.debug("AIChat response uses tool_calls; converting function arguments");
        return getResponseContent(message.getToolCalls());
      }
      if (message.getContent() != null && !message.getContent().isEmpty()) {
        log.debug(
            "AIChat response uses message.content; content={}", summarize(message.getContent()));
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
    } else {
      log.debug(
          "AIChat response validated: attempt={}, expectedChangeId={}, returnedChangeId={}",
          attemptInd,
          changeId,
          returnedChangeId);
    }
    return isValidated;
  }

  protected AIChatResponseContent getResponseContent(List<AIChatToolCall> toolCalls) {
    log.debug("Converting AIChat tool calls: count={}", toolCalls.size());
    if (toolCalls.size() > 1) {
      return mergeToolCalls(toolCalls);
    } else {
      return getArgumentAsResponse(toolCalls, 0);
    }
  }

  protected Optional<String> extractContentFromLine(String line) {
    String dataPrefix = "data: {\"id\"";

    if (!line.startsWith(dataPrefix)) {
      log.debug("Skipping non-data AIChat streamed line: {}", summarize(line));
      return Optional.empty();
    }
    AIChatResponseStreamed AIChatResponseStreamed =
        getGson().fromJson(line.substring("data: ".length()), AIChatResponseStreamed.class);
    AIChatResponseMessage delta = AIChatResponseStreamed.getChoices().get(0).getDelta();
    if (delta == null || delta.getToolCalls() == null) {
      log.debug("Streamed AIChat line has no delta tool calls: {}", summarize(line));
      return Optional.empty();
    }
    String content = getArgumentAsString(delta.getToolCalls(), 0);
    log.debug("Extracted streamed AIChat tool-call chunk: chars={}", length(content));
    return Optional.ofNullable(content);
  }

  protected AIChatResponseContent convertResponseContentFromText(String content) {
    if (content == null) {
      log.debug("AIChat text content is null; returning empty response content");
      return new AIChatResponseContent("");
    }
    String trimmed = content.trim();
    log.debug(
        "Converting AIChat text content: chars={}, trimmedChars={}, startsAsJson={}, content={}",
        length(content),
        length(trimmed),
        isJsonString(trimmed),
        summarize(content));
    if (isJsonString(trimmed)) {
      try {
        log.debug("AIChat text content is JSON/code-fenced JSON; parsing as JSON response");
        return convertResponseContentFromJson(unwrapJsonCode(trimmed));
      } catch (JsonSyntaxException e) {
        log.warn("AIChat message content looked like JSON but could not be parsed. Skipping.", e);
        return new AIChatResponseContent();
      }
    }
    Optional<AIChatResponseContent> embeddedJsonContent = convertEmbeddedJsonContent(trimmed);
    if (embeddedJsonContent.isPresent()) {
      log.debug("AIChat text content contained embedded review JSON; using parsed embedded JSON");
      return embeddedJsonContent.get();
    }
    log.debug("AIChat text content is plain text; using patchset-level message content");
    return new AIChatResponseContent(content);
  }

  protected Optional<AIChatResponseContent> convertEmbeddedJsonContent(String content) {
    log.debug("Searching AIChat text for embedded JSON review content: chars={}", length(content));
    Optional<AIChatResponseContent> codeBlockContent =
        convertFirstMatchingJson(content, JSON_CODE_BLOCK);
    if (codeBlockContent.isPresent()) {
      return codeBlockContent;
    }
    return convertFirstMatchingJson(content, JSON_REPLIES_BLOCK);
  }

  private Optional<AIChatResponseContent> convertFirstMatchingJson(
      String content, Pattern pattern) {
    Matcher matcher = pattern.matcher(content);
    while (matcher.find()) {
      log.debug(
          "Found embedded AIChat JSON candidate: pattern={}, chars={}, content={}",
          pattern,
          length(matcher.group(1)),
          summarize(matcher.group(1)));
      try {
        Optional<AIChatResponseContent> responseContent =
            toOptionalContent(convertResponseContentFromJson(matcher.group(1)));
        if (responseContent.isPresent()) {
          log.debug("Embedded AIChat JSON candidate produced review content");
          return responseContent;
        }
        log.debug("Embedded AIChat JSON candidate parsed but produced no review content");
      } catch (JsonSyntaxException e) {
        log.warn("Embedded AIChat JSON block could not be parsed", e);
      }
    }
    return Optional.empty();
  }

  private AIChatResponseContent convertResponseContentFromJson(String content) {
    log.debug(
        "Converting AIChat JSON response: chars={}, content={}",
        length(content),
        summarize(content));
    JsonElement parsed = JsonParser.parseString(content);
    if (parsed.isJsonArray()) {
      log.debug("AIChat JSON root is an array; converting as reply array");
      return convertReplyArray(parsed.getAsJsonArray());
    }
    if (!parsed.isJsonObject()) {
      log.warn("AIChat JSON response was not an object. Skipping.");
      return new AIChatResponseContent();
    }
    JsonObject object = parsed.getAsJsonObject();
    log.debug("AIChat JSON root object keys: {}", object.keySet());
    for (String key : RESPONSE_ARRAY_KEYS) {
      Optional<AIChatResponseContent> responseContent = convertReplyArrayProperty(object, key);
      if (responseContent.isPresent()) {
        log.debug("AIChat JSON response matched reply array/map key `{}`", key);
        return responseContent.get();
      }
    }
    Optional<AIChatReplyItem> replyItem = convertReplyObject(object);
    if (replyItem.isPresent()) {
      log.debug("AIChat JSON response is a single reply object");
      return convertSingleReply(replyItem.get(), object);
    }
    for (String key : RESPONSE_TEXT_KEYS) {
      Optional<String> text = getStringProperty(object, key);
      if (text.isPresent()) {
        log.debug("AIChat JSON response matched text key `{}`; recursively parsing text", key);
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
    log.debug(
        "Converting AIChat response property `{}`: isArray={}, isObject={}, isString={}",
        key,
        element.isJsonArray(),
        element.isJsonObject(),
        element.isJsonPrimitive() && element.getAsJsonPrimitive().isString());
    if (element.isJsonArray()) {
      return toOptionalContent(convertReplyArray(element.getAsJsonArray(), object));
    }
    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      return toOptionalContent(convertResponseContentFromText(element.getAsString()));
    }
    if (element.isJsonObject()) {
      return toOptionalContent(convertReplyObjectMap(element.getAsJsonObject(), object));
    }
    return Optional.empty();
  }

  private AIChatResponseContent convertReplyArray(JsonArray array) {
    return convertReplyArray(array, null);
  }

  private AIChatResponseContent convertReplyArray(JsonArray array, JsonObject sourceObject) {
    AIChatResponseContent responseContent = new AIChatResponseContent();
    List<AIChatReplyItem> replies = new ArrayList<>();
    Optional<Integer> sourceScore =
        sourceObject == null ? Optional.empty() : getIntegerProperty(sourceObject, SCORE_KEYS);
    log.debug(
        "Converting AIChat reply array: items={}, sourceScorePresent={}",
        array.size(),
        sourceScore.isPresent());
    for (int index = 0; index < array.size(); index++) {
      JsonElement element = array.get(index);
      Optional<AIChatReplyItem> replyItem = convertReplyElement(element);
      if (replyItem.isPresent()) {
        AIChatReplyItem item = replyItem.get();
        sourceScore.ifPresent(score -> setScoreIfMissing(item, score));
        replies.add(item);
        log.debug(
            "AIChat reply array item #{} converted: replyChars={}, filename={}, lineNumber={}, score={}",
            index,
            length(item.getReply()),
            item.getFilename(),
            item.getLineNumber(),
            item.getScore());
      } else {
        log.debug(
            "AIChat reply array item #{} skipped: unsupported or missing reply text, element={}",
            index,
            summarize(element.toString()));
      }
    }
    responseContent.setReplies(replies);
    if (sourceObject != null) {
      getStringProperty(sourceObject, "changeId").ifPresent(responseContent::setChangeId);
    }
    return responseContent;
  }

  private AIChatResponseContent convertReplyObjectMap(
      JsonObject commentsByFile, JsonObject sourceObject) {
    AIChatResponseContent responseContent = new AIChatResponseContent();
    List<AIChatReplyItem> replies = new ArrayList<>();
    Optional<Integer> sourceScore =
        sourceObject == null ? Optional.empty() : getIntegerProperty(sourceObject, SCORE_KEYS);
    log.debug(
        "Converting AIChat reply object map: filenames={}, sourceScorePresent={}",
        commentsByFile.keySet(),
        sourceScore.isPresent());
    for (String filename : commentsByFile.keySet()) {
      JsonElement element = commentsByFile.get(filename);
      if (element.isJsonArray()) {
        for (JsonElement itemElement : element.getAsJsonArray()) {
          addMappedReply(replies, itemElement, filename, sourceScore);
        }
      } else {
        addMappedReply(replies, element, filename, sourceScore);
      }
    }
    responseContent.setReplies(replies);
    if (sourceObject != null) {
      getStringProperty(sourceObject, "changeId").ifPresent(responseContent::setChangeId);
    }
    return responseContent;
  }

  private void addMappedReply(
      List<AIChatReplyItem> replies,
      JsonElement element,
      String filename,
      Optional<Integer> sourceScore) {
    Optional<AIChatReplyItem> replyItem = convertReplyElement(element);
    if (replyItem.isPresent()) {
      AIChatReplyItem item = replyItem.get();
      if (item.getFilename() == null && looksLikeFilename(filename)) {
        item.setFilename(filename);
      }
      sourceScore.ifPresent(score -> setScoreIfMissing(item, score));
      replies.add(item);
      log.debug(
          "AIChat mapped reply converted: mapKey={}, filename={}, lineNumber={}, replyChars={}, score={}",
          filename,
          item.getFilename(),
          item.getLineNumber(),
          length(item.getReply()),
          item.getScore());
    } else {
      log.debug(
          "AIChat mapped reply skipped: mapKey={}, element={}",
          filename,
          summarize(element.toString()));
    }
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
      log.debug("AIChat primitive string reply converted: chars={}", length(replyItem.getReply()));
      return Optional.of(replyItem);
    }
    log.debug("AIChat reply element has unsupported JSON type: {}", summarize(element.toString()));
    return Optional.empty();
  }

  private Optional<AIChatReplyItem> convertReplyObject(JsonObject object) {
    log.debug("Converting AIChat reply object with keys: {}", object.keySet());
    AIChatReplyItem replyItem = getGson().fromJson(object, AIChatReplyItem.class);
    if (replyItem.getReply() == null) {
      getStringProperty(object, REPLY_TEXT_KEYS)
          .ifPresent(
              value -> {
                replyItem.setReply(value);
                log.debug("AIChat reply text extracted through known key; chars={}", length(value));
              });
    }
    if (replyItem.getReply() == null) {
      getFallbackReplyText(object)
          .ifPresent(
              value -> {
                replyItem.setReply(value);
                log.debug(
                    "AIChat reply text extracted through fallback key scan; chars={}",
                    length(value));
              });
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
    if (replyItem.getScore() == null) {
      getIntegerProperty(object, SCORE_KEYS).ifPresent(replyItem::setScore);
    }
    populateLocationFields(replyItem, object);
    log.debug(
        "AIChat reply object conversion result: hasReply={}, replyChars={}, filename={}, lineNumber={}, codeSnippetChars={}, codeToken={}, score={}, relevance={}, repeated={}, conflicting={}",
        replyItem.getReply() != null,
        length(replyItem.getReply()),
        replyItem.getFilename(),
        replyItem.getLineNumber(),
        length(replyItem.getCodeSnippet()),
        replyItem.getCodeToken(),
        replyItem.getScore(),
        replyItem.getRelevance(),
        replyItem.isRepeated(),
        replyItem.isConflicting());
    return replyItem.getReply() == null ? Optional.empty() : Optional.of(replyItem);
  }

  private void populateLocationFields(AIChatReplyItem replyItem, JsonObject object) {
    for (String key :
        Arrays.asList("location", "position", "range", "commentRange", "comment_range")) {
      if (!object.has(key) || !object.get(key).isJsonObject()) {
        continue;
      }
      JsonObject nested = object.get(key).getAsJsonObject();
      log.debug("Reading nested AIChat location object `{}` with keys: {}", key, nested.keySet());
      if (replyItem.getFilename() == null) {
        getStringProperty(nested, FILENAME_KEYS).ifPresent(replyItem::setFilename);
      }
      if (replyItem.getLineNumber() == null) {
        getIntegerProperty(nested, LINE_NUMBER_KEYS).ifPresent(replyItem::setLineNumber);
      }
      if (replyItem.getCodeSnippet() == null) {
        getStringProperty(nested, CODE_SNIPPET_KEYS).ifPresent(replyItem::setCodeSnippet);
      }
      if (replyItem.getCodeToken() == null) {
        getStringProperty(nested, CODE_TOKEN_KEYS).ifPresent(replyItem::setCodeToken);
      }
    }
  }

  private Optional<String> getFallbackReplyText(JsonObject object) {
    Optional<String> preferred = Optional.empty();
    Optional<String> longest = Optional.empty();
    for (String key : object.keySet()) {
      JsonElement element = object.get(key);
      Optional<String> value = getFallbackStringValue(element);
      if (value.isEmpty() || isMetadataKey(key)) {
        continue;
      }
      String text = value.get().trim();
      if (text.isEmpty() || isJsonString(text)) {
        continue;
      }
      if (looksLikeReplyTextKey(key)) {
        log.debug("AIChat fallback candidate preferred text key `{}` chars={}", key, length(text));
        preferred = chooseLonger(preferred, text);
      }
      log.debug("AIChat fallback candidate text key `{}` chars={}", key, length(text));
      longest = chooseLonger(longest, text);
    }
    return preferred.isPresent() ? preferred : longest;
  }

  private Optional<String> chooseLonger(Optional<String> current, String candidate) {
    if (current.isEmpty() || candidate.length() > current.get().length()) {
      return Optional.of(candidate);
    }
    return current;
  }

  private Optional<String> getFallbackStringValue(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return Optional.empty();
    }
    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      return Optional.of(element.getAsString());
    }
    if (element.isJsonObject()) {
      JsonObject object = element.getAsJsonObject();
      Optional<String> value = getStringProperty(object, "value");
      if (value.isPresent()) {
        return value;
      }
      return getStringProperty(object, REPLY_TEXT_KEYS);
    }
    return Optional.empty();
  }

  private boolean looksLikeReplyTextKey(String key) {
    String normalized = key.toLowerCase();
    return normalized.contains("reply")
        || normalized.contains("comment")
        || normalized.contains("message")
        || normalized.contains("review")
        || normalized.contains("finding")
        || normalized.contains("issue")
        || normalized.contains("suggest")
        || normalized.contains("reason")
        || normalized.contains("problem")
        || normalized.contains("description")
        || normalized.contains("detail");
  }

  private boolean isMetadataKey(String key) {
    return matchesAnyKey(key, FILENAME_KEYS)
        || matchesAnyKey(key, LINE_NUMBER_KEYS)
        || matchesAnyKey(key, CODE_SNIPPET_KEYS)
        || matchesAnyKey(key, CODE_TOKEN_KEYS)
        || matchesAnyKey(key, SCORE_KEYS)
        || "id".equalsIgnoreCase(key)
        || "changeId".equalsIgnoreCase(key)
        || "relevance".equalsIgnoreCase(key)
        || "repeated".equalsIgnoreCase(key)
        || "conflicting".equalsIgnoreCase(key);
  }

  private boolean matchesAnyKey(String key, List<String> candidates) {
    return candidates.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(key));
  }

  private Optional<AIChatResponseContent> toOptionalContent(AIChatResponseContent responseContent) {
    if (responseContent.getReplies() != null && !responseContent.getReplies().isEmpty()) {
      log.debug(
          "AIChat response content accepted with replies={}", responseContent.getReplies().size());
      return Optional.of(responseContent);
    }
    if (responseContent.getMessageContent() != null
        && !responseContent.getMessageContent().isEmpty()) {
      log.debug(
          "AIChat response content accepted with messageContentChars={}",
          length(responseContent.getMessageContent()));
      return Optional.of(responseContent);
    }
    log.debug("AIChat response content rejected because it has no replies and no message content");
    return Optional.empty();
  }

  private void setScoreIfMissing(AIChatReplyItem replyItem, Integer score) {
    if (replyItem.getScore() == null) {
      replyItem.setScore(score);
    }
  }

  private boolean looksLikeFilename(String value) {
    return value.contains("/") || value.contains("\\") || value.contains(".");
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
    log.debug(
        "Converting AIChat tool call argument #{}: chars={}, content={}",
        ind,
        length(getArgumentAsString(toolCalls, ind)),
        summarize(getArgumentAsString(toolCalls, ind)));
    return convertResponseContentFromJson(getArgumentAsString(toolCalls, ind));
  }

  private AIChatResponseContent mergeToolCalls(List<AIChatToolCall> toolCalls) {
    AIChatResponseContent responseContent = getArgumentAsResponse(toolCalls, 0);
    for (int ind = 1; ind < toolCalls.size(); ind++) {
      log.debug("Merging AIChat tool call #{} into response content", ind);
      responseContent.getReplies().addAll(getArgumentAsResponse(toolCalls, ind).getReplies());
    }
    log.debug(
        "Merged AIChat tool calls complete: totalReplies={}",
        responseContent.getReplies() == null ? null : responseContent.getReplies().size());
    return responseContent;
  }
}
