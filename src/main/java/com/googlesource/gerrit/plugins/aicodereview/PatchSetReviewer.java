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

package com.googlesource.gerrit.plugins.aicodereview;

import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.length;
import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.summarize;
import static com.googlesource.gerrit.plugins.aicodereview.utils.JsonTextUtils.isJsonString;
import static com.googlesource.gerrit.plugins.aicodereview.utils.JsonTextUtils.unwrapJsonCode;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.aicodereview.config.Configuration;
import com.googlesource.gerrit.plugins.aicodereview.data.ChangeSetDataHandler;
import com.googlesource.gerrit.plugins.aicodereview.interfaces.mode.common.client.api.openapi.ChatAIClient;
import com.googlesource.gerrit.plugins.aicodereview.localization.Localizer;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.messages.DebugCodeBlocksReview;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.patch.comment.GerritCommentRange;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatReplyItem;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatResponseContent;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.review.ReviewBatch;
import com.googlesource.gerrit.plugins.aicodereview.settings.Settings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PatchSetReviewer {
  private static final String SPLIT_REVIEW_MSG =
      "Too many changes. Please consider splitting into patches smaller "
          + "than %s lines for review.";

  private final Configuration config;
  private final GerritClient gerritClient;
  private final ChangeSetData changeSetData;
  private final Provider<GerritClientReview> clientReviewProvider;
  @Getter private final ChatAIClient chatAIClient;
  private final Localizer localizer;
  private final DebugCodeBlocksReview debugCodeBlocksReview;

  private GerritCommentRange gerritCommentRange;
  private List<ReviewBatch> reviewBatches;
  private List<GerritComment> commentProperties;
  private List<Integer> reviewScores;

  @Inject
  PatchSetReviewer(
      GerritClient gerritClient,
      Configuration config,
      ChangeSetData changeSetData,
      Provider<GerritClientReview> clientReviewProvider,
      ChatAIClient chatAIClient,
      Localizer localizer) {
    this.config = config;
    this.gerritClient = gerritClient;
    this.changeSetData = changeSetData;
    this.clientReviewProvider = clientReviewProvider;
    this.chatAIClient = chatAIClient;
    this.localizer = localizer;
    debugCodeBlocksReview = new DebugCodeBlocksReview(localizer);
  }

  public void review(GerritChange change) throws Exception {
    log.debug(
        "AI review pipeline started: change={}, project={}, branch={}, isCommentEvent={}, forcedReview={}",
        change.getFullChangeId(),
        change.getProjectNameKey(),
        change.getBranchNameKey(),
        change.getIsCommentEvent(),
        changeSetData.getForcedReview());
    reviewBatches = new ArrayList<>();
    reviewScores = new ArrayList<>();
    commentProperties = gerritClient.getClientData(change).getCommentProperties();
    gerritCommentRange = new GerritCommentRange(gerritClient, change);
    String patchSet = gerritClient.getPatchSet(change);
    log.debug(
        "PatchSet retrieved for AI review: change={}, chars={}, lines={}, commentProperties={}",
        change.getFullChangeId(),
        length(patchSet),
        Arrays.asList(patchSet.split("\n")).size(),
        commentProperties == null ? 0 : commentProperties.size());
    if (patchSet.isEmpty() && config.getAIMode() == Settings.Modes.stateless) {
      log.info("No file to review has been found in the PatchSet");
      return;
    }
    ChangeSetDataHandler.update(config, change, gerritClient, changeSetData, localizer);
    log.debug(
        "ChangeSetData after update: change={}, shouldRequest={}, shouldHide={}, forcedReview={}, replyFilterEnabled={}, debugReviewMode={}, directives={}, reviewSystemMessagePresent={}, aiPromptChars={}",
        change.getFullChangeId(),
        changeSetData.shouldRequestAICodeReview(),
        changeSetData.shouldHideAICodeReview(),
        changeSetData.getForcedReview(),
        changeSetData.getReplyFilterEnabled(),
        changeSetData.getDebugReviewMode(),
        changeSetData.getDirectives().size(),
        changeSetData.getReviewSystemMessage() != null,
        length(changeSetData.getReviewAIDataPrompt()));

    if (changeSetData.shouldRequestAICodeReview()) {
      AIChatResponseContent reviewReply = getReviewReply(change, patchSet);
      log.debug(
          "AIChat parsed response received: change={}, replies={}, messageContentChars={}, changeId={}",
          change.getFullChangeId(),
          reviewReply.getReplies() == null ? null : reviewReply.getReplies().size(),
          length(reviewReply.getMessageContent()),
          reviewReply.getChangeId());
      log.debug("AIChat parsed response detail: {}", reviewReply);

      retrieveReviewBatches(reviewReply, change);
    } else {
      log.debug(
          "AI review request skipped by ChangeSetData: change={}, systemMessage={}, hidden={}",
          change.getFullChangeId(),
          summarize(changeSetData.getReviewSystemMessage()),
          changeSetData.shouldHideAICodeReview());
    }
    Integer reviewScore = getReviewScore(change);
    log.debug(
        "Submitting Gerrit review from pipeline: change={}, reviewBatches={}, reviewScores={}, finalReviewScore={}",
        change.getFullChangeId(),
        reviewBatches.size(),
        reviewScores,
        reviewScore);
    clientReviewProvider.get().setReview(change, reviewBatches, changeSetData, reviewScore);
  }

  private void setCommentBatchMap(ReviewBatch batchMap, Integer batchID) {
    log.debug(
        "Mapping AI reply to existing Gerrit comment: requestedBatchId={}, commentProperties={}",
        batchID,
        commentProperties == null ? 0 : commentProperties.size());
    if (commentProperties != null && batchID < commentProperties.size()) {
      GerritComment commentProperty = commentProperties.get(batchID);
      if (commentProperty != null
          && (commentProperty.getLine() != null || commentProperty.getRange() != null)) {
        String id = commentProperty.getId();
        String filename = commentProperty.getFilename();
        Integer line = commentProperty.getLine();
        GerritCodeRange range = commentProperty.getRange();
        if (range != null) {
          batchMap.setId(id);
          batchMap.setFilename(filename);
          batchMap.setLine(line);
          batchMap.setRange(range);
          log.debug(
              "Mapped AI reply to existing Gerrit comment: id={}, filename={}, line={}, range={}",
              id,
              filename,
              line,
              range);
        }
      } else {
        log.debug(
            "Existing Gerrit comment had no line/range, cannot map inline reply: batchID={}, comment={}",
            batchID,
            commentProperty);
      }
    } else {
      log.debug(
          "Existing Gerrit comment index not available: requestedBatchId={}, commentProperties={}",
          batchID,
          commentProperties == null ? 0 : commentProperties.size());
    }
  }

  private void setPatchSetReviewBatchMap(ReviewBatch batchMap, AIChatReplyItem replyItem) {
    log.debug(
        "Resolving AI reply location: filename={}, lineNumber={}, codeSnippetChars={}, codeToken={}",
        replyItem.getFilename(),
        replyItem.getLineNumber(),
        length(replyItem.getCodeSnippet()),
        replyItem.getCodeToken());
    Optional<GerritCodeRange> optGerritCommentRange =
        gerritCommentRange.getGerritCommentRange(replyItem);
    if (optGerritCommentRange.isPresent()) {
      GerritCodeRange gerritCodeRange = optGerritCommentRange.get();
      batchMap.setFilename(replyItem.getFilename());
      batchMap.setLine(gerritCodeRange.getStartLine());
      batchMap.setRange(gerritCodeRange);
      log.debug(
          "AI reply mapped to precise Gerrit range: filename={}, line={}, range={}",
          batchMap.getFilename(),
          batchMap.getLine(),
          batchMap.getRange());
    } else if (replyItem.getFilename() != null && replyItem.getLineNumber() != null) {
      batchMap.setFilename(replyItem.getFilename());
      batchMap.setLine(replyItem.getLineNumber());
      log.debug(
          "AI reply mapped to Gerrit line fallback: filename={}, line={}",
          batchMap.getFilename(),
          batchMap.getLine());
    } else {
      log.debug(
          "AI reply has no inline location; will become patchset-level comment: filename={}, lineNumber={}",
          replyItem.getFilename(),
          replyItem.getLineNumber());
    }
  }

  private void retrieveReviewBatches(AIChatResponseContent reviewReply, GerritChange change) {
    log.debug(
        "Building review batches from AI response: replies={}, messageContentChars={}",
        reviewReply.getReplies() == null ? null : reviewReply.getReplies().size(),
        length(reviewReply.getMessageContent()));
    if ((reviewReply.getReplies() == null || reviewReply.getReplies().isEmpty())
        && reviewReply.getMessageContent() != null
        && !reviewReply.getMessageContent().isEmpty()) {
      log.debug(
          "AI response has messageContent but no replies; adding patchset-level batch: {}",
          summarize(reviewReply.getMessageContent()));
      reviewBatches.add(new ReviewBatch(reviewReply.getMessageContent()));
      return;
    }
    if (reviewReply.getReplies() == null) {
      log.warn("AIChat response contains no replies and no message content");
      return;
    }
    List<ReviewBatch> hiddenReviewBatches = new ArrayList<>();
    for (int replyIndex = 0; replyIndex < reviewReply.getReplies().size(); replyIndex++) {
      AIChatReplyItem replyItem = reviewReply.getReplies().get(replyIndex);
      String reply = getReplyText(replyItem);
      log.debug(
          "Processing AI reply item #{}: replyChars={}, score={}, relevance={}, repeated={}, conflicting={}, filename={}, lineNumber={}, codeSnippetChars={}, codeToken={}",
          replyIndex,
          length(reply),
          replyItem.getScore(),
          replyItem.getRelevance(),
          replyItem.isRepeated(),
          replyItem.isConflicting(),
          replyItem.getFilename(),
          replyItem.getLineNumber(),
          length(replyItem.getCodeSnippet()),
          replyItem.getCodeToken());
      if (reply.isBlank()) {
        log.warn("AIChat reply text is empty for reply item {}", replyItem);
        continue;
      }
      Integer score = replyItem.getScore();
      boolean isNotNegative = isNotNegativeReply(score);
      boolean isIrrelevant = isIrrelevantReply(replyItem);
      boolean isHidden =
          replyItem.isRepeated() || replyItem.isConflicting() || isIrrelevant || isNotNegative;
      log.debug(
          "AI reply filter decision #{}: isHidden={}, isNotNegative={}, isIrrelevant={}, replyFilterEnabled={}, filterNegativeComments={}, filterBelowScore={}, filterRelevantComments={}, relevanceThreshold={}",
          replyIndex,
          isHidden,
          isNotNegative,
          isIrrelevant,
          changeSetData.getReplyFilterEnabled(),
          config.getFilterNegativeComments(),
          config.getFilterCommentsBelowScore(),
          config.getFilterRelevantComments(),
          config.getFilterCommentsRelevanceThreshold());
      if (!replyItem.isConflicting() && !isIrrelevant && score != null) {
        log.debug("Score added: {}", score);
        reviewScores.add(score);
      }
      if (changeSetData.getDebugReviewMode()) {
        reply += debugCodeBlocksReview.getDebugCodeBlock(replyItem, isHidden);
      }
      ReviewBatch batchMap = new ReviewBatch(reply);
      if (change.getIsCommentEvent() && replyItem.getId() != null) {
        setCommentBatchMap(batchMap, replyItem.getId());
      } else {
        setPatchSetReviewBatchMap(batchMap, replyItem);
      }
      if (changeSetData.getReplyFilterEnabled() && isHidden) {
        log.debug(
            "AI reply item #{} hidden by filter but kept for all-hidden fallback: filename={}, line={}, range={}",
            replyIndex,
            batchMap.getFilename(),
            batchMap.getLine(),
            batchMap.getRange());
        hiddenReviewBatches.add(batchMap);
        continue;
      }
      log.debug(
          "AI reply item #{} added to review batches: filename={}, line={}, range={}, message={}",
          replyIndex,
          batchMap.getFilename(),
          batchMap.getLine(),
          batchMap.getRange(),
          summarize(batchMap.getContent()));
      reviewBatches.add(batchMap);
    }
    if (reviewBatches.isEmpty() && !hiddenReviewBatches.isEmpty()) {
      log.warn("AIChat reply filter hid every reply; submitting filtered replies as fallback");
      reviewBatches.addAll(hiddenReviewBatches);
    }
    log.debug(
        "Review batch build complete: submittedBatches={}, hiddenFallbackCandidates={}, reviewScores={}",
        reviewBatches.size(),
        hiddenReviewBatches.size(),
        reviewScores);
  }

  private String getReplyText(AIChatReplyItem replyItem) {
    String reply = replyItem.getReply();
    if (reply == null) {
      return "";
    }
    String trimmed = reply.trim();
    if (!isJsonString(trimmed)) {
      return reply;
    }
    log.debug("AI reply text itself looks like JSON; attempting to unwrap reply field");
    try {
      JsonElement parsed = JsonParser.parseString(unwrapJsonCode(trimmed));
      if (parsed.isJsonObject()) {
        JsonObject object = parsed.getAsJsonObject();
        if (object.has("reply") && object.get("reply").isJsonPrimitive()) {
          log.debug("AI reply JSON unwrapped through `reply` field");
          return object.get("reply").getAsString();
        }
      }
    } catch (JsonSyntaxException e) {
      log.warn("AIChat reply looked like JSON but could not be parsed", e);
    }
    return "";
  }

  private AIChatResponseContent getReviewReply(GerritChange change, String patchSet)
      throws Exception {
    List<String> patchLines = Arrays.asList(patchSet.split("\n"));
    log.debug(
        "Preparing AI review request: change={}, patchLines={}, maxReviewLines={}, patchChars={}",
        change.getFullChangeId(),
        patchLines.size(),
        config.getMaxReviewLines(),
        length(patchSet));
    if (patchLines.size() > config.getMaxReviewLines()) {
      log.warn("Patch set too large. Skipping review. changeId: {}", change.getFullChangeId());
      return new AIChatResponseContent(String.format(SPLIT_REVIEW_MSG, config.getMaxReviewLines()));
    }

    log.debug("Sending patchset to AI client: change={}", change.getFullChangeId());
    return chatAIClient.ask(changeSetData, change, patchSet);
  }

  private Integer getReviewScore(GerritChange change) {
    if (config.isVotingEnabled()) {
      Integer score =
          reviewScores.isEmpty()
              ? (change.getIsCommentEvent() || reviewBatches.isEmpty() ? null : 0)
              : Collections.min(reviewScores);
      log.debug(
          "Computed Gerrit review score: change={}, votingEnabled=true, collectedScores={}, reviewBatches={}, score={}",
          change.getFullChangeId(),
          reviewScores,
          reviewBatches.size(),
          score);
      return score;
    } else {
      log.debug("Gerrit voting disabled; no Code-Review label will be sent");
      return null;
    }
  }

  private boolean isNotNegativeReply(Integer score) {
    return score != null
        && config.getFilterNegativeComments()
        && score >= config.getFilterCommentsBelowScore();
  }

  private boolean isIrrelevantReply(AIChatReplyItem replyItem) {
    return config.getFilterRelevantComments()
        && replyItem.getRelevance() != null
        && replyItem.getRelevance() < config.getFilterCommentsRelevanceThreshold();
  }
}
