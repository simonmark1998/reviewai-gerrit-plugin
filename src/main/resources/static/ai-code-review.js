Gerrit.install(plugin => {
  const LABEL = "AI Review";
  const WORKING_LABEL = "AI Review...";
  const CHANGE_ACTION_TYPE = "change";
  const FALLBACK_BUTTON_SELECTOR = "[data-ai-code-review-button]";

  let currentChange = null;
  let actionKey = null;
  let actionRegistered = false;
  let fallbackRegistered = false;

  plugin.on("showchange", change => {
    currentChange = change;
    registerChangeAction();
  });

  function registerChangeAction() {
    if (actionRegistered) {
      return;
    }

    const actions =
      typeof plugin.changeActions === "function" ? plugin.changeActions() : null;
    if (!actions || typeof actions.add !== "function") {
      registerFallbackButton();
      return;
    }

    try {
      actionKey = actions.add(CHANGE_ACTION_TYPE, LABEL);
      if (!actionKey) {
        throw new Error("changeActions.add did not return an action key");
      }
      actionRegistered = true;

      safeCall(actions, "setTitle", actionKey, "Trigger an AI code review for this change.");
      safeCall(actions, "addTapListener", actionKey, event => {
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
      registerFallbackButton();
    }
  }

  function registerFallbackButton() {
    if (fallbackRegistered || typeof plugin.hook !== "function") {
      return;
    }
    fallbackRegistered = true;

    plugin.hook("change-view-integration").onAttached(hookEl => {
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
        triggerReview(working => setFallbackWorking(button, working));
      });

      hookEl.appendChild(button);
    });
  }

  async function triggerReview(setWorking) {
    const changeNumber = getChangeNumber();
    if (!changeNumber) {
      notify("AI review could not determine the current change.");
      return;
    }

    setWorking(true);
    try {
      await plugin.restApi().post(`/changes/${encodeURIComponent(changeNumber)}/ai-review`, {
        trigger: "manual",
      });
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
      return currentChange._number;
    }

    const locationText = `${window.location.pathname}${window.location.hash}`;
    const match = locationText.match(/\/c\/.*\/\+\/(\d+)/);
    return match ? match[1] : null;
  }

  function setActionWorking(actions, key, working) {
    safeCall(actions, "setEnabled", key, !working);
    safeCall(actions, "setLabel", key, working ? WORKING_LABEL : LABEL);
  }

  function setFallbackWorking(button, working) {
    button.disabled = working;
    button.textContent = working ? WORKING_LABEL : LABEL;
    button.style.cursor = working ? "wait" : "pointer";
  }

  function safeCall(object, method, ...args) {
    if (object && typeof object[method] === "function") {
      object[method](...args);
    }
  }

  function notify(message) {
    document.dispatchEvent(
        new CustomEvent("show-alert", {
          detail: {message},
          composed: true,
          bubbles: true,
        }));
  }
});
