(ns frontend.controllers.controls
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [cljs.reader :as reader]
            [clojure.set :as set]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.components.forms :refer [release-button!]]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.analytics.mixpanel :as mixpanel]
            [frontend.camera :as cameras]
            [frontend.datascript :as ds]
            [frontend.favicon :as favicon]
            [frontend.layers :as layers]
            [frontend.models.chat :as chat-model]
            [frontend.models.layer :as layer-model]
            [frontend.overlay :as overlay]
            [frontend.routes :as routes]
            [frontend.sente :as sente]
            [frontend.settings :as settings]
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

(defmulti handle-keyboard-shortcut (fn [state shortcut-name] shortcut-name))

(defmethod handle-keyboard-shortcut :default
  [state shortcut-name]
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
                              (let [next-undo-index (dec (vec-index transactions last-undo))]
                                (when-not (neg? next-undo-index)
                                  (nth transactions next-undo-index)))
                              (last transactions))]
    (when transaction-to-undo
      (ds/reverse-transaction transaction-to-undo (:db state))
      (swap! (:undo-state state) assoc :last-undo transaction-to-undo))
    state))

;; TODO: have some way to handle pre-and-post
(defmethod handle-keyboard-shortcut :undo
  [state shortcut-name]
  (handle-undo state))

(defmethod handle-keyboard-shortcut :shortcuts-menu
  [state shortcut-name]
  (overlay/replace-overlay state :shortcuts))

(defmethod handle-keyboard-shortcut :escape-interaction
  [state shortcut-name]
  (overlay/clear-overlays state))

(defmethod handle-keyboard-shortcut :reset-canvas-position
  [state shortcut-name]
  (-> state
      (update-in [:camera] cameras/reset)))

(defmethod control-event :key-state-changed
  [browser-state message [{:keys [key key-name-kw depressed?]}] state]
  (let [shortcuts (get-in state state/keyboard-shortcuts-path)]
    (-> state
        (assoc-in [:keyboard key-name-kw] depressed?)
        (cond-> (and depressed? (contains? (apply set/union (vals shortcuts)) key))
                (handle-keyboard-shortcut (first (filter #(-> shortcuts % (contains? key))
                                                         (keys shortcuts))))
                (and (= "shift" key) (settings/drawing-in-progress? state))
                (assoc-in [:drawing :layers 0 :force-even?] depressed?)))))

(defmethod post-control-event! :key-state-changed
  [browser-state message [{:keys [key-name-kw depressed?]}] previous-state current-state]
  ;; TODO: better way to handle this
  (when (or (= key-name-kw :backspace?)
            (= key-name-kw :del?))
    (put! (get-in current-state [:comms :controls]) [:deleted-selected])))

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
                (assoc-in [:drawing :layers] [(assoc layer
                                                :layer/current-x snap-x
                                                :layer/current-y snap-y)])
                (assoc-in [:mouse :down] true)
                (update-mouse x y)
                (assoc-in [:selected-eids] #{entity-id})
                (update-in [:entity-ids] disj entity-id))]
            r)))

