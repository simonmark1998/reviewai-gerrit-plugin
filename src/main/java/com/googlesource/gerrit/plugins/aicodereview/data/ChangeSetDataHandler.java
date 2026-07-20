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

package com.googlesource.gerrit.plugins.aicodereview.data;

import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.length;
import static com.googlesource.gerrit.plugins.aicodereview.utils.DebugLogUtils.summarize;

import com.googlesource.gerrit.plugins.aicodereview.config.Configuration;
import com.googlesource.gerrit.plugins.aicodereview.localization.Localizer;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.prompt.AIChatDataPrompt;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.api.gerrit.GerritPermittedVotingRange;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.data.GerritClientData;
import java.util.HashSet;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChangeSetDataHandler {
  public static void update(
      Configuration config,
      GerritChange change,
      GerritClient gerritClient,
      ChangeSetData changeSetData,
      Localizer localizer) {
    GerritClientData gerritClientData = gerritClient.getClientData(change);
    log.debug(
        "Updating ChangeSetData: change={}, commentProperties={}, aiMode={}, aiType={}, votingEnabled={}",
        change.getFullChangeId(),
        gerritClientData.getCommentProperties() == null
            ? 0
            : gerritClientData.getCommentProperties().size(),
        config.getAIMode(),
        config.getAIType(),
        config.isVotingEnabled());
    AIChatDataPrompt AIChatDataPrompt =
        new AIChatDataPrompt(config, changeSetData, change, gerritClientData, localizer);

    changeSetData.setCommentPropertiesSize(gerritClientData.getCommentProperties().size());
    changeSetData.setDirectives(new HashSet<>());
    changeSetData.setReviewSystemMessage(null);
    changeSetData.setReviewAIDataPrompt(AIChatDataPrompt.buildPrompt());
    log.debug(
        "ChangeSetData prompt built: change={}, commentPropertiesSize={}, promptChars={}, prompt={}",
        change.getFullChangeId(),
        changeSetData.getCommentPropertiesSize(),
        length(changeSetData.getReviewAIDataPrompt()),
        summarize(changeSetData.getReviewAIDataPrompt()));
    if (config.isVotingEnabled() && !change.getIsCommentEvent()) {
      GerritPermittedVotingRange permittedVotingRange =
          gerritClient.getPermittedVotingRange(change);
      log.debug(
          "Permitted Gerrit voting range retrieved: change={}, range={}",
          change.getFullChangeId(),
          permittedVotingRange);
      if (permittedVotingRange != null) {
        if (permittedVotingRange.getMin() > config.getVotingMinScore()) {
          log.debug("Minimum AIChat voting score set to {}", permittedVotingRange.getMin());
          changeSetData.setVotingMinScore(permittedVotingRange.getMin());
        }
        if (permittedVotingRange.getMax() < config.getVotingMaxScore()) {
          log.debug("Maximum AIChat voting score set to {}", permittedVotingRange.getMax());
          changeSetData.setVotingMaxScore(permittedVotingRange.getMax());
        }
      }
    } else {
      log.debug(
          "Skipping permitted voting range lookup: votingEnabled={}, isCommentEvent={}",
          config.isVotingEnabled(),
          change.getIsCommentEvent());
    }
  }
}
