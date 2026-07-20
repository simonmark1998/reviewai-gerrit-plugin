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

package com.googlesource.gerrit.plugins.aicodereview.mode.common.client.patch.comment;

import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.length;
import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.summarize;

import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.patch.code.InlineCode;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.patch.diff.FileDiffProcessed;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.openai.AIChatReplyItem;
import java.util.HashMap;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GerritCommentRange {
  private final HashMap<String, FileDiffProcessed> fileDiffsProcessed;

  public GerritCommentRange(GerritClient gerritClient, GerritChange change) {
    fileDiffsProcessed = gerritClient.getFileDiffsProcessed(change);
    log.debug(
        "GerritCommentRange initialized: change={}, files={}",
        change.getFullChangeId(),
        fileDiffsProcessed == null ? null : fileDiffsProcessed.keySet());
  }

  public Optional<GerritCodeRange> getGerritCommentRange(AIChatReplyItem replyItem) {
    Optional<GerritCodeRange> gerritCommentRange = Optional.empty();
    String filename = replyItem.getFilename();
    log.debug(
        "Resolving Gerrit inline range for AI reply: filename={}, lineNumber={}, codeSnippetChars={}, codeSnippet={}, codeToken={}",
        filename,
        replyItem.getLineNumber(),
        length(replyItem.getCodeSnippet()),
        summarize(replyItem.getCodeSnippet()),
        replyItem.getCodeToken());
    if (filename == null || filename.equals("/COMMIT_MSG")) {
      log.debug(
          "Skipping inline range lookup because filename is null or commit message: {}", filename);
      return gerritCommentRange;
    }
    if (replyItem.getCodeSnippet() == null && replyItem.getCodeToken() == null) {
      log.info("CodeSnippet and codeToken are both null in reply '{}'.", replyItem);
      return gerritCommentRange;
    }
    if (!fileDiffsProcessed.containsKey(filename)) {
      log.info(
          "Filename '{}' not found for reply '{}'. Available files = {}",
          filename,
          replyItem,
          fileDiffsProcessed.keySet());
      return gerritCommentRange;
    }
    InlineCode inlineCode = new InlineCode(fileDiffsProcessed.get(filename));
    // When codeToken is provided: first find the line range via codeSnippet, then locate the
    // token within that line for a precise character-level highlight
    if (replyItem.getCodeToken() != null && !replyItem.getCodeToken().isEmpty()) {
      log.debug("Attempting token-level inline range lookup: token={}", replyItem.getCodeToken());
      Optional<GerritCodeRange> snippetRange =
          replyItem.getCodeSnippet() != null
              ? inlineCode.findCommentRange(replyItem)
              : Optional.empty();
      log.debug(
          "Snippet range before token lookup: present={}, range={}",
          snippetRange.isPresent(),
          snippetRange.orElse(null));
      gerritCommentRange =
          snippetRange.isPresent()
              ? inlineCode.findTokenInLine(replyItem.getCodeToken(), snippetRange.get())
              : Optional.empty();
      log.debug(
          "Token-level inline range lookup result: present={}, range={}",
          gerritCommentRange.isPresent(),
          gerritCommentRange.orElse(null));
    }
    // Fall back to codeSnippet-based range if codeToken range was not found
    if (gerritCommentRange.isEmpty() && replyItem.getCodeSnippet() != null) {
      log.debug("Attempting snippet-level inline range lookup after token lookup miss");
      gerritCommentRange = inlineCode.findCommentRange(replyItem);
      log.debug(
          "Snippet-level inline range lookup result: present={}, range={}",
          gerritCommentRange.isPresent(),
          gerritCommentRange.orElse(null));
    }
    if (gerritCommentRange.isEmpty()) {
      log.info("Inline code not found for reply {}", replyItem);
    } else {
      log.debug("Inline Gerrit range resolved: {}", gerritCommentRange.get());
    }
    return gerritCommentRange;
  }
}
