(ns frontend.state)

(defn initial-state []
  {:camera          {:x          0
                     :y          0
                     :zf         1
                     :show-grid? true}
   :error-message   nil
   :changelog       nil
   :environment     "development"
   :settings        {:browser-settings {:show-grid?  true
                                        :night-mode? true
                                        :current-tool :rect}}
   :current-user    nil
   :instrumentation []
   :entity-ids      #{}
   :document/id     17592186046465
   :subscribers     {}
   ;; This isn't passed to the components, it can be accessed though om/get-shared :_app-state-do-not-use
   :inputs          nil})

(def user-path [:current-user])


(def settings-path [:settings])

(def instrumentation-path [:instrumentation])

(def browser-settings-path [:settings :browser-settings])

(def account-subpage-path [:account-settings-subpage])
(def new-user-token-path (conj user-path :new-user-token))

(def flash-path [:render-context :flash])

(def error-data-path [:error-data])

(def selected-home-technology-tab-path [:selected-home-technology-tab])

(def language-testimonial-tab-path [:selected-language-testimonial-tab])

(def changelog-path [:changelog])

(def build-state-path [:build-state])

(def error-message-path [:error-message])

(def inputs-path [:inputs])

(def docs-data-path [:docs-data])
(def docs-search-path [:docs-query])
(def docs-articles-results-path [:docs-articles-results])
(def docs-articles-results-query-path [:docs-articles-results-query])

(def user-options-shown-path [:user-options-shown])

(def show-grid-path (conj browser-settings-path :show-grid?))

(def night-mode-path (conj browser-settings-path :night-mode?))

(def current-tool-path (conj browser-settings-path :current-tool))
