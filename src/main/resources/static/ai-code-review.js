Gerrit.install(plugin => {
  const LABEL = "AI Review";
  const WORKING_LABEL = "AI Review...";
  const CHANGE_ACTION_TYPE = "change";
  let currentChange = null;

  plugin.on("showchange", change => {
    currentChange = change;
  });

  plugin.restApi().getLoggedIn().then(loggedIn => {
    if (!loggedIn) {
      return;
    }

    const actions = plugin.changeActions();
    const actionKey = actions.add(CHANGE_ACTION_TYPE, LABEL);

    actions.setTitle(actionKey, "Trigger an AI code review for this change.");
    actions.addPrimaryActionKey(actionKey);
    actions.addTapListener(actionKey, event => {
      event.preventDefault();
      event.stopPropagation();
      triggerReview(actions, actionKey);
    });
  });

  async function triggerReview(actions, actionKey) {
    const changeNumber = getChangeNumber();
    if (!changeNumber) {
      notify("AI review could not determine the current change.");
      return;
    }

    setWorking(actions, actionKey, true);
    try {
      await plugin.restApi().post(`/changes/${encodeURIComponent(changeNumber)}/ai-review`, {
        trigger: "manual",
      });
      notify("AI review started.");
    } catch (error) {
      console.error("AI review failed", error);
      notify(`AI review failed: ${error.message || error}`);
    } finally {
      setWorking(actions, actionKey, false);
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

  function setWorking(actions, actionKey, working) {
    actions.setEnabled(actionKey, !working);
    actions.setLabel(actionKey, working ? WORKING_LABEL : LABEL);
  }

  function notify(message) {
    const event = new CustomEvent("show-alert", {
      detail: {message},
      composed: true,
      bubbles: true,
    });
    document.dispatchEvent(event);
  }
});
