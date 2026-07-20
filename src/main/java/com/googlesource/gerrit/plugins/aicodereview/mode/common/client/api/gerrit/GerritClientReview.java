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

package com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit;

import static com.googlesource.gerrit.plugins.aicodereview.mode.common.client.prompt.MessageSanitizer.sanitizeAIChatMessage;
import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.length;
import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.summarize;
import static com.googlesource.gerrit.plugins.aicodereview.utils.TextUtils.joinWithDoubleNewLine;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.gerrit.entities.LabelId;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.client.Comment;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.aicodereview.config.Configuration;
import com.googlesource.gerrit.plugins.aicodereview.config.DynamicConfiguration;
import com.googlesource.gerrit.plugins.aicodereview.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.aicodereview.localization.Localizer;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.messages.DebugCodeBlocksDynamicSettings;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.review.ReviewBatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GerritClientReview extends GerritClientAccount {
  private final PluginDataHandlerProvider pluginDataHandlerProvider;
  private final Localizer localizer;
  private final DebugCodeBlocksDynamicSettings debugCodeBlocksDynamicSettings;

  @VisibleForTesting
  @Inject
  public GerritClientReview(
      Configuration config,
      AccountCache accountCache,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer) {
    super(config, accountCache);
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.localizer = localizer;
    debugCodeBlocksDynamicSettings = new DebugCodeBlocksDynamicSettings(localizer);
  }

  public void setReview(
      GerritChange change,
      List<ReviewBatch> reviewBatches,
      ChangeSetData changeSetData,
      Integer reviewScore)
      throws Exception {
    log.debug(
        "Preparing Gerrit review submission: change={}, batches={}, reviewScore={}, forcedReview={}, hideReview={}, systemMessagePresent={}",
        change.getFullChangeId(),
        reviewBatches == null ? null : reviewBatches.size(),
        reviewScore,
        changeSetData.getForcedReview(),
        changeSetData.shouldHideAICodeReview(),
        changeSetData.getReviewSystemMessage() != null);
    ReviewInput reviewInput = buildReview(reviewBatches, changeSetData, reviewScore);
    log.debug(
        "Gerrit ReviewInput built: change={}, comments={}, commentCount={}, messageChars={}, labels={}",
        change.getFullChangeId(),
        reviewInput.comments == null ? null : reviewInput.comments.keySet(),
        reviewInput.comments == null ? 0 : countComments(reviewInput.comments),
        length(reviewInput.message),
        reviewInput.labels);
    if (reviewInput.comments == null
        && reviewInput.message == null
        && (reviewInput.labels == null || reviewInput.labels.isEmpty())) {
      log.warn(
          "Gerrit review submission skipped because ReviewInput is empty: change={}, batches={}, reviewScore={}, hideReview={}, systemMessage={}",
          change.getFullChangeId(),
          reviewBatches == null ? null : reviewBatches.size(),
          reviewScore,
          changeSetData.shouldHideAICodeReview(),
          summarize(changeSetData.getReviewSystemMessage()));
      return;
    }
    try (ManualRequestContext requestContext = config.openRequestContext()) {
      log.debug(
          "Submitting Gerrit review API call: project={}, branch={}, changeKey={}, comments={}, labels={}, message={}",
          change.getProjectName(),
          change.getBranchNameKey().shortName(),
          change.getChangeKey().get(),
          reviewInput.comments == null ? null : reviewInput.comments.keySet(),
          reviewInput.labels,
          summarize(reviewInput.message));
      ReviewResult result =
          config
              .getGerritApi()
              .changes()
              .id(
                  change.getProjectName(),
                  change.getBranchNameKey().shortName(),
                  change.getChangeKey().get())
              .current()
              .review(reviewInput);

      if (!Strings.isNullOrEmpty(result.error)) {
        log.error("Review setting failed with status code: {}", result.error);
      } else {
        log.debug(
            "Gerrit review API call completed successfully: change={}, comments={}, labels={}",
            change.getFullChangeId(),
            reviewInput.comments == null ? 0 : countComments(reviewInput.comments),
            reviewInput.labels);
      }
    }
  }

  public void setReview(
      GerritChange change, List<ReviewBatch> reviewBatches, ChangeSetData changeSetData)
      throws Exception {
    setReview(change, reviewBatches, changeSetData, null);
  }

  private ReviewInput buildReview(
      List<ReviewBatch> reviewBatches, ChangeSetData changeSetData, Integer reviewScore) {
    ReviewInput reviewInput = ReviewInput.create();
    Map<String, List<CommentInput>> comments = new HashMap<>();
    boolean hasExplicitSystemMessage = changeSetData.getReviewSystemMessage() != null;
    String systemMessage = null;
    log.debug(
        "Building Gerrit ReviewInput: batches={}, reviewScore={}, hasExplicitSystemMessage={}, shouldHide={}",
        reviewBatches == null ? null : reviewBatches.size(),
        reviewScore,
        hasExplicitSystemMessage,
        changeSetData.shouldHideAICodeReview());
    if (changeSetData.getReviewSystemMessage() != null) {
      systemMessage = changeSetData.getReviewSystemMessage();
      log.debug("ReviewInput will use explicit system message: {}", summarize(systemMessage));
    } else if (!changeSetData.shouldHideAICodeReview()) {
      comments = getReviewComments(reviewBatches);
      if (reviewScore != null) {
        reviewInput.label(LabelId.CODE_REVIEW, reviewScore);
        log.debug("ReviewInput label set: {}={}", LabelId.CODE_REVIEW, reviewScore);
      } else {
        log.debug("ReviewInput label not set because reviewScore is null");
      }
    } else {
      log.debug("ReviewInput comments hidden because shouldHideAICodeReview=true");
    }
    updateSystemMessage(reviewInput, comments.isEmpty() && hasExplicitSystemMessage, systemMessage);
    if (!comments.isEmpty()) {
      reviewInput.comments = comments;
    }
    return reviewInput;
  }

  private void updateSystemMessage(
      ReviewInput reviewInput, boolean emptyComments, String systemMessage) {
    List<String> messages = new ArrayList<>();
    Map<String, String> dynamicConfig =
        new DynamicConfiguration(pluginDataHandlerProvider).getDynamicConfig();
    if (dynamicConfig != null && !dynamicConfig.isEmpty()) {
      log.debug("Adding dynamic configuration debug block to Gerrit review message");
      messages.add(debugCodeBlocksDynamicSettings.getDebugCodeBlock(dynamicConfig));
    }
    if (emptyComments) {
      log.debug("Adding system message to Gerrit review: {}", summarize(systemMessage));
      messages.add(localizer.getText("system.message.prefix") + ' ' + systemMessage);
    }
    if (!messages.isEmpty()) {
      reviewInput.message(joinWithDoubleNewLine(messages));
      log.debug("Gerrit review message built: chars={}", length(reviewInput.message));
    }
  }

  private Map<String, List<CommentInput>> getReviewComments(List<ReviewBatch> reviewBatches) {
    Map<String, List<CommentInput>> comments = new HashMap<>();
    if (reviewBatches == null) {
      log.debug("No review batches provided for Gerrit comments");
      return comments;
    }
    for (int index = 0; index < reviewBatches.size(); index++) {
      ReviewBatch reviewBatch = reviewBatches.get(index);
      String message = sanitizeAIChatMessage(reviewBatch.getContent());
      log.debug(
          "Converting ReviewBatch #{} to Gerrit comment: filename={}, line={}, range={}, id={}, rawChars={}, sanitizedChars={}",
          index,
          reviewBatch.getFilename(),
          reviewBatch.getLine(),
          reviewBatch.getRange(),
          reviewBatch.getId(),
          length(reviewBatch.getContent()),
          length(message));
      if (message.trim().isEmpty()) {
        log.info("Empty message from review not submitted.");
        continue;
      }
      boolean unresolved;
      String filename = reviewBatch.getFilename();
      List<CommentInput> filenameComments = comments.getOrDefault(filename, new ArrayList<>());
      CommentInput filenameComment = new CommentInput();
      filenameComment.message = message;
      if (reviewBatch.getLine() != null || reviewBatch.getRange() != null) {
        filenameComment.line = reviewBatch.getLine();
        Optional.ofNullable(reviewBatch.getRange())
            .ifPresent(
                r -> {
                  Comment.Range range = new Comment.Range();
                  range.startLine = r.startLine;
                  range.startCharacter = r.startCharacter;
                  range.endLine = r.endLine;
                  range.endCharacter = r.endCharacter;
                  filenameComment.range = range;
                });
        filenameComment.inReplyTo = reviewBatch.getId();
        unresolved = !config.getInlineCommentsAsResolved();
        log.debug(
            "ReviewBatch #{} became inline Gerrit comment: filename={}, line={}, range={}, unresolved={}",
            index,
            filename,
            filenameComment.line,
            filenameComment.range,
            unresolved);
      } else {
        unresolved = !config.getPatchSetCommentsAsResolved();
        log.debug(
            "ReviewBatch #{} became patchset-level Gerrit comment: filename={}, unresolved={}",
            index,
            filename,
            unresolved);
      }
      filenameComment.unresolved = unresolved;
      filenameComments.add(filenameComment);
      comments.putIfAbsent(filename, filenameComments);
    }
    log.debug(
        "Gerrit comments map built: files={}, totalComments={}",
        comments.keySet(),
        countComments(comments));
    return comments;
  }

  private int countComments(Map<String, List<CommentInput>> comments) {
    return comments.values().stream().mapToInt(List::size).sum();
  }
}
