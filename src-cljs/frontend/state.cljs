(ns frontend.state)

;; If you want a browser setting to be persisted to the backend,
;; be sure to add it to the schema (pc.datomic.schema/shema) and
;; add the translation to frontend.browser-settings/db-setting->app-state-setting
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

(def subscriber-bot
  {:color "#00b233"
   :cust-name "prcrsr"
   :cust/name "prcrsr"
   :show-mouse? true
   :hide-in-list? true
   :cust/uuid "prcrsr-subscriber-bot"
   :cust/color-name :color.name/green
   :client-id "prcrsr-subscriber-bot"})

(defn initial-state []
  {:camera          {:x          0
                     :y          0
                     :zf         1
                     :z-exact    1
                     :offset-x   0
                     :offset-y   0
                     :show-grid? true}
   :error-message   nil
   :settings        {:browser-settings initial-browser-settings}
   :keyboard-shortcuts {:select #{#{"v"}}
                        :circle #{#{"l"}}
                        :rect #{#{"m"}}
                        :line #{#{"\\"}}
                        :pen #{#{"n"}}
                        :text #{#{"t"}}
                        :undo #{#{"meta" "z"} #{"ctrl" "z"}}
                        :shortcuts-menu #{#{"shift" "/"}}
                        :escape-interaction #{#{"esc"}}
                        :reset-canvas-position #{#{"home"} #{"1"}}
                        :return-from-origin #{#{"2"}}}
   :drawing {:layers []}
   :current-user    nil
   :entity-ids      #{}
   :document/id     nil
   ;; subscribers is split into many parts for perf
   :subscribers     {:mice {}
                     :layers {}
                     :info {}
                     ;; used to keep track of which entities are being edited
                     ;; so that we can lock them
                     ;; We have to be a little silly here and below so that Om will let
                     ;; us have multiple ref cursors in the same component
                     :entity-ids {:entity-ids #{}}}
   :selected-eids   {:selected-eids #{}}
   :editing-eids    {:editing-eids #{}}
   ;; Info about contributors to the doc
   ;; Combines sessions with custs, which might turn out to be a bad idea
   :cust-data {:uuid->cust {(:cust/uuid subscriber-bot) (select-keys subscriber-bot [:cust/uuid :cust/name :cust/color-name])}}
   :show-landing? false
   :overlays []
   :frontend-id-state nil
   :mouse {}
   :page-count 0})

(defn reset-state [state]
  (-> state
    (merge (select-keys (initial-state)
                        [:camera :error-message
                         :drawing :document/id
                         :subscribers :selected-eids
                         :editing-eids :mouse
                         :show-landing? :overlays
                         :frontend-id-state]))))

(def user-path [:current-user])

(def settings-path [:settings])

(def browser-settings-path [:settings :browser-settings])

(def error-message-path [:error-message])

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
