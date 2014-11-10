(ns frontend.controllers.controls
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [cljs.reader :as reader]
            [clojure.set :as set]
            [frontend.async :refer [put!]]
            [frontend.components.forms :refer [release-button!]]
            [datascript :as d]
            [frontend.camera :as cameras]
            [frontend.datascript :as ds]
            [frontend.layers :as layers]
            [frontend.models.layer :as layer-model]
            [frontend.routes :as routes]
            [frontend.sente :as sente]
            [frontend.state :as state]
            [frontend.stripe :as stripe]
            [frontend.svg :as svg]
            [frontend.utils.ajax :as ajax]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer [dissoc-in]]
            [frontend.utils.state :as state-utils]
            [goog.string :as gstring]
            [goog.labs.userAgent.engine :as engine]
            goog.style)
  (:require-macros [dommy.macros :refer [sel sel1]]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]])
  (:import [goog.fx.dom.Scroll]))

;; --- Navigation Multimethod Declarations ---

(defmulti control-event
  ;; target is the DOM node at the top level for the app
  ;; message is the dispatch method (1st arg in the channel vector)
  ;; state is current state of the app
  ;; return value is the new state
  (fn [target message args state] message))

(defmulti post-control-event!
  (fn [target message args previous-state current-state] message))

;; --- Navigation Multimethod Implementations ---

(defmethod control-event :default
  [target message args state]
  (utils/mlog "Unknown controls: " message)
  state)

(defmethod post-control-event! :default
  [target message args previous-state current-state]
  (utils/mlog "No post-control for: " message))

(defmethod control-event :state-restored
  [target message path state]
  (let [str-data (.getItem js/sessionStorage "circle-state")]
    (if (seq str-data)
      (-> str-data
          reader/read-string
          (assoc :comms (:comms state)))
      state)))

(defmethod post-control-event! :state-persisted
  [target message channel-id previous-state current-state]
  (.setItem js/sessionStorage "circle-state"
            (pr-str (dissoc current-state :comms))))

(defmethod control-event :camera-nudged-up
  [target message _ state]
  (update-in state [:camera :y] inc))

(defmethod control-event :camera-nudged-down
  [target message _ state]
  (update-in state [:camera :y] dec))

(defmethod control-event :camera-nudged-left
  [target message _ state]
  (update-in state [:camera :x] inc))

(defmethod control-event :camera-nudged-right
  [target message _ state]
  (update-in state [:camera :x] dec))

(defmethod control-event :key-state-changed
  [target message [{:keys [key-name-kw depressed?]}] state]
  (assoc-in state [:keyboard key-name-kw] depressed?))

(defmethod post-control-event! :key-state-changed
  [target message [{:keys [key-name-kw depressed?]}] state]
  ;; TODO: better way to handle this
  (when (= key-name-kw :backspace?)
    (put! (get-in state [:comms :controls]) [:deleted-selected])))

(defmethod control-event :show-grid-toggled
  [target message {:keys [project-id]} state]
  (update-in state state/show-grid-path not))

(defmethod control-event :night-mode-toggled
  [target message {:keys [project-id]} state]
  (update-in state state/night-mode-path not))

(defn update-mouse [state x y]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)]
    (update-in state [:mouse] assoc :x x :y y :rx rx :ry ry)))

(defmethod control-event :drawing-started
  [target message [x y] state]
  (let [;{:keys [x y]} (get-in state [:mouse])
        [rx ry]       (cameras/screen->point (:camera state) x y)
        [snap-x snap-y] (cameras/snap-to-grid (:camera state) rx ry)
        entity-id     (-> state :entity-ids first)
        layer         (assoc (layers/make-layer entity-id (:document/id state) snap-x snap-y)
                        :layer/type (condp = (get-in state state/current-tool-path)
                                      :rect   :layer.type/rect
                                      :circle :layer.type/rect
                                      :text   :layer.type/text
                                      :line   :layer.type/line
                                      :select :layer.type/group
                                      :pen    :layer.type/path
                                      :layer.type/rect))]
    (let [r (-> state
                (assoc-in [:drawing :in-progress?] true)
                (assoc-in [:drawing :layer] layer)
                (update-in [:drawing :layer] assoc
                           :layer/current-x snap-x
                           :layer/current-y snap-y)
                (assoc-in [:mouse :down] true)
                (update-mouse x y)
                (assoc-in [:selected-eid] entity-id)
                (update-in [:entity-ids] disj entity-id))]
            r)))

