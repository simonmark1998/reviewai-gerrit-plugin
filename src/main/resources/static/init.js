Gerrit.install(plugin => {
  const pluginName = plugin.getPluginName();

  class ReviewAiTabHeader extends HTMLElement {
    connectedCallback() {
      this.textContent = 'AI Assistant';
    }
  }

  class ReviewAiTabContent extends HTMLElement {
    constructor() {
      super();
      this._change = null;
      this._fetchHistory = null;
      this._sendMessage = null;
      this._loadedChangeNumber = null;
      this._loadingChangeNumber = null;
      this._entries = [];
      this._error = null;
      this._submitting = false;
      this._pollHandle = null;
      this._draftMessage = '';
      this._awaitingResponse = false;
      this._pendingPrompt = null;
      this._lastPollMs = 0;
    }

    connectedCallback() {
      this._render();
      this._loadIfReady();
      this._pollHandle = window.setInterval(() => this._poll(), 5000);
    }

    disconnectedCallback() {
      if (this._pollHandle !== null) {
        window.clearInterval(this._pollHandle);
        this._pollHandle = null;
      }
    }

    set change(change) {
      this._change = change;
      this._loadedChangeNumber = null;
      this._error = null;
      this._draftMessage = '';
      this._awaitingResponse = false;
      this._pendingPrompt = null;
      this._loadIfReady();
    }

    set fetchHistory(fetchHistory) {
      this._fetchHistory = fetchHistory;
      this._loadIfReady();
    }

    set sendMessage(sendMessage) {
      this._sendMessage = sendMessage;
    }

    async _loadIfReady() {
      if (!this.isConnected || !this._fetchHistory || !this._change || !this._change._number) {
        return;
      }

      const changeNumber = this._change._number;
      if (
        this._loadingChangeNumber === changeNumber ||
        this._loadedChangeNumber === changeNumber
      ) {
        return;
      }

      this._loadingChangeNumber = changeNumber;
      this._entries = [];
      this._error = null;
      this._render();

      try {
        const result = await this._fetchHistory(this._change);
        this._entries = Array.isArray(result && result.entries) ? result.entries : [];
        if (this._awaitingResponse && this._hasAssistantReplyForPendingPrompt(this._entries)) {
          this._awaitingResponse = false;
          this._pendingPrompt = null;
        }
        this._loadedChangeNumber = changeNumber;
        this._lastPollMs = Date.now();
      } catch (error) {
        this._error = error;
      } finally {
        this._loadingChangeNumber = null;
        this._render();
      }
    }

    async _submitMessage(event) {
      event.preventDefault();
      if (!this._sendMessage || !this._change || this._submitting) {
        return;
      }
      const input = this.querySelector('.reviewai-history__composer-input');
      const text = input ? input.value.trim() : '';
      if (!text) {
        return;
      }

      this._submitting = true;
      this._error = null;
      this._render();
      try {
        await this._sendMessage(this._change, text);
        this._awaitingResponse = true;
        this._pendingPrompt = text;
        this._draftMessage = '';
        if (input) {
          input.value = '';
        }
        this._loadedChangeNumber = null;
        await this._loadIfReady();
      } catch (error) {
        this._error = error;
      } finally {
        this._submitting = false;
        this._render();
      }
    }

    async _poll() {
      if (
        !this.isConnected ||
        this._loadingChangeNumber !== null ||
        this._submitting ||
        this._draftMessage.trim()
      ) {
        return;
      }
      const now = Date.now();
      if (!this._awaitingResponse && now - this._lastPollMs < 5000) {
        return;
      }
      if (!this._fetchHistory || !this._change || !this._change._number) {
        return;
      }
      try {
        const result = await this._fetchHistory(this._change);
        this._entries = Array.isArray(result && result.entries) ? result.entries : [];
        if (this._awaitingResponse && this._hasAssistantReplyForPendingPrompt(this._entries)) {
          this._awaitingResponse = false;
          this._pendingPrompt = null;
        }
        this._loadedChangeNumber = this._change._number;
        this._lastPollMs = now;
        this._error = null;
        this._render();
      } catch (error) {
        this._error = error;
        this._render();
      }
    }

    _resizeComposerInput(input) {
      if (!input) {
        return;
      }
      input.style.height = 'auto';
      input.style.height = `${input.scrollHeight}px`;
    }

    _handleComposerKeydown(event) {
      if (event.key !== 'Enter' || event.shiftKey) {
        return;
      }
      event.preventDefault();
      event.target.form.requestSubmit();
    }

    _render() {
      this.replaceChildren();

      const container = document.createElement('div');
      container.className = 'reviewai-history';

      const style = document.createElement('style');
      style.textContent = `
        .reviewai-history {
          box-sizing: border-box;
          padding: 16px;
          color: var(--primary-text-color);
          display: grid;
          gap: 16px;
        }
        .reviewai-history__intro {
          margin: 0;
          color: var(--deemphasized-text-color);
        }
        .reviewai-history__state {
          color: var(--deemphasized-text-color);
        }
        .reviewai-history__list {
          display: grid;
          gap: 12px;
        }
        .reviewai-history__item {
          border: 1px solid var(--border-color, #ddd);
          border-radius: 8px;
          padding: 12px;
          background: var(--view-background-color, #fff);
          max-width: 70%;
        }
        .reviewai-history__item--user {
          justify-self: end;
          background: var(--table-subheader-background-color, #eef4ff);
        }
        .reviewai-history__item--assistant {
          justify-self: start;
        }
        .reviewai-history__item--pending {
          opacity: 0.75;
        }
        .reviewai-history__meta {
          display: flex;
          flex-wrap: wrap;
          gap: 8px 16px;
          margin-bottom: 8px;
          color: var(--deemphasized-text-color);
          font-size: 0.9rem;
        }
        .reviewai-history__message {
          margin: 0;
          white-space: pre-wrap;
          line-height: 1.5;
        }
        .reviewai-history__composer {
          display: grid;
          gap: 8px;
          border-top: 1px solid var(--border-color, #ddd);
          padding-top: 16px;
        }
        .reviewai-history__composer-label {
          font-weight: 600;
        }
        .reviewai-history__composer-input {
          min-height: 1.5em;
          line-height: 1.5;
          resize: none;
          overflow-y: hidden;
          padding: 10px 12px;
          border: 1px solid var(--border-color, #ccc);
          border-radius: 8px;
          font: inherit;
          color: inherit;
          background: var(--view-background-color, #fff);
        }
        .reviewai-history__composer-actions {
          display: flex;
          align-items: center;
          gap: 12px;
        }
        .reviewai-history__composer-button {
          appearance: none;
          border: 0;
          border-radius: 999px;
          padding: 8px 14px;
          cursor: pointer;
          font: inherit;
          background: var(--primary-button-background-color, #1a73e8);
          color: var(--primary-button-text-color, #fff);
        }
        .reviewai-history__composer-button[disabled] {
          opacity: 0.6;
          cursor: default;
        }
        .reviewai-history__composer-hint {
          color: var(--deemphasized-text-color);
          font-size: 0.9rem;
        }
      `;
      container.appendChild(style);

      const intro = document.createElement('p');
      intro.className = 'reviewai-history__intro';
      intro.textContent =
        'Chat with the AI by sending a patch set comment addressed to the bot. AI review comments remain in the normal Gerrit discussion.';
      container.appendChild(intro);

      if (this._loadingChangeNumber !== null) {
        const loading = document.createElement('div');
        loading.className = 'reviewai-history__state';
        loading.textContent = 'Loading AI review history...';
        container.appendChild(loading);
      } else if (this._error) {
        const error = document.createElement('div');
        error.className = 'reviewai-history__state';
        error.textContent = 'Failed to load AI review history.';
        container.appendChild(error);
      } else if (!this._entries.length) {
        const empty = document.createElement('div');
        empty.className = 'reviewai-history__state';
        empty.textContent = 'No AI chat messages were found on this change yet.';
        container.appendChild(empty);
      } else {
        const list = document.createElement('div');
        list.className = 'reviewai-history__list';
        const displayedEntries = [...this._entries];
        if (this._awaitingResponse && this._pendingPrompt) {
          const hasPromptInEntries = this._entries.some(
            entry => entry.role === 'user' && entry.message === this._pendingPrompt
          );
          if (!hasPromptInEntries) {
            displayedEntries.push({
              role: 'user',
              author: 'You',
              message: this._pendingPrompt,
              pending: true,
            });
          }
          displayedEntries.push({
            role: 'assistant',
            author: 'ReviewAI',
            message: 'Thinking...',
            pending: true,
          });
        }
        displayedEntries.forEach(entry => {
          const item = document.createElement('section');
          const role = entry.role === 'assistant' ? 'assistant' : 'user';
          const pendingClass = entry.pending ? ' reviewai-history__item--pending' : '';
          item.className = `reviewai-history__item reviewai-history__item--${role}${pendingClass}`;

          const meta = document.createElement('div');
          meta.className = 'reviewai-history__meta';

          const author = document.createElement('span');
          author.textContent = entry.author || 'Unknown';
          meta.appendChild(author);

          const location = document.createElement('span');
          location.textContent = this._formatLocation(entry);
          meta.appendChild(location);

          if (entry.updated) {
            const updated = document.createElement('span');
            updated.textContent = this._formatTimestamp(entry.updated);
            meta.appendChild(updated);
          }

          const message = document.createElement('pre');
          message.className = 'reviewai-history__message';
          message.textContent = entry.message || '';

          item.appendChild(meta);
          item.appendChild(message);
          list.appendChild(item);
        });

        container.appendChild(list);
      }

      const composer = document.createElement('form');
      composer.className = 'reviewai-history__composer';
      composer.addEventListener('submit', event => this._submitMessage(event));

      const label = document.createElement('div');
      label.className = 'reviewai-history__composer-label';
      label.textContent = 'Message AI';

      const input = document.createElement('textarea');
      input.className = 'reviewai-history__composer-input';
      input.rows = 1;
      input.placeholder = 'Ask the AI about this change...';
      input.disabled = this._submitting;
      input.value = this._draftMessage;
      input.addEventListener('input', event => {
        this._draftMessage = event.target.value;
        this._resizeComposerInput(event.target);
      });
      input.addEventListener('keydown', event => this._handleComposerKeydown(event));

      const actions = document.createElement('div');
      actions.className = 'reviewai-history__composer-actions';

      const button = document.createElement('button');
      button.className = 'reviewai-history__composer-button';
      button.type = 'submit';
      button.disabled = this._submitting;
      button.textContent = this._submitting ? 'Sending...' : 'Send';

      const hint = document.createElement('div');
      hint.className = 'reviewai-history__composer-hint';
      hint.textContent = 'Replies may take a few seconds and will also appear in Gerrit comments.';

      actions.appendChild(button);
      actions.appendChild(hint);

      composer.appendChild(label);
      composer.appendChild(input);
      composer.appendChild(actions);
      container.appendChild(composer);
      this.appendChild(container);
      this._resizeComposerInput(input);
    }

    _formatLocation(entry) {
      if (entry.filename) {
        return entry.line ? `${entry.filename}:${entry.line}` : entry.filename;
      }
      if (entry.patchSet) {
        return `Patch Set ${entry.patchSet}`;
      }
      return 'Change thread';
    }

    _formatTimestamp(value) {
      if (!value) {
        return '';
      }
      return value.replace(/\.0+$/, '');
    }

    _hasAssistantReplyForPendingPrompt(entries) {
      if (!this._pendingPrompt) {
        return false;
      }
      let promptIndex = -1;
      entries.forEach((entry, index) => {
        if (entry.role === 'user' && entry.message === this._pendingPrompt) {
          promptIndex = index;
        }
      });
      if (promptIndex === -1) {
        return false;
      }
      return entries.slice(promptIndex + 1).some(entry => entry.role === 'assistant');
    }
  }

  customElements.define('reviewai-change-view-tab-header', ReviewAiTabHeader);
  customElements.define('reviewai-change-view-tab-content', ReviewAiTabContent);

  plugin.registerDynamicCustomComponent(
    'change-view-tab-header',
    'reviewai-change-view-tab-header'
  );

  plugin
    .registerDynamicCustomComponent(
      'change-view-tab-content',
      'reviewai-change-view-tab-content'
    )
    .onAttached(view => {
      view.fetchHistory = change =>
        plugin
          .restApi()
          .get(`/changes/${change._number}/${pluginName}~ai-review-history`);
      view.sendMessage = (change, message) =>
        plugin
          .restApi()
          .post(`/changes/${change._number}/${pluginName}~ai-review-message`, {message});
    });
});
