(ns frontend.controllers.controls
  (:require [cemerick.url :as url]
            [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [cljs.reader :as reader]
            [clojure.set :as set]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.components.forms :refer [release-button!]]
            [datascript :as d]
            [frontend.camera :as cameras]
            [frontend.datascript :as ds]
            [frontend.favicon :as favicon]
            [frontend.layers :as layers]
            [frontend.models.chat :as chat-model]
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
  ;; browser-state is a map that includes DOM node at the top level for the app
  ;; message is the dispatch method (1st arg in the channel vector)
  ;; state is current state of the app
  ;; return value is the new state
  (fn [browser-state message args state] message))

(defmulti post-control-event!
  (fn [browser-state message args previous-state current-state] message))

;; --- Navigation Multimethod Implementations ---

(defmethod control-event :default
  [browser-state message args state]
  (utils/mlog "Unknown controls: " message)
  state)

(defmethod post-control-event! :default
  [browser-state message args previous-state current-state]
  (utils/mlog "No post-control for: " message))

(defmethod control-event :state-restored
  [browser-state message path state]
  (let [str-data (.getItem js/sessionStorage "circle-state")]
    (if (seq str-data)
      (-> str-data
          reader/read-string
          (assoc :comms (:comms state)))
      state)))

(defmethod post-control-event! :state-persisted
  [browser-state message channel-id previous-state current-state]
  (.setItem js/sessionStorage "circle-state"
            (pr-str (dissoc current-state :comms))))

(defmethod control-event :camera-nudged-up
  [browser-state message _ state]
  (update-in state [:camera :y] inc))

(defmethod control-event :camera-nudged-down
  [browser-state message _ state]
  (update-in state [:camera :y] dec))

(defmethod control-event :camera-nudged-left
  [browser-state message _ state]
  (update-in state [:camera :x] inc))

(defmethod control-event :camera-nudged-right
  [browser-state message _ state]
  (update-in state [:camera :x] dec))

(defmulti handle-keyboard-shortcut (fn [shortcut-name state] shortcut-name))

(defmethod handle-keyboard-shortcut :default
  [shortcut-name state]
  (assoc-in state state/current-tool-path shortcut-name))

(defn vec-index [v elem]
  (let [result (atom nil)
        i (atom (dec (count v)))]
    (while (not @result)
      (if (or (= -1 @i)
              (= elem (nth v @i)))
        (reset! result @i)
        (swap! i dec)))
    @result))

(defn handle-undo [state]
  (let [{:keys [transactions last-undo]} @(:undo-state state)
        transaction-to-undo (if last-undo
                              ;; TODO: something special for no transactions to undo
                              (let [next-undo-index (dec (utils/inspect (vec-index transactions last-undo)))]
                                (when-not (neg? next-undo-index)
                                  (nth transactions next-undo-index)))
                              (last transactions))]
    (when transaction-to-undo
      (ds/reverse-transaction transaction-to-undo (:db state))
      (swap! (:undo-state state) assoc :last-undo transaction-to-undo))
    state))

;; TODO: have some way to handle pre-and-post
(defmethod handle-keyboard-shortcut :undo
  [shortcut-name state]
  (handle-undo state))

;; TODO: find a better way to handle multiple keyboard shortcuts for the same thing
(defmethod handle-keyboard-shortcut :undo-windows
  [shortcut-name state]
  (handle-undo state))

(defmethod handle-keyboard-shortcut :shortcuts-menu
  [shortcut-name state]
  (-> state
      (update-in state/overlay-shortcuts-opened-path not)))

(defmethod handle-keyboard-shortcut :escape-interaction
  [shortcut-name state]
  (-> state
      (assoc-in state/overlay-info-opened-path false)
      (assoc-in state/overlay-shortcuts-opened-path false)))

(defmethod control-event :key-state-changed
  [browser-state message [{:keys [key key-name-kw depressed?]}] state]
  (let [shortcuts (get-in state state/keyboard-shortcuts-path)]
    (-> state
        (assoc-in [:keyboard key-name-kw] depressed?)
        (cond->> (and depressed? (contains? (set (vals shortcuts)) key))
                 (handle-keyboard-shortcut (get (set/map-invert shortcuts) key))))))

(defmethod post-control-event! :key-state-changed
  [browser-state message [{:keys [key-name-kw depressed?]}] state]
  ;; TODO: better way to handle this
  (when (or (= key-name-kw :backspace?)
            (= key-name-kw :del?))
    (put! (get-in state [:comms :controls]) [:deleted-selected])))

(defmethod control-event :show-grid-toggled
  [browser-state message {:keys [project-id]} state]
  (update-in state state/show-grid-path not))

(defmethod control-event :night-mode-toggled
  [browser-state message {:keys [project-id]} state]
  (update-in state state/night-mode-path not))

(defn update-mouse [state x y]
  (if (and x y)
    (let [[rx ry] (cameras/screen->point (:camera state) x y)]
      (update-in state [:mouse] assoc :x x :y y :rx rx :ry ry))
    (do
      (utils/mlog "Called update-mouse without x and y coordinates")
      state)))

(defn parse-points-from-path [path]
  (let [points (map js/parseInt (str/split (subs path 1) #" "))]
    (map (fn [[rx ry]] {:rx rx :ry ry}) (partition 2 points))))

(defmethod control-event :drawing-started
  [browser-state message [x y] state]
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

(defmethod control-event :layer-duplicated
  [browser-state message {:keys [layer x y]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        entity-id (-> state :entity-ids first)]
    (-> state
        (assoc :selected-eid entity-id)
        (assoc-in [:drawing :original-layer] layer)
        (assoc-in [:drawing :layer] (assoc layer
                                      :db/id entity-id
                                      :layer/start-x (:layer/start-x layer)
                                      :layer/end-x (:layer/end-x layer)
                                      :layer/current-x (:layer/end-x layer)
                                      :layer/current-y (:layer/end-y layer)))
        (assoc-in [:drawing :moving?] true)
        ;; TODO: handle points
        (assoc-in [:drawing :points] (when (:layer/path layer) (parse-points-from-path (:layer/path layer))))
        (assoc-in [:drawing :starting-mouse-position] [rx ry])
        (update-in [:entity-ids] disj entity-id))))

(defmethod control-event :text-layer-edited
  [browser-state message {:keys [value]} state]
  (-> state
      (assoc-in [:drawing :layer :layer/text] value)))

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

(defn draw-in-progress-drawing [state x y]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        [snap-x snap-y] (cameras/snap-to-grid (:camera state) rx ry)
        points ((fnil conj []) (get-in state [:drawing :points]) {:x x :y x :rx rx :ry ry})
        bounding-eids (when (= (get-in state [:drawing :layer :layer/type]) :layer.type/group)
                        (eids-in-bounding-box (-> state :db deref)
                                              {:start-x (get-in state [:drawing :layer :layer/start-x])
                                               :end-x snap-x
                                               :start-y (get-in state [:drawing :layer :layer/start-y])
                                               :end-y snap-y}))]
    (-> state
        (assoc-in [:drawing :points] points)
        (update-in [:drawing :layer] assoc
                   :layer/current-x snap-x
                   :layer/current-y snap-y)
        (update-in [:drawing :layer]
                   (fn [layer]
                     (merge
                      layer
                      (when (= :pen (get-in state state/current-tool-path))
                        {:layer/path (svg/points->path points)})
                      (when (= :circle (get-in state state/current-tool-path))
                        {:layer/rx (Math/abs (- (:layer/start-x layer)
                                                (:layer/current-x layer)))
                         :layer/ry (Math/abs (- (:layer/start-y layer)
                                                (:layer/current-y layer)))})
                      (when (seq bounding-eids)
                        {:layer/child bounding-eids})))))))

(defn move-points [points move-x move-y]
  (map (fn [{:keys [rx ry]}]
         {:rx (+ rx move-x)
          :ry (+ ry move-y)})
       points))

(defn move-drawing [state x y]
  (let [[start-x start-y] (get-in state [:drawing :starting-mouse-position])
        [rx ry] (cameras/screen->point (:camera state) x y)
        [move-x move-y] [(- rx start-x) (- ry start-y)]
        [snap-move-x snap-move-y] (cameras/snap-to-grid (:camera state) move-x move-y)
        points (get-in state [:drawing :points])
        original (get-in state [:drawing :original-layer])]
    (-> state
        (update-in [:drawing :layer]
                   (fn [layer]
                     (-> (assoc layer
                           :layer/start-x (+ snap-move-x (:layer/start-x original))
                           :layer/end-x (+ snap-move-x (:layer/end-x original))
                           :layer/current-x (+ snap-move-x (:layer/end-x original))
                           :layer/start-y (+ snap-move-y (:layer/start-y original))
                           :layer/end-y (+ snap-move-y (:layer/end-y original))
                           :layer/current-y (+ snap-move-y (:layer/end-y original)))
                         (cond-> (= :layer.type/path (:layer/type layer))
                                 (assoc :layer/path (svg/points->path (move-points points move-x move-y))))))))))

(defmethod control-event :mouse-moved
  [browser-state message [x y] state]
  (-> state

      (update-mouse x y)
      (cond-> (get-in state [:drawing :in-progress?])
              (draw-in-progress-drawing x y)

              (get-in state [:drawing :moving?])
              (move-drawing x y))))

;; TODO: this shouldn't assume it's sending a mouse position
(defn maybe-notify-subscribers! [current-state x y]
  (when (get-in current-state [:subscribers (:client-uuid current-state) :show-mouse?])
    (sente/send-msg (:sente current-state)
                    [:frontend/mouse-position (merge
                                               {:tool (get-in current-state state/current-tool-path)
                                                :document/id (:document/id current-state)
                                                :layer (when (or (get-in current-state [:drawing :in-progress?])
                                                                 (get-in current-state [:drawing :moving?]))
                                                         (get-in current-state [:drawing :layer]))}
                                               (when (and x y)
                                                 {:mouse-position (cameras/screen->point (:camera current-state) x y)}))])))

(defmethod post-control-event! :text-layer-edited
  [browser-state message _ current-state previous-state]
  (maybe-notify-subscribers! current-state nil nil))

(defmethod post-control-event! :mouse-moved
  [browser-state message [x y] current-state previous-state]
  (maybe-notify-subscribers! current-state x y))

(defmethod post-control-event! :show-mouse-toggled
  [browser-state message {:keys [client-uuid show-mouse?]} current-state previous-state]
  (sente/send-msg (:sente current-state)
                  [:frontend/share-mouse {:document/id (:document/id current-state)
                                          :show-mouse? show-mouse?
                                          :mouse-owner-uuid client-uuid}]))


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

(defn drop-layer
  "Finalizes layer translation"
  [state]
  (-> state
      (update-in [:drawing :layer] dissoc :layer/current-x :layer/current-y)
      (assoc-in [:drawing :moving?] false)
      (assoc-in [:mouse :down] false)
      (assoc-in [:drawing :points] [])))

(defmethod control-event :mouse-released
  [browser-state message [x y] state]
  (if (and (not (get-in state [:drawing :moving?]))
           (= :layer.type/text (get-in state [:drawing :layer :layer/type])))
    state
    (-> state
        (update-mouse x y)

        (cond-> (get-in state [:drawing :in-progress?])
                (finalize-layer)

                (get-in state [:drawing :moving?])
                (drop-layer)))))

(defmethod control-event :mouse-depressed
  [browser-state message [x y {:keys [button type]}] state]
  (-> state
      (update-mouse x y)
      (assoc-in [:mouse :type] (if (= type "mousedown") :mouse :touch))))

(defmethod post-control-event! :mouse-depressed
  [browser-state message [x y {:keys [button ctrl?]}] previous-state current-state]
  (let [cast! (fn [msg & [payload]]
                (put! (get-in current-state [:comms :controls]) [msg payload]))]
    (cond
     (= button 2) (cast! :menu-opened)
     (and (= button 0) ctrl?) (cast! :menu-opened)
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

(defn detectable-movement?
  "Checks to make sure we moved the layer from its starting position"
  [original-layer layer]
  (or (not= (:layer/start-x layer)
            (:layer/start-x original-layer))
      (not= (:layer/start-y layer)
            (:layer/start-y original-layer))))

(defmethod post-control-event! :mouse-released
  [browser-state message [x y {:keys [button type ctrl?]}] previous-state current-state]
  (let [cast! #(put! (get-in current-state [:comms :controls]) %)
        db           (:db current-state)
        was-drawing? (or (get-in previous-state [:drawing :in-progress?])
                         (get-in previous-state [:drawing :moving?]))
        original-layer (get-in previous-state [:drawing :original-layer])
        layer        (get-in current-state [:drawing :layer])]
    (cond
     (and (not= type "touchend")
          (not= button 2)
          (not (and (= button 0) ctrl?))
          (get-in current-state [:menu :open?]))
     (cast! [:menu-closed])

     (and (not (get-in previous-state [:drawing :moving?]))
          (= :layer.type/text (:layer/type layer)))
     nil

     was-drawing? (do (when (and (layer-model/detectable? layer)
                                 (or (not (get-in previous-state [:drawing :moving?]))
                                     (detectable-movement? original-layer layer)))
                        (d/transact! db [layer] {:can-undo? true}))
                      (cast! [:mouse-moved [x y]]))

     :else nil)))

(defmethod control-event :text-layer-finished
  [browser-state message [x y] state]
  (finalize-layer state))

(defmethod post-control-event! :text-layer-finished
  [browser-state message [x y] previous-state current-state]
  (let [cast! #(put! (get-in current-state [:comms :controls]) %)
        db           (:db current-state)
        layer        (get-in current-state [:drawing :layer])]
    (when (layer-model/detectable? layer)
      (d/transact! db [layer] {:can-undo? true}))
    (cast! [:mouse-moved [x y]])))

(defmethod control-event :deleted-selected
  [browser-state message _ state]
  (dissoc state :selected-eid))

(defmethod post-control-event! :deleted-selected
  [browser-state message _ previous-state current-state]
  (when-let [selected-eid (:selected-eid previous-state)]
    (let [db (:db current-state)
          document-id (:document/id current-state)
          selected-eids (layer-model/selected-eids @db selected-eid)]
      (d/transact! db (for [eid selected-eids]
                        [:db.fn/retractEntity eid])
                   {:can-undo? true}))))

(defmethod control-event :layer-selected
  [browser-state message {:keys [layer x y]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)]
    (-> state
        (assoc :selected-eid (:db/id layer))
        (assoc-in [:drawing :layer] (assoc layer
                                      :layer/current-x (:layer/end-x layer)
                                      :layer/current-y (:layer/end-y layer)))
        (assoc-in [:drawing :original-layer] layer)
        (assoc-in [:drawing :points] (when (:layer/path layer) (parse-points-from-path (:layer/path layer))))
        (assoc-in [:drawing :moving?] true)
        (assoc-in [:drawing :starting-mouse-position] [rx ry]))))

(defmethod control-event :menu-opened
  [browser-state message _ state]
  (print "menu opened")
  (-> state
      (update-in [:menu] assoc
                 :open? true
                 :x (get-in state [:mouse :x])
                 :y (get-in state [:mouse :y]))
      (assoc-in [:drawing :in-progress?] false)
      (assoc-in state/right-click-learned-path true)))

(defmethod control-event :menu-closed
  [browser-state message _ state]
  (-> state
      (assoc-in [:menu :open?] false)))

(defmethod control-event :tool-selected
  [browser-state message [tool] state]
  (-> state
      (assoc-in state/current-tool-path tool)
      (assoc-in [:menu :open?] false)))

(defmethod control-event :text-layer-re-edited
  [browser-state message layer state]
  (-> state
      (assoc-in [:drawing :layer] (assoc layer
                                    :layer/current-x (:layer/start-x layer)
                                    :layer/current-y (:layer/start-y layer)))
      (assoc-in [:drawing :in-progress?] true)
      (assoc-in state/current-tool-path :text)))

(defmethod post-control-event! :text-layer-re-edited
  [browser-state message layer previous-state current-state]
  (maybe-notify-subscribers! current-state nil nil))

(defmethod control-event :chat-db-updated
  [browser-state message _ state]
  (if (get-in state state/aside-menu-opened-path)
    (let [db @(:db state)
          last-chat-time (last (sort (chat-model/chat-timestamps-since db (js/Date. 0))))]
      (assoc-in state (state/last-read-chat-time-path (:document/id state)) last-chat-time))
    state))

(defmethod post-control-event! :chat-db-updated
  [browser-state message _ previous-state current-state]
  (cond
   (.isHidden (:visibility-monitor browser-state))
   (favicon/set-unread!)

   (not (get-in current-state state/aside-menu-opened-path))
   (let [db @(:db current-state)
         last-time (get-in current-state (state/last-read-chat-time-path (:document/id current-state)))
         unread-chats? (pos? (chat-model/compute-unread-chat-count db last-time))]
     (when unread-chats?
       (favicon/set-unread!)))))

(defmethod post-control-event! :visibility-changed
  [browser-state message {:keys [hidden?]} previous-state current-state]
  (when (and (not hidden?)
             (get-in current-state state/aside-menu-opened-path))
    (favicon/set-normal!)))

(defmethod control-event :chat-body-changed
  [browser-state message {:keys [value]} state]
  (let [entity-id (or (get-in state [:chat :entity-id])
                      (-> state :entity-ids first))]
    (-> state
        (assoc-in [:chat :body] value)
        (assoc-in [:chat :entity-id] entity-id)
        (update-in [:entity-ids] disj entity-id))))

(defmethod control-event :chat-submitted
  [browser-state message _ state]
  (-> state
      (assoc-in [:chat :body] nil)
      (assoc-in [:chat :entity-id] nil)))

(defmethod post-control-event! :chat-submitted
  [browser-state message _ previous-state current-state]
  (let [db (:db current-state)
        client-uuid (:client-uuid previous-state)
        color (get-in previous-state [:subscribers client-uuid :color])]
    (d/transact! db [{:chat/body (get-in previous-state [:chat :body])
                      :chat/color color
                      :cust/uuid (get-in current-state [:cust :uuid])
                      ;; TODO: teach frontend to lookup cust/name from cust/uuid
                      :chat/cust-name (get-in current-state [:cust :name])
                      :db/id (get-in previous-state [:chat :entity-id])
                      :session/uuid client-uuid
                      :document/id (:document/id previous-state)
                      :client/timestamp (js/Date.)
                      ;; server will overwrite this
                      :server/timestamp (js/Date.)}])))

(defmethod control-event :aside-menu-toggled
  [browser-state message _ state]
  (let [aside-open? (not (get-in state state/aside-menu-opened-path))
        db @(:db state)
        last-chat-time (or (last (sort (chat-model/chat-timestamps-since db (js/Date. 0))))
                           (js/Date. 0))]
    (-> state
        (assoc-in state/aside-menu-opened-path aside-open?)
        (assoc-in (state/last-read-chat-time-path (:document/id state)) last-chat-time)
        (assoc-in [:drawing :in-progress?] false)
        (assoc-in [:camera :offset-x] (if aside-open?
                                        (get-in state state/aside-width-path)
                                        0)))))

(defmethod post-control-event! :aside-menu-toggled
  [browser-state message _ previous-state current-state]
  (when (get-in current-state state/aside-menu-opened-path)
    (favicon/set-normal!)))

(defmethod control-event :overlay-info-toggled
  [browser-state message _ state]
  (-> state
      (update-in state/overlay-info-opened-path not)
      (assoc-in state/info-button-learned-path true)))

(defmethod control-event :overlay-closed
  [target message _ state]
  (-> state
      (assoc-in state/overlay-info-opened-path false)
      (assoc-in state/overlay-shortcuts-opened-path false)))

(defmethod post-control-event! :application-shutdown
  [browser-state message _ previous-state current-state]
  (sente/send-msg (:sente current-state) [:frontend/close-connection]))

(defmethod control-event :chat-mobile-toggled
  [browser-state message _ state]
  (-> state
      (update-in state/chat-mobile-opened-path not)))

(defmethod control-event :chat-link-clicked
  [browser-state message _ state]
   (-> state
     (assoc-in state/overlay-info-opened-path false)
     (assoc-in state/aside-menu-opened-path true)
     (assoc-in [:camera :offset-x] (get-in state state/aside-width-path))
     (assoc-in state/chat-mobile-opened-path true)
     (assoc-in [:chat :body] "@prcrsr ")))

(defmethod post-control-event! :chat-link-clicked
  [browser-state message _ previous-state current-state]
  (.focus (sel1 (:container browser-state) "#chat-box")))

(defmethod control-event :aside-user-clicked
  [browser-state message {:keys [id-str]} state]
   (-> state
     (assoc-in state/chat-mobile-opened-path true)
     (update-in [:chat :body] (fn [s]
                                (str (when (seq s)
                                       ;; maybe separate with space
                                       (str s (when (not= " " (last s)) " ")))
                                     "@" id-str " ")))))

(defmethod post-control-event! :aside-user-clicked
  [browser-state message _ previous-state current-state]
  (.focus (sel1 (:container browser-state) "#chat-box")))

(defmethod control-event :self-updated
  [browser-state message {:keys [name]} state]
  (assoc-in state [:cust :name] name))

(defmethod post-control-event! :self-updated
  [browser-state message {:keys [name]} previous-state current-state]
  (sente/send-msg (:sente current-state) [:frontend/update-self {:document/id (:document/id current-state)
                                                                 :cust/name name}]))

(defmethod control-event :canvas-scrolled
  [browser-state message {:keys [dx dy]} state]
  (let [camera (cameras/camera state)
        mode   (cameras/camera-mouse-mode state)]
    (if (= mode :zoom)
      (cameras/set-zoom state (partial + (* -0.002 dy)))
      (cameras/move-camera state dx dy))))

(def scroll-timer (atom nil))

(defmethod post-control-event! :canvas-scrolled
  [{:keys [history-imp]} message _ previous-state current-state]
  (let [{:keys [zf x y]} (:camera current-state)
        token (.getToken history-imp)
        [_ path query] (re-find #"([^\?]+)[\?]*(.*)" token)]
    (swap! scroll-timer
           (fn [s]
             (js/window.clearTimeout s)
             (js/setTimeout
              #(.replaceToken history-imp
                              (str path "?"
                                   (url/map->query
                                    (merge (url/query->map query)
                                           {"x" x "y" y "zf" zf}))))
              200)))))
