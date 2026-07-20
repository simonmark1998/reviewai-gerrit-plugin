console.debug("window.Gerrit =", window.Gerrit);

Gerrit.install(plugin => {
  const LABEL = "\u{1F916} AI Review";
  const WORKING_LABEL = "Reviewing...";
  const STARTED_LABEL = "Review Started";
  const FAILED_LABEL = "Review Failed";
  const BUTTON_SELECTOR = ".ai-review-btn";

  debug("Plugin JS loaded");

  plugin.hook("commit-container").onAttached(hookEl => {
    debug("commit-container attached", {hasHookEl: !!hookEl});

    if (document.querySelector(BUTTON_SELECTOR)) {
      debug("AI review button already exists, skipping attach");
      return;
    }

    const btn = document.createElement("button");
    btn.className = "ai-review-btn";
    btn.textContent = LABEL;

    btn.style.cssText = [
      "background:transparent",
      "border:none",
      "border-radius:4px",
      "color:#90caf9",
      "font-size:14px",
      "font-family:inherit",
      "cursor:pointer",
      "padding:6px 12px",
      "font-weight:500",
      "transition:background-color 0.15s ease",
    ].join(";");

    btn.onmouseover = () => {
      if (!btn.disabled) {
        btn.style.backgroundColor = "#3c4043";
      }
    };

    btn.onmouseout = () => {
      if (!btn.disabled) {
        btn.style.backgroundColor = "transparent";
      }
    };

    btn.onclick = () => triggerReview(btn);

    const commitContainer = hookEl.closest(".commitContainer");
    if (!commitContainer) {
      debug("commitContainer not found; appending button to hook element");
      hookEl.appendChild(btn);
      return;
    }

    const replyRow = commitContainer.querySelector("#replyBtn")?.parentElement;
    if (!replyRow) {
      debug("reply row not found; appending button to hook element");
      hookEl.appendChild(btn);
      return;
    }

    replyRow.style.display = "flex";
    replyRow.style.alignItems = "center";

    const spacer = document.createElement("div");
    spacer.style.flex = "1";

    replyRow.appendChild(spacer);
    replyRow.appendChild(btn);
    replyRow.style.paddingRight = "12px";

    debug("Button attached to right side of Reply row");
  });

  async function triggerReview(btn) {
    const changeNumber = getChangeNumber();
    debug("triggerReview called", {changeNumber});

    if (!changeNumber) {
      console.error(
          "[AI Code Review] Cannot determine change number from URL:",
          window.location.pathname);
      return;
    }

    btn.disabled = true;
    btn.textContent = WORKING_LABEL;
    btn.style.color = "#9aa0a6";

    try {
      const endpoint = `/changes/${encodeURIComponent(changeNumber)}/ai-review`;
      debug("posting manual AI review request", {endpoint});
      const response = await plugin.restApi().post(endpoint, {trigger: "manual"});
      debug("manual AI review request completed", {response});

      btn.textContent = STARTED_LABEL;
      btn.style.color = "#34a853";
      resetButton(btn);
    } catch (err) {
      console.error("[AI Code Review] Failed to trigger review", err);
      btn.textContent = FAILED_LABEL;
      btn.style.color = "#ea4335";
      resetButton(btn);
    }
  }

  function getChangeNumber() {
    const match = window.location.pathname.match(/\/\+\/(\d+)/);
    debug("change number resolved from location", {
      path: window.location.pathname,
      changeNumber: match ? match[1] : null,
    });
    return match ? match[1] : null;
  }

  function resetButton(btn) {
    setTimeout(() => {
      btn.textContent = LABEL;
      btn.style.color = "#90caf9";
      btn.disabled = false;
    }, 3000);
  }

  function debug(message, data) {
    if (data === undefined) {
      console.debug(`[AI Code Review] ${message}`);
    } else {
      console.debug(`[AI Code Review] ${message}`, data);
    }
  }
});
