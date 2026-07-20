Gerrit.install(plugin => {
  const LABEL = "AI Review";
  const WORKING_LABEL = "AI Review...";
  const CHANGE_ACTION_TYPE = "change";
  const FALLBACK_BUTTON_SELECTOR = "[data-ai-code-review-button]";

  let currentChange = null;
  let actionKey = null;
  let actionRegistered = false;
  let fallbackRegistered = false;

  debug("plugin loaded");

  plugin.on("showchange", change => {
    debug("showchange event", {
      changeNumber: change && change._number,
      project: change && change.project,
      branch: change && change.branch,
    });
    currentChange = change;
    registerChangeAction();
  });

  function registerChangeAction() {
    debug("registerChangeAction called", {actionRegistered});
    if (actionRegistered) {
      return;
    }

    const actions =
      typeof plugin.changeActions === "function" ? plugin.changeActions() : null;
    debug("changeActions resolved", {
      hasChangeActionsFunction: typeof plugin.changeActions === "function",
      hasActionsObject: !!actions,
      hasAddFunction: !!actions && typeof actions.add === "function",
    });
    if (!actions || typeof actions.add !== "function") {
      debug("changeActions unavailable; registering fallback button");
      registerFallbackButton();
      return;
    }

    try {
      actionKey = actions.add(CHANGE_ACTION_TYPE, LABEL);
      debug("change action add returned", {actionKey});
      if (!actionKey) {
        throw new Error("changeActions.add did not return an action key");
      }
      actionRegistered = true;

      safeCall(actions, "setTitle", actionKey, "Trigger an AI code review for this change.");
      safeCall(actions, "addTapListener", actionKey, event => {
        debug("change action tapped", {actionKey});
        if (event) {
          if (typeof event.preventDefault === "function") {
            event.preventDefault();
          }
          if (typeof event.stopPropagation === "function") {
            event.stopPropagation();
          }
        }
        triggerReview(working => setActionWorking(actions, actionKey, working));
      });
    } catch (error) {
      console.warn("AI review change action could not be registered", error);
      debug("falling back after change action registration error", {
        error: error && (error.message || String(error)),
      });
      registerFallbackButton();
    }
  }

  function registerFallbackButton() {
    debug("registerFallbackButton called", {
      fallbackRegistered,
      hasHookFunction: typeof plugin.hook === "function",
    });
    if (fallbackRegistered || typeof plugin.hook !== "function") {
      return;
    }
    fallbackRegistered = true;

    plugin.hook("change-view-integration").onAttached(hookEl => {
      debug("change-view-integration attached", {
        hasHookEl: !!hookEl,
        hasExistingButton: !!hookEl && !!hookEl.querySelector(FALLBACK_BUTTON_SELECTOR),
      });
      if (!hookEl || hookEl.querySelector(FALLBACK_BUTTON_SELECTOR)) {
        return;
      }

      const button = document.createElement("button");
      button.dataset.aiCodeReviewButton = "true";
      button.type = "button";
      button.textContent = LABEL;
      button.title = "Trigger an AI code review for this change.";
      button.style.cssText = [
        "display:inline-block",
        "margin:8px 0",
        "padding:6px 16px",
        "background:#1976d2",
        "color:#fff",
        "border:0",
        "border-radius:4px",
        "font:inherit",
        "cursor:pointer",
      ].join(";");
      button.addEventListener("click", () => {
        debug("fallback button clicked");
        triggerReview(working => setFallbackWorking(button, working));
      });

      hookEl.appendChild(button);
      debug("fallback button appended");
    });
  }

  async function triggerReview(setWorking) {
    const changeNumber = getChangeNumber();
    debug("triggerReview called", {changeNumber});
    if (!changeNumber) {
      notify("AI review could not determine the current change.");
      return;
    }

    setWorking(true);
    try {
      const endpoint = `/changes/${encodeURIComponent(changeNumber)}/ai-review`;
      debug("posting manual AI review request", {endpoint});
      const response = await plugin.restApi().post(endpoint, {
        trigger: "manual",
      });
      debug("manual AI review request completed", {response});
      notify("AI review started.");
    } catch (error) {
      console.error("AI review failed", error);
      notify(`AI review failed: ${error.message || error}`);
    } finally {
      setWorking(false);
    }
  }

  function getChangeNumber() {
    if (currentChange && currentChange._number) {
      debug("change number resolved from showchange payload", {
        changeNumber: currentChange._number,
      });
      return currentChange._number;
    }

    const locationText = `${window.location.pathname}${window.location.hash}`;
    const match = locationText.match(/\/c\/.*\/\+\/(\d+)/);
    debug("change number resolved from location fallback", {
      locationText,
      changeNumber: match ? match[1] : null,
    });
    return match ? match[1] : null;
  }

  function setActionWorking(actions, key, working) {
    debug("setActionWorking", {key, working});
    safeCall(actions, "setEnabled", key, !working);
    safeCall(actions, "setLabel", key, working ? WORKING_LABEL : LABEL);
  }

  function setFallbackWorking(button, working) {
    debug("setFallbackWorking", {working});
    button.disabled = working;
    button.textContent = working ? WORKING_LABEL : LABEL;
    button.style.cursor = working ? "wait" : "pointer";
  }

  function safeCall(object, method, ...args) {
    if (object && typeof object[method] === "function") {
      debug("calling Gerrit plugin API method", {method});
      object[method](...args);
    } else {
      debug("skipping unavailable Gerrit plugin API method", {method});
    }
  }

  function notify(message) {
    debug("showing notification", {message});
    document.dispatchEvent(
        new CustomEvent("show-alert", {
          detail: {message},
          composed: true,
          bubbles: true,
        }));
  }

  function debug(message, data) {
    if (data === undefined) {
      console.debug(`[ai-code-review] ${message}`);
    } else {
      console.debug(`[ai-code-review] ${message}`, data);
    }
  }
});
