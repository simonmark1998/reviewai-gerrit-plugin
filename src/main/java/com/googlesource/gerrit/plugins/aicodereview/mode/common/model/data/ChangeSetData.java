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

package com.googlesource.gerrit.plugins.aicodereview.mode.common.model.data;

import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritChange;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Data
@Slf4j
public class ChangeSetData {
  @NonNull private Integer gptAccountId;
  private String reviewAIDataPrompt;
  private Integer commentPropertiesSize;
  @NonNull private Integer votingMinScore;
  @NonNull private Integer votingMaxScore;

  // Command variables
  private Boolean forcedReview = false;
  private Boolean forcedReviewLastPatchSet = false;
  private Boolean replyFilterEnabled = true;
  private Boolean debugReviewMode = false;
  private Boolean hideAICodeReview = false;
  private Set<String> directives = new HashSet<>();
  private String reviewSystemMessage;
  private Map<String, GerritChange> reviewChanges = new LinkedHashMap<>();
  private Map<String, Set<String>> reviewFileChangeIds = new HashMap<>();

  public Boolean shouldHideAICodeReview() {
    return hideAICodeReview && !forcedReview;
  }

  public Boolean shouldRequestAICodeReview() {
    return reviewSystemMessage == null && !shouldHideAICodeReview();
  }

  public void resetReviewTargets() {
    reviewChanges = new LinkedHashMap<>();
    reviewFileChangeIds = new HashMap<>();
  }

  public void addReviewChange(GerritChange change) {
    if (change != null && change.getFullChangeId() != null) {
      reviewChanges.put(change.getFullChangeId(), change);
    }
  }

  public void addReviewFile(GerritChange change, String filename) {
    if (change == null || change.getFullChangeId() == null || filename == null) {
      return;
    }
    addReviewChange(change);
    reviewFileChangeIds
        .computeIfAbsent(filename, unused -> new HashSet<>())
        .add(change.getFullChangeId());
  }

  public Optional<GerritChange> getReviewChange(String changeId) {
    return Optional.ofNullable(reviewChanges.get(changeId));
  }

  public Set<String> getReviewChangeIdsForFile(String filename) {
    return reviewFileChangeIds.getOrDefault(filename, Set.of());
  }

  public boolean hasMultipleReviewChanges() {
    return reviewChanges.size() > 1;
  }
}
