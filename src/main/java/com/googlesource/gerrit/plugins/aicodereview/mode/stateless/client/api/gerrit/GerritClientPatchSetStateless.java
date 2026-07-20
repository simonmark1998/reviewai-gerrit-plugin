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

package com.googlesource.gerrit.plugins.aicodereview.mode.stateless.client.api.gerrit;

import static java.util.stream.Collectors.toList;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.FileInfo;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.aicodereview.config.Configuration;
import com.googlesource.gerrit.plugins.aicodereview.interfaces.mode.common.client.api.gerrit.GerritClientPatchSetInfo;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritClientPatchSet;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.model.data.ChangeSetData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GerritClientPatchSetStateless extends GerritClientPatchSet
    implements GerritClientPatchSetInfo {
  @VisibleForTesting
  @Inject
  public GerritClientPatchSetStateless(Configuration config, AccountCache accountCache) {
    super(config, accountCache);
  }

  public String getPatchSet(ChangeSetData changeSetData, GerritChange change) throws Exception {
    diffs.clear();
    fileDiffsProcessed.clear();
    changeSetData.resetReviewTargets();
    changeSetData.addReviewChange(change);

    int revisionBase = getChangeSetRevisionBase(changeSetData);
    log.debug("Revision base: {}", revisionBase);

    // Collect diffs for the triggering change
    List<String> files = getAffectedFiles(change, revisionBase);
    files.forEach(filename -> changeSetData.addReviewFile(change, filename));
    log.debug("Patch files: {}", files);
    List<String> currentDiffs = retrieveChangeFileDiffs(change, files, revisionBase);

    // Also collect diffs for all other open changes that share the same topic
    String topic = getChangeTopic(change);
    List<GerritChange> relatedChanges = new ArrayList<>();
    if (topic != null && !topic.isEmpty()) {
      log.debug("Topic '{}' detected, collecting related changes", topic);
      relatedChanges = getTopicRelatedChanges(change, topic);
      for (GerritChange related : relatedChanges) {
        changeSetData.addReviewChange(related);
        List<String> relatedFiles = getAffectedFiles(related, 0);
        relatedFiles.forEach(filename -> changeSetData.addReviewFile(related, filename));
        List<String> relatedDiffs = retrieveChangeFileDiffs(related, relatedFiles, 0);
        diffs.add(getNoEscapedChangeBlock(related, relatedDiffs));
        log.debug("Added diffs for related change: {}", related.getFullChangeId());
      }
    }

    if (relatedChanges.isEmpty()) {
      diffs.addAll(currentDiffs);
      diffs.add(String.format("{\"changeId\": \"%s\"}", change.getFullChangeId()));
    } else {
      diffs.add(0, getNoEscapedChangeBlock(change, currentDiffs));
    }

    String fileDiffsJson = "[" + String.join(",", diffs) + "]\n";
    log.debug("File diffs: {}", fileDiffsJson);
    return fileDiffsJson;
  }

  private List<String> retrieveChangeFileDiffs(
      GerritChange change, List<String> files, int revisionBase) throws Exception {
    int startIndex = diffs.size();
    retrieveFileDiff(change, files, revisionBase);
    List<String> changeDiffs = new ArrayList<>(diffs.subList(startIndex, diffs.size()));
    diffs.subList(startIndex, diffs.size()).clear();
    log.debug(
        "Collected file diffs for change: changeId={}, files={}, diffObjects={}",
        change.getFullChangeId(),
        files,
        changeDiffs.size());
    return changeDiffs;
  }

  private String getNoEscapedChangeBlock(GerritChange change, List<String> fileDiffs) {
    JsonObject changeBlock = new JsonObject();
    changeBlock.addProperty("changeId", change.getFullChangeId());
    changeBlock.addProperty("project", change.getProjectName());
    changeBlock.addProperty("branch", change.getBranchNameKey().shortName());
    JsonArray files = new JsonArray();
    for (String fileDiff : fileDiffs) {
      files.add(JsonParser.parseString(fileDiff));
    }
    changeBlock.add("files", files);
    return changeBlock.toString();
  }

  private String getChangeTopic(GerritChange change) {
    try (ManualRequestContext requestContext = config.openRequestContext()) {
      ChangeInfo info =
          config
              .getGerritApi()
              .changes()
              .id(
                  change.getProjectName(),
                  change.getBranchNameKey().shortName(),
                  change.getChangeKey().get())
              .get();
      return info.topic;
    } catch (Exception e) {
      log.warn(
          "Could not retrieve topic for change {}: {}", change.getFullChangeId(), e.getMessage());
      return null;
    }
  }

  private List<GerritChange> getTopicRelatedChanges(GerritChange current, String topic) {
    List<GerritChange> related = new ArrayList<>();
    try (ManualRequestContext requestContext = config.openRequestContext()) {
      List<ChangeInfo> results =
          config.getGerritApi().changes().query("topic:\"" + topic + "\" status:open").get();
      for (ChangeInfo info : results) {
        Project.NameKey project = Project.nameKey(info.project);
        BranchNameKey branch = BranchNameKey.create(project, info.branch);
        Change.Key changeKey = Change.key(info.changeId);
        GerritChange candidate = new GerritChange(project, branch, changeKey);
        if (!candidate.getFullChangeId().equals(current.getFullChangeId())) {
          related.add(candidate);
        }
      }
    } catch (Exception e) {
      log.warn(
          "Could not retrieve topic-related changes for topic '{}': {}", topic, e.getMessage());
    }
    return related;
  }

  private List<String> getAffectedFiles(GerritChange change, int revisionBase) throws Exception {
    try (ManualRequestContext requestContext = config.openRequestContext()) {
      Map<String, FileInfo> files =
          config
              .getGerritApi()
              .changes()
              .id(
                  change.getProjectName(),
                  change.getBranchNameKey().shortName(),
                  change.getChangeKey().get())
              .current()
              .files(revisionBase);
      return files.entrySet().stream()
          .filter(
              fileEntry -> {
                String filename = fileEntry.getKey();
                if (!filename.equals("/COMMIT_MSG") || config.getAIReviewCommitMessages()) {
                  if (fileEntry.getValue().size > config.getMaxReviewFileSize()) {
                    log.info(
                        "File '{}' not reviewed because its size exceeds the fixed maximum"
                            + " allowable size.",
                        filename);
                  } else {
                    return true;
                  }
                }
                return false;
              })
          .map(Map.Entry::getKey)
          .collect(toList());
    }
  }
}
