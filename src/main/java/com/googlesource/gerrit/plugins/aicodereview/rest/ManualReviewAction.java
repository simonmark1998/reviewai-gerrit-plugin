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

package com.googlesource.gerrit.plugins.aicodereview.rest;

import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.change.ChangeResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.aicodereview.config.ConfigCreator;
import com.googlesource.gerrit.plugins.aicodereview.config.Configuration;
import com.googlesource.gerrit.plugins.aicodereview.listener.EventHandlerExecutor;
import com.googlesource.gerrit.plugins.aicodereview.mode.common.client.api.gerrit.GerritChange;
import lombok.extern.slf4j.Slf4j;

/**
 * REST endpoint that triggers a manual AI code review for a change.
 *
 * <p>Endpoint: POST /changes/{change-id}/ai-review
 *
 * <p>The UI button is provided by the JavaScript plugin (static/ai-code-review.js).
 */
@Slf4j
@Singleton
public class ManualReviewAction
    implements RestModifyView<ChangeResource, ManualReviewAction.Input> {

  private final ConfigCreator configCreator;
  private final EventHandlerExecutor eventHandlerExecutor;

  @Inject
  ManualReviewAction(ConfigCreator configCreator, EventHandlerExecutor eventHandlerExecutor) {
    this.configCreator = configCreator;
    this.eventHandlerExecutor = eventHandlerExecutor;
  }

  @Override
  public Response<String> apply(ChangeResource rsrc, Input input) throws Exception {
    Change change = rsrc.getChange();
    log.info(
        "Manual AI review triggered for change: id={}, key={}, project={}, branch={}, trigger={}",
        change.getId(),
        change.getKey(),
        change.getProject(),
        change.getDest(),
        input == null ? null : input.getTrigger());

    Configuration config = configCreator.createConfig(change.getProject(), change.getKey());
    log.debug(
        "Manual AI review config resolved: changeKey={}, aiType={}, aiMode={}, aiModel={}, aiDomain={}, chatEndpoint={}, votingEnabled={}, reviewPatchSet={}, streamOutput={}",
        change.getKey(),
        config.getAIType(),
        config.getAIMode(),
        config.getAIModel(),
        config.getAIDomain(),
        config.getChatEndpoint(),
        config.isVotingEnabled(),
        config.getAIReviewPatchSet(),
        config.getAIStreamOutput());
    GerritChange gerritChange =
        new GerritChange(change.getProject(), change.getDest(), change.getKey());
    log.debug("Manual AI review GerritChange created: {}", gerritChange);

    eventHandlerExecutor.executeManualReview(config, gerritChange);
    log.debug("Manual AI review execution submitted: changeKey={}", change.getKey());

    return Response.ok("AI review started");
  }

  public static class Input {
    private String trigger = "";

    public Input() {}

    public String getTrigger() {
      return trigger;
    }

    public void setTrigger(String trigger) {
      this.trigger = trigger;
    }
  }
}