(defmethod control-event :text-layer-edited
  [target message {:keys [value]} state]
  (-> state
      (assoc-in [:drawing :layer :layer/text] value)))

(defmethod control-event :mouse-moved
  [target message [x y] state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        [snap-x snap-y] (cameras/snap-to-grid (:camera state) rx ry)]
    (let [r (if (get-in state [:drawing :in-progress?])
              (let [points ((fnil conj []) (get-in state [:drawing :points]) {:x x :y x :rx rx :ry ry})]
                (-> state
                    (update-mouse x y)
                    (assoc-in [:drawing :points] points)
                    (update-in [:drawing :layer] assoc
                               :layer/current-x snap-x
                               :layer/current-y snap-y)
                    (update-in [:drawing :layer]
                               (fn [layer]
                                 (merge
                                  layer
                                  (when (= :text (get-in state state/current-tool-path))
                                    {:layer/start-x snap-x
                                     :layer/start-y snap-y})
                                  (when (= :pen (get-in state state/current-tool-path))
                                    {:layer/path (svg/points->path points)})
                                  (when (= :circle (get-in state state/current-tool-path))
                                    {:layer/rx (Math/abs (- (:layer/start-x layer)
                                                            (:layer/current-x layer)))
                                     :layer/ry (Math/abs (- (:layer/start-y layer)
                                                            (:layer/current-y layer)))}))))))
              (update-mouse state x y))]
      r)))

;; TODO: this shouldn't assume it's sending a mouse position
(defn maybe-notify-subscribers! [current-state x y]
  (when (get-in current-state [:subscribers (:client-uuid current-state) :show-mouse?])
    (sente/send-msg (:sente current-state)
                    [:frontend/mouse-position (merge
                                               {:tool (get-in current-state state/current-tool-path)
                                                :document/id (:document/id current-state)
                                                :layer (when (get-in current-state [:drawing :in-progress?])
                                                         (get-in current-state [:drawing :layer]))}
                                               (when (and x y)
                                                 {:mouse-position (cameras/screen->point (:camera current-state) x y)}))])))

(defmethod post-control-event! :text-layer-edited
  [target message _ current-state previous-state]
  (maybe-notify-subscribers! current-state nil nil))

(defmethod post-control-event! :mouse-moved
  [target message [x y] current-state previous-state]
  (maybe-notify-subscribers! current-state x y))

(defmethod post-control-event! :show-mouse-toggled
  [target message {:keys [client-uuid show-mouse?]} current-state previous-state]
  (sente/send-msg (:sente current-state)
                  [:frontend/share-mouse {:document/id (:document/id current-state)
                                          :show-mouse? show-mouse?
                                          :mouse-owner-uuid client-uuid}]))

(defn eids-in-bounding-box [db {:keys [start-x end-x start-y end-y] :as box}]
  (let [x0 (min start-x end-x)
        x1 (max start-x end-x)
        y0 (min start-y end-y)
        y1 (max start-y end-y)
        has-x0 (set (map :e (d/index-range db :layer/start-x x0 x1)))
        has-x1 (set (map :e (d/index-range db :layer/end-x x0 x1)))
        has-y0 (set (map :e (d/index-range db :layer/start-y y0 y1)))
        has-y1 (set (map :e (d/index-range db :layer/end-y y0 y1)))]
    (set/union (set/intersection has-x0 has-y0)
               (set/intersection has-x0 has-y1)
               (set/intersection has-x1 has-y0)
               (set/intersection has-x1 has-y1))))

