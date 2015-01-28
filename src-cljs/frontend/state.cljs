(ns frontend.state)

(def initial-browser-settings
  {:current-tool :pen
   :chat-opened false
   :chat-mobile-opened true
   :right-click-learned false
   :menu-button-learned false
   :info-button-learned false
   :newdoc-button-learned false
   :main-menu-learned false
   :chat-button-learned false
   :login-button-learned false})

(defn initial-state []
  {:camera          {:x          0
                     :y          0
                     :zf         1
                     :z-exact    1
                     :offset-x   0
                     :offset-y   0
                     :show-grid? true}
   :error-message   nil
   :changelog       nil
   :environment     "development"
   :settings        {:browser-settings initial-browser-settings}
   :keyboard-shortcuts {:select #{"v"}
                        :circle #{"l"}
                        :rect #{"m"}
                        :line #{"\\"}
                        :pen #{"n"}
                        :text #{"t"}
                        :undo #{"meta+z" "ctrl+z"}
                        :shortcuts-menu #{"shift+/"}
                        :escape-interaction #{"esc"}
                        :reset-canvas-position #{"home" "1"}
                        :return-from-origin #{"2"}}
   :current-user    nil
   :instrumentation []
   :entity-ids      #{}
   :document/id     nil
   :subscribers     {}
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

(def current-tool-path (conj browser-settings-path :current-tool))

(def chat-opened-path (conj browser-settings-path :chat-opened))

(def chat-mobile-opened-path (conj browser-settings-path :chat-mobile-toggled))

(def right-click-learned-path (conj browser-settings-path :right-click-learned))

(def menu-button-learned-path (conj browser-settings-path :menu-button-learned))

(def info-button-learned-path (conj browser-settings-path :info-button-learned))

(def newdoc-button-learned-path (conj browser-settings-path :newdoc-button-learned))

(def login-button-learned-path (conj browser-settings-path :login-button-learned))

(def your-docs-learned-path (conj browser-settings-path :your-docs-learned))

(def main-menu-learned-path (conj browser-settings-path :main-menu-learned))

(def invite-menu-learned-path (conj browser-settings-path :invite-menu-learned))

(def sharing-menu-learned-path (conj browser-settings-path :sharing-menu-learned))

(def shortcuts-menu-learned-path (conj browser-settings-path :shortcuts-menu-learned))

(def chat-button-learned-path (conj browser-settings-path :chat-button-learned))

(defn doc-settings-path [doc-id]
  (conj browser-settings-path :document-settings doc-id))

(defn last-read-chat-time-path [doc-id]
  (conj (doc-settings-path doc-id) :last-read-chat-time))

(def keyboard-shortcuts-path [:keyboard-shortcuts])

(def overlay-info-opened-path [:overlay-info-opened])

(def overlay-username-opened-path [:overlay-username-opened])

(def overlay-shortcuts-opened-path [:overlay-shortcuts-opened])

(def overlays-path [:overlays])

(def invite-email-path [:invite-email])
(def permission-grant-email-path [:permission-grant-email])

(defn invite-responses-path [doc-id]
  [:doc-settings doc-id :invite-responses])

(defn document-access-path [doc-id]
  [:doc-settings (str doc-id) :access])