;; These are used to globally increment names for layer targets and ids
;; There is definitely a better to do this, but not sure what it is at the moment.
;; Sorry for the complex code :/
;; Offset is there so you can duplicate multiple shapes
(def str-id-regex #"(.+)\((\d+)\)$")
(defn inc-str-id [db str-id & {:keys [offset]
                               :or {offset 0}}]
  (let [[_ base _] (re-find str-id-regex str-id)
        base (or base (str str-id " "))
        ids (remove nil? (map first (d/q '[:find ?id :where [_ :layer/ui-id ?id]] db)))
        max-num (reduce (fn [acc str-id]
                          (if-let [[match num] (re-find (re-pattern (str base "\\((\\d+\\))$")) str-id)]
                            (max acc (js/parseInt num))
                            acc))
                        0 ids)]
    (str base "(" (+ 1 offset max-num) ")")))


(defn inc-str-target [db str-target & {:keys [offset]
                                       :or {offset 0}}]
  (let [[_ base _] (re-find str-id-regex str-target)
        base (or base (str str-target " "))
        ids (remove nil? (map first (d/q '[:find ?id :where [_ :layer/ui-target ?id]] db)))
        max-num (reduce (fn [acc str-target]
                          (if-let [[match num] (re-find (re-pattern (str base "\\((\\d+\\))$")) str-target)]
                            (max acc (js/parseInt num))
                            acc))
                        0 ids)]
    (str base "(" (+ 1 offset max-num) ")")))

(defmethod control-event :layer-duplicated
  [browser-state message {:keys [layer x y]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        entity-id (-> state :entity-ids first)]
    (-> state
        (assoc :selected-eids #{entity-id})
        (assoc-in [:drawing :original-layers] [layer])
        (assoc-in [:drawing :layers] [(assoc layer
                                        :points (when (:layer/path layer) (parse-points-from-path (:layer/path layer)))
                                        :db/id entity-id
                                        :layer/start-x (:layer/start-x layer)
                                        :layer/end-x (:layer/end-x layer)
                                        :layer/current-x (:layer/end-x layer)
                                        :layer/current-y (:layer/end-y layer)
                                        :layer/ui-id (when (:layer/ui-id layer)
                                                       (inc-str-id @(:db state) (:layer/ui-id layer)))
                                        :layer/ui-target (when (:layer/ui-target layer)
                                                           (inc-str-target @(:db state) (:layer/ui-target layer))))])
        (assoc-in [:drawing :moving?] true)
        (assoc-in [:drawing :starting-mouse-position] [rx ry])
        (update-in [:entity-ids] disj entity-id))))

(defmethod control-event :group-duplicated
  [browser-state message {:keys [layer-eids x y]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        ;; TODO: better way to get selected layers
        db @(:db state)
        layers (mapv #(ds/touch+ (d/entity db %)) layer-eids)
        entity-ids (take (count layers) (:entity-ids state))]
    (-> state
        (assoc :selected-eids (set entity-ids))
        (assoc-in [:drawing :original-layers] layers)
        (assoc-in [:drawing :layers] (mapv (fn [layer entity-id index]
                                              (assoc layer
                                                :points (when (:layer/path layer) (parse-points-from-path (:layer/path layer)))
                                                :db/id entity-id
                                                :layer/start-x (:layer/start-x layer)
                                                :layer/end-x (:layer/end-x layer)
                                                :layer/current-x (:layer/end-x layer)
                                                :layer/current-y (:layer/end-y layer)
                                                :layer/ui-id (when (:layer/ui-id layer)
                                                               (inc-str-id @(:db state) (:layer/ui-id layer) :offset index))
                                                :layer/ui-target (when (:layer/ui-target layer)
                                                                   (inc-str-target @(:db state) (:layer/ui-target layer) :offset index))))
                                            layers entity-ids (range)))
        (assoc-in [:drawing :moving?] true)
        (assoc-in [:drawing :starting-mouse-position] [rx ry])
        (update-in [:entity-ids] (fn [eids] (apply disj eids entity-ids))))))

(defmethod control-event :text-layer-edited
  [browser-state message {:keys [value]} state]
  (-> state
      (assoc-in [:drawing :layers 0 :layer/text] value)))

(defn selectable? [db e]
  (not= :layer.type/group (:layer/type (d/entity db e))))

(defn eids-in-bounding-box [db {:keys [start-x end-x start-y end-y] :as box}]
  (let [x0 (min start-x end-x)
        x1 (max start-x end-x)
        y0 (min start-y end-y)
        y1 (max start-y end-y)
        has-x0 (set (map :e (d/index-range db :layer/start-x x0 x1)))
        has-x1 (set (map :e (d/index-range db :layer/end-x x0 x1)))
        has-y0 (set (map :e (d/index-range db :layer/start-y y0 y1)))
        has-y1 (set (map :e (d/index-range db :layer/end-y y0 y1)))]
    (set (filter (partial selectable? db)
                 (set/union (set/intersection has-x0 has-y0)
                            (set/intersection has-x0 has-y1)
                            (set/intersection has-x1 has-y0)
                            (set/intersection has-x1 has-y1))))))

(defn draw-in-progress-drawing [state x y {:keys [force-even?]}]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        [snap-x snap-y] (cameras/snap-to-grid (:camera state) rx ry)
        points ((fnil conj []) (get-in state [:drawing :layers 0 :points]) {:x x :y x :rx rx :ry ry})
        group? (= (get-in state [:drawing :layers 0 :layer/type]) :layer.type/group)
        bounding-eids (when group?
                        (eids-in-bounding-box (-> state :db deref)
                                              {:start-x (get-in state [:drawing :layers 0 :layer/start-x])
                                               :end-x snap-x
                                               :start-y (get-in state [:drawing :layers 0 :layer/start-y])
                                               :end-y snap-y}))]
    (-> state
        (update-in [:drawing :layers 0] #(-> %
                                             (assoc :points points
                                                    :force-even? force-even?
                                                    :layer/current-x snap-x
                                                    :layer/current-y snap-y)))
        (update-in [:drawing :layers 0]
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
                        {:layer/child bounding-eids}))))
        (cond-> group?
                (assoc-in [:selected-eids] bounding-eids)))))

(defn move-points [points move-x move-y]
  (map (fn [{:keys [rx ry]}]
         {:rx (+ rx move-x)
          :ry (+ ry move-y)})
       points))

(defn move-layer [layer original {:keys [snap-x snap-y x y snap-paths?]}]
  (-> (assoc layer
        :layer/start-x (+ snap-x (:layer/start-x original))
        :layer/end-x (+ snap-x (:layer/end-x original))
        :layer/current-x (+ snap-x (:layer/end-x original))
        :layer/start-y (+ snap-y (:layer/start-y original))
        :layer/end-y (+ snap-y (:layer/end-y original))
        :layer/current-y (+ snap-y (:layer/end-y original)))
      (cond-> (= :layer.type/path (:layer/type layer))
              (assoc :layer/path (svg/points->path (move-points (:points layer)
                                                                (if snap-paths?
                                                                  snap-x
                                                                  x)
                                                                (if snap-paths?
                                                                  snap-y
                                                                  y)))))))

(defn move-drawings [state x y]
  (let [[start-x start-y] (get-in state [:drawing :starting-mouse-position])
        [rx ry] (cameras/screen->point (:camera state) x y)
        [move-x move-y] [(- rx start-x) (- ry start-y)]
        [snap-move-x snap-move-y] (cameras/snap-to-grid (:camera state) move-x move-y)
        layers (get-in state [:drawing :layers])
        snap-paths? (first (filter #(not= :layer.type/path (:layer/type %)) layers))
        layers (mapv (fn [layer original]
                       (move-layer layer original {:snap-x snap-move-x :snap-y snap-move-y :x move-x :y move-y :snap-paths? snap-paths?}))
                     layers
                     (get-in state [:drawing :original-layers]))]
    (assoc-in state [:drawing :layers] layers)))

(defmethod control-event :mouse-moved
  [browser-state message [x y {:keys [shift?]}] state]
  (-> state

      (update-mouse x y)
      (cond-> (get-in state [:drawing :in-progress?])
              (draw-in-progress-drawing x y {:force-even? shift?})

              (get-in state [:drawing :moving?])
              (move-drawings x y))))

;; TODO: this shouldn't assume it's sending a mouse position
(defn maybe-notify-subscribers! [current-state x y]
  (when (get-in current-state [:subscribers (str (:client-uuid current-state)) :show-mouse?])
    (sente/send-msg (:sente current-state)
                    [:frontend/mouse-position (merge
                                               {:tool (get-in current-state state/current-tool-path)
                                                :document/id (:document/id current-state)
                                                :layers (when (or (get-in current-state [:drawing :in-progress?])
                                                                  (get-in current-state [:drawing :moving?]))
                                                          (get-in current-state [:drawing :layers]))}
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
        layer-type (get-in state [:drawing :layers 0 :layer/type])
        group? (= layer-type :layer.type/group)
        bounding-eids (when group?
                        (eids-in-bounding-box (-> state :db deref)
                                              {:start-x (get-in state [:drawing :layers 0 :layer/start-x])
                                               :end-x snap-x
                                               :start-y (get-in state [:drawing :layers 0 :layer/start-y])
                                               :end-y snap-y}))]
    (-> state
        (update-in [:drawing] assoc :in-progress? false)
        (assoc-in [:mouse :down] false)
        ;; TODO: get rid of nils (datomic doesn't like them)
        (update-in [:drawing :layers 0]
                   (fn [layer]
                     (-> layer
                         (assoc :layer/end-x snap-x
                                :layer/end-y snap-y)
                         (#(if (:force-even? layer)
                             (layers/force-even %)
                             %))
                         (dissoc :points :force-even? :layer/current-x :layer/current-y)
                         (#(merge %
                            (when (= :circle (get-in state state/current-tool-path))
                              {:layer/rx (Math/abs (- (:layer/start-x %)
                                                      (:layer/end-x %)))
                               :layer/ry (Math/abs (- (:layer/start-y %)
                                                      (:layer/end-y %)))})
                            (when (= layer-type :layer.type/path)
                              {:layer/path (svg/points->path (:points layer))})
                            (when (seq bounding-eids)
                              {:layer/child bounding-eids}))))))
        (assoc-in [:camera :moving?] false)
        (cond-> group?
                (assoc-in [:selected-eids] bounding-eids)))))

(defn drop-layers
  "Finalizes layer translation"
  [state]
  (-> state
      (update-in [:drawing :layers] (fn [layers] (mapv #(dissoc % :layer/current-x :layer/current-y) layers)))
      (assoc-in [:drawing :moving?] false)
      (assoc-in [:mouse :down] false)))

(defmethod control-event :mouse-released
  [browser-state message [x y] state]
  (if (and (not (get-in state [:drawing :moving?]))
           (get-in state [:drawing :in-progress?])
           (= :layer.type/text (get-in state [:drawing :layers 0 :layer/type])))
    state
    (-> state
        (update-mouse x y)

        (cond-> (get-in state [:drawing :in-progress?])
                (finalize-layer)

                (get-in state [:drawing :moving?])
                (drop-layers)))))

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
     (get-in current-state [:layer-properties-menu :opened?]) (cast! :layer-properties-submitted)
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
        original-layers (get-in previous-state [:drawing :original-layers])
        layers        (mapv #(dissoc % :points) (get-in current-state [:drawing :layers]))]
    (cond
     (and (not= type "touchend")
          (not= button 2)
          (not (and (= button 0) ctrl?))
          (get-in current-state [:menu :open?]))
     (cast! [:menu-closed])

     (and (not (get-in previous-state [:drawing :moving?]))
          (every? #(= :layer.type/text (:layer/type %)) layers))
     nil

     was-drawing? (do (when (and (some layer-model/detectable? layers)
                                 (or (not (get-in previous-state [:drawing :moving?]))
                                     (some true? (map detectable-movement? original-layers layers))))
                        (d/transact! db layers {:can-undo? true}))
                      (cast! [:mouse-moved [x y]]))

     :else nil)))

(defmethod control-event :text-layer-finished
  [browser-state message [x y] state]
  (finalize-layer state))

(defmethod post-control-event! :text-layer-finished
  [browser-state message [x y] previous-state current-state]
  (let [cast! #(put! (get-in current-state [:comms :controls]) %)
        db           (:db current-state)
        layer        (get-in current-state [:drawing :layers 0])]
    (when (layer-model/detectable? layer)
      (d/transact! db [layer] {:can-undo? true}))
    (cast! [:mouse-moved [x y]])))

(defmethod control-event :deleted-selected
  [browser-state message _ state]
  (dissoc state :selected-eids))

(defmethod post-control-event! :deleted-selected
  [browser-state message _ previous-state current-state]
  (when-let [selected-eids (seq (:selected-eids previous-state))]
    (let [db (:db current-state)
          document-id (:document/id current-state)]
      (d/transact! db (for [eid selected-eids]
                        [:db.fn/retractEntity eid])
                   {:can-undo? true}))))

(defn conjv [& args]
  (apply (fnil conj []) args))

(defmethod control-event :layer-selected
  [browser-state message {:keys [layer x y append?]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)]
    (-> state
        (update-in [:selected-eids] (fn [eids]
                                      ((fnil conj #{}) (if append? eids #{}) (:db/id layer))))
        (update-in [:drawing :layers]
                   (fn [layers]
                     (conjv (if append? layers []) layer)))
        (update-in [:drawing :layers]
                   (fn [layers]
                     ;; TODO: handle this better, should probably dissoc just before saving
                     (mapv (fn [layer]
                             (assoc layer
                               :layer/current-x (:layer/end-x layer)
                               :layer/current-y (:layer/end-y layer)
                               :points (when (:layer/path layer) (parse-points-from-path (:layer/path layer)))))
                           layers)))
        (update-in [:drawing :original-layers] (fn [layers]
                                                 (conjv (if append? layers [])
                                                        layer)))
        (assoc-in [:drawing :moving?] true)
        (assoc-in [:drawing :starting-mouse-position] [rx ry]))))

;; TODO: hook this up. Unsure how to tell the difference between layer deselected and group-selected
(defmethod control-event :layer-deselected
  [browser-state message {:keys [layer]} state]
  (let [selected-eids (disj (:selected-eids state) (:db/id layer))]
    (-> state
        (assoc-in [:selected-eids] selected-eids)
        (update-in [:drawing :layers]
                   (fn [layers]
                     (filterv #(not= (:db/id layer) (:db/id %)) layers)))
        (update-in [:drawing :original-layers]
                   (fn [layers]
                     (filterv #(not= (:db/id layer) (:db/id %)) layers)))
        (update-in [:drawing :layers]
                   (fn [layers]
                     ;; TODO: handle this better, should probably dissoc just before saving
                     (mapv (fn [layer]
                             (assoc layer
                               :layer/current-x (:layer/end-x layer)
                               :layer/current-y (:layer/end-y layer)
                               :points (when (:layer/path layer) (parse-points-from-path (:layer/path layer)))))
                           layers)))
        (assoc-in [:drawing :moving?] (not (empty? selected-eids))))))

(defmethod control-event :group-selected
  [browser-state message {:keys [layer-eids x y]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        db @(:db state)
        layers (mapv #(ds/touch+ (d/entity db %)) layer-eids)]
    (-> state
        ;; TODO: this should just read from state, I think instead of passing it in
        (assoc :selected-eids (set layer-eids))
        (assoc-in [:drawing :layers] (mapv (fn [layer]
                                             (assoc layer
                                               :layer/current-x (:layer/end-x layer)
                                               :layer/current-y (:layer/end-y layer)
                                               :points (when (:layer/path layer) (parse-points-from-path (:layer/path layer)))))
                                           layers))
        (assoc-in [:drawing :original-layers] layers)
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

(defmethod post-control-event! :menu-opened
  [browser-state message _ previous-state current-state]
  (when (and (not (get-in previous-state state/right-click-learned-path))
             (get-in current-state state/right-click-learned-path))
    (analytics/track "Radial menu learned")))

(defmethod control-event :menu-closed
  [browser-state message _ state]
  (-> state
      (assoc-in [:menu :open?] false)))

(defmethod control-event :newdoc-button-clicked
  [browser-state message _ state]
  (-> state
      (assoc-in state/newdoc-button-learned-path true)))

(defmethod control-event :login-button-clicked
  [browser-state message _ state]
  (-> state
      (assoc-in state/login-button-learned-path true)))

(defmethod control-event :tool-selected
  [browser-state message [tool] state]
  (-> state
      (assoc-in state/current-tool-path tool)
      (assoc-in [:menu :open?] false)))

(defmethod control-event :text-layer-re-edited
  [browser-state message layer state]
  (-> state
      (assoc-in [:drawing :layers] [(assoc layer
                                      :layer/current-x (:layer/start-x layer)
                                      :layer/current-y (:layer/start-y layer))])
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

(defn chat-cmd [body]
  (when (seq body)
    (last (re-find #"^/([^\s]+)" body))))

(defmulti handle-cmd-chat (fn [state cmd]
                            (utils/mlog "handling chat command:" cmd)
                            cmd))

(defmethod handle-cmd-chat :default
  [state cmd chat]
  (utils/mlog "unknown chat command:" cmd)
  state)

(defmethod handle-cmd-chat "toggle-grid"
  [state cmd chat]
  (update-in state [:camera :show-grid?] not))

(defmethod control-event :chat-submitted
  [browser-state message _ state]
  (-> state
      (handle-cmd-chat (chat-cmd (get-in state [:chat :body])) (get-in state [:chat :body]))
      (assoc-in [:chat :body] nil)
      (assoc-in [:chat :entity-id] nil)))

(defmulti post-handle-cmd-chat (fn [state cmd]
                                 (utils/mlog "post-handling chat command:" cmd)
                                 cmd))

;; TODO: more robust cmd parsing
(defmethod post-handle-cmd-chat :default
  [state cmd chat]
  (utils/mlog "unknown post chat command:" cmd))

(defmethod post-handle-cmd-chat "invite"
  [state cmd body]
  (let [email (last (re-find #"/invite\s+([^\s]+)" body))]
    (sente/send-msg (:sente state) [:frontend/send-invite {:document/id (:document/id state)
                                                           :email email}])))
(defmethod post-control-event! :chat-submitted
  [browser-state message _ previous-state current-state]
  (let [db (:db current-state)
        client-uuid (str (:client-uuid previous-state))
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
                      :server/timestamp (js/Date.)}])
    (when-let [cmd (chat-cmd (get-in previous-state [:chat :body]))]
      (post-handle-cmd-chat current-state cmd (get-in previous-state [:chat :body])))))

(defmethod control-event :aside-menu-toggled
  [browser-state message _ state]
  (let [aside-open? (not (get-in state state/aside-menu-opened-path))
        db @(:db state)
        last-chat-time (or (last (sort (chat-model/chat-timestamps-since db (js/Date. 0))))
                           (js/Date. 0))]
    (-> state
        (assoc-in state/aside-menu-opened-path aside-open?)
        (assoc-in state/menu-button-learned-path true)
        (assoc-in (state/last-read-chat-time-path (:document/id state)) last-chat-time)
        (assoc-in [:drawing :in-progress?] false)
        (assoc-in [:camera :offset-x] (if aside-open?
                                        (get-in state state/aside-width-path)
                                        0)))))

(defmethod post-control-event! :aside-menu-toggled
  [browser-state message _ previous-state current-state]
  (if (get-in current-state state/aside-menu-opened-path)
    (do (analytics/track "Aside menu opened")
        (favicon/set-normal!))
    (analytics/track "Aside menu closed")))

(defmethod control-event :overlay-info-toggled
  [browser-state message _ state]
  (-> state
      (overlay/replace-overlay :info)
      (assoc-in state/info-button-learned-path true)))

(defmethod control-event :overlay-username-toggled
  [browser-state message _ state]
  (-> state
      (overlay/replace-overlay :username)))

(defmethod post-control-event! :overlay-info-toggled
  [browser-state message _ previous-state current-state]
  (when (and (not (get-in previous-state state/info-button-learned-path))
             (get-in current-state state/info-button-learned-path))
    (analytics/track "What's this learned")))

(defmethod control-event :overlay-closed
  [target message _ state]
  (overlay/clear-overlays state))

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
     (overlay/clear-overlays)
     (assoc-in state/aside-menu-opened-path true)
     (assoc-in [:camera :offset-x] (get-in state state/aside-width-path))
     (assoc-in state/chat-mobile-opened-path true)
     (assoc-in [:chat :body] "@prcrsr ")))

(defmethod post-control-event! :chat-link-clicked
  [browser-state message _ previous-state current-state]
  (.focus (sel1 (:container browser-state) "#chat-box")))

(defmethod control-event :invite-link-clicked
  [browser-state message _ state]
   (-> state
     (overlay/clear-overlays)
     (assoc-in state/aside-menu-opened-path true)
     (assoc-in [:camera :offset-x] (get-in state state/aside-width-path))
     (assoc-in state/chat-mobile-opened-path true)
     (assoc-in [:chat :body] "/invite ")))

(defmethod post-control-event! :invite-link-clicked
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
  (-> state
    (assoc-in [:cust :name] name)))

(defmethod post-control-event! :self-updated
  [browser-state message {:keys [name]} previous-state current-state]
  (sente/send-msg (:sente current-state) [:frontend/update-self {:document/id (:document/id current-state)
                                                                 :cust/name name}]))


(defmethod post-control-event! :track-external-link-clicked
  [target message {:keys [path event properties]} previous-state current-state]
  (let [redirect #(js/window.location.replace path)]
    (go (alt!
         (mixpanel/managed-track event properties) ([v] (do (utils/mlog "tracked" v "... redirecting")
                                                            (redirect)))
         (async/timeout 1000) (redirect)))))

(defmethod control-event :canvas-aligned-to-layer-center
  [browser-state message {:keys [ui-id canvas-size]} state]
  ;; TODO: how to handle no layer for ui-id
  (if-let [layer (layer-model/find-by-ui-id @(:db state) ui-id)]
    (let [layer-width (js/Math.abs (- (:layer/start-x layer)
                                      (:layer/end-x layer)))
          layer-height (js/Math.abs (- (:layer/start-y layer)
                                       (:layer/end-y layer)))
          layer-start-x (min (:layer/start-x layer)
                             (:layer/end-x layer))
          layer-start-y (min (:layer/start-y layer)
                             (:layer/end-y layer))]
      (-> state
          (assoc-in [:camera :x] (+ (/ (:width canvas-size) 2)
                                    (- (+ layer-start-x
                                          (/ layer-width 2)))))
          (assoc-in [:camera :y] (+ (/ (:height canvas-size) 2)
                                    (- (+ layer-start-y
                                          (/ layer-height 2)))))
          (assoc-in [:drawing :in-progress?] false)
          (assoc-in [:drawing :moving?] false)))
    state))

(defmethod control-event :layer-properties-opened
  [browser-state message {:keys [layer x y]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)]
    (-> state
        (update-mouse x y)
        (assoc-in [:layer-properties-menu :opened?] true)
        (assoc-in [:layer-properties-menu :layer] layer)
        (assoc-in [:layer-properties-menu :x] rx)
        (assoc-in [:layer-properties-menu :y] ry))))

(defmethod control-event :layer-properties-submitted
  [browser-state message _ state]
  (-> state
      (assoc-in [:layer-properties-menu :opened?] false)))

(defmethod post-control-event! :layer-properties-submitted
  [browser-state message _ previous-state current-state]
  (let [db (:db current-state)]
    (d/transact! db [(select-keys (get-in current-state [:layer-properties-menu :layer])
                                  [:db/id :layer/ui-id :layer/ui-target])])))

(defn empty-str->nil [s]
  (if (str/blank? s)
    nil
    s))

;; TODO: need a way to delete these 2 values
(defmethod control-event :layer-ui-id-edited
  [browser-state message {:keys [value]} state]
  (-> state
      (assoc-in [:layer-properties-menu :layer :layer/ui-id] (empty-str->nil value))))

(defmethod control-event :layer-ui-target-edited
  [browser-state message {:keys [value]} state]
  (-> state
      (assoc-in [:layer-properties-menu :layer :layer/ui-target] (empty-str->nil value))))

(defmethod control-event :layers-pasted
  [browser-state message {:keys [layers height width min-x min-y canvas-size] :as layer-data} state]
  (let [layer-ids (take (count layers) (:entity-ids state))
        doc-id (:document/id state)
        new-x (- (/ (- (:width canvas-size) width) 2)
                 (:x (:camera state)))
        new-y (- (/ (- (:height canvas-size) height) 2)
                 (:y (:camera state)))
        [move-x move-y] [(- new-x min-x) (- new-y min-y)]
        [snap-move-x snap-move-y] (cameras/snap-to-grid (:camera state) move-x move-y)]
    (-> state
        (assoc-in [:clipboard :layers] (mapv (fn [l eid]
                                               (-> l
                                                   (assoc :layer/ancestor (:db/id l)
                                                          :db/id eid
                                                          :document/id doc-id
                                                          :points (when (:layer/path l) (parse-points-from-path (:layer/path l))))
                                                   (#(move-layer % %
                                                                 {:snap-x snap-move-x :snap-y snap-move-y
                                                                  :move-x move-x :move-y move-y :snap-paths? true}))
                                                   (dissoc :layer/current-x :layer/current-y :points)))
                                             layers layer-ids))
        (assoc-in [:selected-eids] (set layer-ids))
        (update-in [:entity-ids] #(apply disj % layer-ids)))))

(defmethod post-control-event! :layers-pasted
  [browser-state message _ previous-state current-state]
  (let [db (:db current-state)
        layers (get-in current-state [:clipboard :layers])]
    (d/transact! db layers {:can-undo? true})))

(defmethod post-control-event! :created-fetched
  [browser-state message _ previous-state current-state]
  (sente/send-msg
   (:sente current-state)
   [:frontend/fetch-created]
   10000
   (fn [{:keys [docs]}]
     (put! (get-in current-state [:comms :api]) [:created-docs :success {:docs docs}]))))

(defmethod control-event :your-docs-opened
  [browser-state message _ state]
  (-> state
      (overlay/replace-overlay :doc-viewer)
      (assoc-in state/your-docs-learned-path true)))

(defmethod post-control-event! :your-docs-opened
  [browser-state message _ previous-state current-state]
  (when (:cust current-state)
    (sente/send-msg
     (:sente current-state)
     [:frontend/fetch-touched]
     10000
     (fn [{:keys [docs]}]
       (when docs
         (put! (get-in current-state [:comms :api]) [:touched-docs :success {:docs docs}]))))))