(defn finalize-layer [state]
  (let [{:keys [x y]} (get-in state [:mouse])
        [rx ry] (cameras/screen->point (:camera state) x y)
        [snap-x snap-y] (cameras/snap-to-grid (:camera state) rx ry)
        layer-type (get-in state [:drawing :layer :layer/type])
        bounding-eids (when (= layer-type :layer.type/group)
                        (eids-in-bounding-box (-> state :db deref)
                                              {:start-x (get-in state [:drawing :layer :layer/start-x])
                                               :end-x snap-x
                                               :start-y (get-in state [:drawing :layer :layer/start-y])
                                               :end-y snap-y}))]
    (-> state
        (update-in [:drawing :layer] dissoc :layer/current-x :layer/current-y)
        (update-in [:drawing :layer] assoc :layer/end-x snap-x :layer/end-y snap-y)
        (update-in [:drawing] assoc :in-progress? false)
        (assoc-in [:drawing :points] [])
        (assoc-in [:mouse :down] false)
        (update-mouse x y)
        ;; TODO: get rid of nils (datomic doesn't like them)
        (update-in [:drawing :layer]
                   (fn [layer]
                     (-> layer
                         (dissoc
                          :layer/current-x
                          :layer/current-y)
                         (merge
                          {:layer/end-x snap-x
                           :layer/end-y snap-y}
                          (when (= :circle (get-in state state/current-tool-path))
                            {:layer/rx (Math/abs (- (:layer/start-x layer)
                                                    (:layer/end-x layer)))
                             :layer/ry (Math/abs (- (:layer/start-y layer)
                                                    (:layer/end-y layer)))})
                          (when (= layer-type :layer.type/path)
                            {:layer/path (svg/points->path (get-in state [:drawing :points]))})
                          (when (seq bounding-eids)
                            {:layer/child bounding-eids})))))
        (assoc-in [:camera :moving?] false))))

(defmethod control-event :mouse-released
  [target message [x y] state]
  (if (= :layer.type/text (get-in state [:drawing :layer :layer/type]))
    state
    (finalize-layer state)))

(defmethod control-event :mouse-depressed
  [target message [x y button type] state]
  (-> state
      (update-mouse x y)
      (assoc-in [:mouse :type] (if (= type "mousedown") :mouse :touch))))

(defmethod post-control-event! :mouse-depressed
  [target message [x y button] previous-state current-state]
  (let [cast! (fn [msg & [payload]]
                (put! (get-in current-state [:comms :controls]) [msg payload]))]
    (cond
     (= button 2) (cast! :menu-opened)
     ;; turning off Cmd+click for opening the menu
     ;; (get-in current-state [:keyboard :meta?]) (cast! :menu-opened)
     (= (get-in current-state state/current-tool-path) :pen) (cast! :drawing-started [x y])
     (= (get-in current-state state/current-tool-path) :text) (if (get-in current-state [:drawing :in-progress?])
                                                                ;; if you click while writing text, you probably wanted to place it there
                                                                (cast! :text-layer-finished [x y])
                                                                (cast! :drawing-started [x y]))
     (= (get-in current-state state/current-tool-path) :rect) (cast! :drawing-started [x y])
     (= (get-in current-state state/current-tool-path) :circle) (cast! :drawing-started [x y])
     (= (get-in current-state state/current-tool-path) :line)  (cast! :drawing-started [x y])
     (= (get-in current-state state/current-tool-path) :select)  (cast! :drawing-started [x y])
     :else                                             nil)))

(defmethod post-control-event! :mouse-released
  [target message [x y button type] previous-state current-state]
  (let [cast! #(put! (get-in current-state [:comms :controls]) %)
        db           (:db current-state)
        was-drawing? (get-in previous-state [:drawing :in-progress?])
        layer        (get-in current-state [:drawing :layer])]
    (cond
     (and (not= type "touchend")
          (not= button 2)
          (get-in current-state [:menu :open?]))
     (cast! [:menu-closed])

     (= :layer.type/text (:layer/type layer)) nil

     was-drawing? (do (d/transact! db [layer])
                      (cast! [:mouse-moved [x y]]))

     :else nil)))

(defmethod control-event :text-layer-finished
  [target message [x y] state]
  (finalize-layer state))

(defmethod post-control-event! :text-layer-finished
  [target message [x y] previous-state current-state]
  (let [cast! #(put! (get-in current-state [:comms :controls]) %)
        db           (:db current-state)
        layer        (get-in current-state [:drawing :layer])]
    (d/transact! db [layer])
    (cast! [:mouse-moved [x y]])))

(defmethod control-event :deleted-selected
  [target message _ state]
  (dissoc state :selected-eid))

(defmethod post-control-event! :deleted-selected
  [target message _ previous-state current-state]
  (when-let [selected-eid (:selected-eid previous-state)]
    (let [db (:db current-state)
          document-id (:document/id current-state)
          selected-eids (layer-model/selected-eids @db selected-eid)]
      (d/transact! db (for [eid selected-eids]
                        [:db.fn/retractEntity eid])))))

(defmethod control-event :layer-selected
  [target message layer state]
  (assoc state :selected-eid (:db/id layer)))

(defmethod control-event :menu-opened
  [target message _ state]
  (print "menu opened")
  (-> state
      (update-in [:menu] assoc
                 :open? true
                 :x (get-in state [:mouse :x])
                 :y (get-in state [:mouse :y]))
      (assoc-in [:drawing :in-progress?] false)))

(defmethod control-event :menu-closed
  [target message _ state]
  (-> state
      (assoc-in [:menu :open?] false)))

(defmethod control-event :tool-selected
  [target message [tool] state]
  (-> state
      (assoc-in state/current-tool-path tool)
      (assoc-in [:menu :open?] false)))

(defmethod control-event :text-layer-created
  [target message [text [x y]] state]
  (let [{:keys [rx ry]} (:mouse state)
        [rx ry]         (cameras/screen->point (:camera state) x y)
        entity-id       (-> state :entity-ids first)
        layer           (assoc (layers/make-layer entity-id (:document/id state) rx ry)
                          :layer/type :layer.type/text
                          :layer/font-family "Roboto"
                          :layer/font-size 24
                          :layer/stroke-width 0
                          :layer/text text)]
    (-> state
        (assoc-in [:drawing :layer] layer)
        (update-in [:entity-ids] disj entity-id))))

(defmethod post-control-event! :text-layer-created
  [target message [x y] previous-state current-state]
  (let [db    (:db current-state)
        layer (get-in current-state [:drawing :layer])]
    (d/transact! db [layer])))

(defmethod control-event :text-layer-re-edited
  [target message layer state]
  (-> state
      (assoc-in [:drawing :layer] (assoc layer
                                    :layer/current-x (:layer/start-x layer)
                                    :layer/current-y (:layer/start-y layer)))
      (assoc-in [:drawing :in-progress?] true)
      (assoc-in state/current-tool-path :text)))

(defmethod post-control-event! :text-layer-re-edited
  [target message layer previous-state current-state]
  (maybe-notify-subscribers! current-state nil nil))

(defmethod control-event :db-updated
  [target message _ state]
  (assoc state :random-number (Math/random)))

(defmethod control-event :chat-body-changed
  [target message {:keys [value]} state]
  (let [entity-id (or (get-in state [:chat :entity-id])
                      (-> state :entity-ids first))]
    (-> state
        (assoc-in [:chat :body] value)
        (assoc-in [:chat :entity-id] entity-id)
        (update-in [:entity-ids] disj entity-id))))

(defmethod control-event :chat-submitted
  [target message _ state]
  (-> state
      (assoc-in [:chat :body] nil)
      (assoc-in [:chat :entity-id] nil)))

(defmethod post-control-event! :chat-submitted
  [target message _ previous-state current-state]
  (let [db (:db current-state)
        client-uuid (:client-uuid previous-state)
        color (get-in previous-state [:subscribers client-uuid :color])]
    (d/transact db [{:chat/body (get-in previous-state [:chat :body])
                     :chat/color color
                     :db/id (get-in previous-state [:chat :entity-id])
                     :session/uuid client-uuid
                     :document/id (:document/id previous-state)
                     :client/timestamp (js/Date.)
                     ;; server will overwrite this
                     :server/timestamp (js/Date.)}])))

(defmethod control-event :aside-menu-toggled
  [target message _ state]
  (let [aside-open? (not (get-in state state/aside-menu-opened-path))
        db @(:db state)
        last-chat-time (->> (d/q '[:find ?t
                                   :where [?t :chat/body]]
                                 db)
                            (map #(:server/timestamp (d/entity db (first %))))
                            sort
                            last)]
    (-> state
        (assoc-in state/aside-menu-opened-path aside-open?)
        (assoc-in [:drawing :in-progress?] false)
        (assoc-in [:camera :offset-x] (if aside-open?
                                        (get-in state state/aside-width-path)
                                        0))
        (assoc-in (state/last-read-chat-time-path (:document/id state)) (or last-chat-time
                                                                            (js/Date. 0))))))

(defmethod control-event :overlay-info-toggled
  [target message _ state]
  (-> state
      (update-in state/overlay-info-opened-path not)))

(defmethod post-control-event! :application-shutdown
  [target message _ previous-state current-state]
  (sente/send-msg (:sente current-state) [:frontend/close-connection]))

(defmethod control-event :chat-mobile-toggled
  [target message _ state]
  (-> state
      (update-in state/chat-mobile-opened-path not)))

(defmethod control-event :chat-link-clicked
  [target message _ state]
   (-> state
     (assoc-in state/overlay-info-opened-path false)
     (assoc-in state/aside-menu-opened-path true)
     (assoc-in [:camera :offset-x] (get-in state state/aside-width-path))
     (assoc-in state/chat-mobile-opened-path true)
     (assoc-in [:chat :body] "@prcrsr ")))

(defmethod post-control-event! :chat-link-clicked
  [target message _ previous-state current-state]
  (.focus (sel1 target "#chat-box")))
