(function(global) {
  const reviewAi = global.ReviewAi;

  function defineCustomElement(name, componentClass) {
    if (!customElements.get(name)) {
      customElements.define(name, componentClass);
    }
  }

  reviewAi.defineCustomElements = function() {
    defineCustomElement('reviewai-change-view-tab-header', reviewAi.ReviewAiTabHeader);
    defineCustomElement('reviewai-change-view-tab-content', reviewAi.ReviewAiTabContent);
  };
})(window);
