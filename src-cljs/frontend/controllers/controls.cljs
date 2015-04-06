(ns frontend.controllers.controls
  (:require [cemerick.url :as url]
            [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [cljs.reader :as reader]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.analytics.mixpanel :as mixpanel]
            [frontend.async :refer [put!]]
            [frontend.camera :as cameras]
            [frontend.careful]
            [frontend.components.forms :refer [release-button!]]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.db]
            [frontend.favicon :as favicon]
            [frontend.keyboard :as keyboard]
            [frontend.layers :as layers]
            [frontend.models.chat :as chat-model]
            [frontend.models.layer :as layer-model]
            [frontend.overlay :as overlay]
            [frontend.replay :as replay]
            [frontend.routes :as routes]
            [frontend.sente :as sente]
            [frontend.settings :as settings]
            [frontend.state :as state]
            [frontend.subscribers :as subs]
            [frontend.svg :as svg]
            [frontend.utils.ajax :as ajax]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer [dissoc-in]]
            [frontend.utils.state :as state-utils]
            [goog.dom]
            [goog.labs.userAgent.engine :as engine]
            [goog.math :as math]
            [goog.string :as gstring]
            goog.style)
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]])
  (:import goog.fx.dom.Scroll))

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

;; TODO: this shouldn't assume it's sending a mouse position
(defn maybe-notify-subscribers! [current-state x y]
  (when (get-in current-state [:subscribers :mice (:client-id current-state) :show-mouse?])
    (sente/send-msg (:sente current-state)
                    [:frontend/mouse-position (merge
                                               {:tool (get-in current-state state/current-tool-path)
                                                :document/id (:document/id current-state)
                                                :layers (when (or (get-in current-state [:drawing :in-progress?])
                                                                  (get-in current-state [:drawing :moving?]))
                                                          (get-in current-state [:drawing :layers]))
                                                :relation (when (get-in current-state [:drawing :relation :layer])
                                                            (get-in current-state [:drawing :relation]))}
                                               (when (and x y)
                                                 {:mouse-position (cameras/screen->point (:camera current-state) x y)}))])))

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

(defn cancel-drawing [state]
  (-> state
    (assoc :drawing {:layers []})
    (assoc-in [:editing-eids :editing-eids] #{})
    (assoc-in [:mouse-down] false)))

(defmethod control-event :cancel-drawing
  [browser-state message _ state]
  (cancel-drawing state))

(defmulti handle-keyboard-shortcut (fn [state shortcut-name] shortcut-name))

(defmethod handle-keyboard-shortcut :default
  [state shortcut-name]
  (if (contains? state/tools shortcut-name)
    (assoc-in state state/current-tool-path shortcut-name)
    state))

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

(defn handle-add-menu [state menu]
  (-> state
      (assoc-in [:layer-properties-menu :opened?] false)
      (assoc-in [:radial :open?] false)
      (overlay/add-overlay menu)))

(defn handle-replace-menu [state menu]
  (-> state
      (assoc-in [:layer-properties-menu :opened?] false)
      (assoc-in [:radial :open?] false)
      (overlay/replace-overlay menu)))

;; TODO: have some way to handle pre-and-post
(defmethod handle-keyboard-shortcut :undo
  [state shortcut-name]
  (handle-undo state))

(defmethod handle-keyboard-shortcut :shortcuts-menu
  [state shortcut-name]
  (if (= :shortcuts (overlay/current-overlay state))
    (overlay/clear-overlays state)
    (handle-replace-menu state :shortcuts)))

(defn close-menu [state]
  (assoc-in state [:menu :open?] false))

(defn clear-shortcuts [state]
  (assoc-in state [:keyboard] {}))

(defmethod handle-keyboard-shortcut :escape-interaction
  [state shortcut-name]
  (-> state
    overlay/clear-overlays
    close-menu
    cancel-drawing
    clear-shortcuts))

(defmethod handle-keyboard-shortcut :reset-canvas-position
  [state shortcut-name]
  (-> state
    (update-in [:camera] cameras/reset)))

(defmethod handle-keyboard-shortcut :return-from-origin
  [state shortcut-name]
  (-> state
    (update-in [:camera] cameras/previous)))

(defmethod handle-keyboard-shortcut :arrow-tool
  [state shortcut-name]
  state)

(defmethod control-event :key-state-changed
  [browser-state message [{:keys [key-set depressed?]}] state]
  (let [shortcuts (get-in state state/keyboard-shortcuts-path)
        new-state (assoc state :keyboard {key-set depressed?})]
    (-> new-state
      (cond-> (and depressed? (contains? (apply set/union (vals shortcuts)) key-set))
        (handle-keyboard-shortcut (first (filter #(-> shortcuts % (contains? key-set))
                                                 (keys shortcuts))))
        (and (= #{"shift"} key-set) (settings/drawing-in-progress? state))
        (assoc-in [:drawing :layers 0 :force-even?] depressed?)

        (= #{"space"} key-set)
        (assoc-in [:pan :position] {:x (get-in state [:mouse :x])
                                    :y (get-in state [:mouse :y])})

        (and (keyboard/arrow-shortcut-active? state)
             (not (keyboard/arrow-shortcut-active? new-state)))
        cancel-drawing))))

(defmulti handle-keyboard-shortcut-after (fn [state shortcut-name] shortcut-name))

(defmethod handle-keyboard-shortcut-after :default
  [state shortcut-name]
  nil)

(defn next-font-size [current-size direction]
  (let [grow? (keyword-identical? :grow direction)
        comp (if grow? > <)]
    (or (first (filter #(comp % current-size)
                       (sort-by (if grow? identity -) (conj state/font-options current-size))))
        current-size)))

(defn set-text-font-sizes [state direction]
  (some->> (get-in state [:selected-eids :selected-eids])
    (map #(d/entity @(:db state) %))
    (filter #(keyword-identical? (:layer/type %) :layer.type/text))
    (map (fn [layer]
           (let [font-size (next-font-size (:layer/font-size layer state/default-font-size) direction)]
             {:db/id (:db/id layer)
              :layer/font-size font-size
              :layer/end-x (+ (:layer/start-x layer) (utils/measure-text-width (:layer/text layer)
                                                                               font-size
                                                                               (:layer/font-family layer state/default-font-family)))
              :layer/end-y (- (:layer/start-y layer) font-size)})))
    seq
    (#(d/transact! (:db state) % {:can-undo? true}))))

(defmethod handle-keyboard-shortcut-after :shrink-text
  [state shortcut-name]
  (set-text-font-sizes state :shrink))

(defmethod handle-keyboard-shortcut-after :grow-text
  [state shortcut-name]
  (set-text-font-sizes state :grow))

(defmethod handle-keyboard-shortcut-after :escape-interaction
  [state shortcut-name]
  (when (and (:replay-interrupt-chan state)
             (put! (:replay-interrupt-chan state) :interrupt))
    (frontend.db/reset-db! (:db state) nil)
    (sente/subscribe-to-document (:sente state) (:comms state) (:document/id state))))

(defmethod post-control-event! :key-state-changed
  [browser-state message [{:keys [key-set depressed?]}] previous-state current-state]
  ;; TODO: better way to handle this
  (when (and depressed?
             (or (= key-set #{"backspace"})
                 (= key-set #{"del"})))
    (put! (get-in current-state [:comms :controls]) [:deleted-selected]))
  (let [shortcuts (get-in current-state state/keyboard-shortcuts-path)]
    (when (and depressed? (contains? (apply set/union (vals shortcuts)) key-set))
      (handle-keyboard-shortcut-after current-state (first (filter #(-> shortcuts % (contains? key-set))
                                                                   (keys shortcuts))))))
  (maybe-notify-subscribers! current-state nil nil))

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

(defn handle-drawing-started [state x y]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        [snap-x snap-y] (cameras/snap-to-grid (:camera state) rx ry)
        {:keys [entity-id state]} (frontend.db/get-entity-id state)
        layer (assoc (layers/make-layer entity-id (:document/id state) snap-x snap-y)
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
              (update-mouse x y)
              (assoc-in [:editing-eids :editing-eids] #{entity-id})
              (assoc-in [:selected-eids :selected-eids] #{entity-id})
              (assoc-in [:selected-arrows :selected-arrows] #{}))]
      r)))

(defmethod control-event :drawing-started
  [browser-state message [x y] state]
  (handle-drawing-started state x y))

(defmethod control-event :drawing-edited
  [browser-state message {:keys [layer x y]} state]
  (-> state
    (assoc-in [:drawing :in-progress?] true)
    (assoc-in [:drawing :layers] [(-> layer
                                    (update-in [:layer/start-x] #(if (= % x)
                                                                   (:layer/end-x layer)
                                                                   %))
                                    (update-in [:layer/start-y] #(if (= % y)
                                                                   (:layer/end-y layer)
                                                                   %))
                                    (dissoc :layer/end-x :layer/end-y)
                                    (assoc :layer/current-x x
                                           :layer/current-y y))])
    (assoc-in [:mouse-down] true)
    ;; TODO: do we need to update mouse?
    ;; (update-mouse x y)
    (assoc-in [:selected-eids :selected-eids] #{(:db/id layer)})
    (assoc-in [:editing-eids :editing-eids] #{(:db/id layer)})
    (assoc-in [:editing-arrows :editing-arrows] #{})))

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

(defmethod control-event :layer-duplicated
  [browser-state message {:keys [layer x y]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        {:keys [entity-id state]} (frontend.db/get-entity-id state)]
    (-> state
      (assoc-in [:selected-eids :selected-eids] #{entity-id})
      (assoc-in [:editing-eids :editing-eids] #{entity-id})
      (assoc-in [:editing-arrows :editing-arrows] #{})
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
                                                              (:layer/ui-target layer)))])
      (assoc-in [:drawing :moving?] true)
      (assoc-in [:drawing :starting-mouse-position] [rx ry]))))

(defmethod control-event :group-duplicated
  [browser-state message {:keys [x y]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        ;; TODO: better way to get selected layers
        db @(:db state)
        layer-eids (get-in state [:selected-eids :selected-eids])
        layers (mapv #(ds/touch+ (d/entity db %)) layer-eids)
        {:keys [entity-ids state]} (frontend.db/get-entity-ids state (count layers))
        eid-map (zipmap (map :db/id layers) entity-ids)]
    (-> state
      (assoc-in [:selected-eids :selected-eids] (set entity-ids))
      (assoc-in [:editing-eids :editing-eids] (set entity-ids))
      (assoc-in [:selected-arrows :selected-arrows] #{})
      (assoc-in [:drawing :original-layers] layers)
      (assoc-in [:drawing :layers] (mapv (fn [layer index]
                                           (-> layer
                                             (assoc :points (when (:layer/path layer) (parse-points-from-path (:layer/path layer)))
                                                    :db/id (get eid-map (:db/id layer))
                                                    :layer/start-x (:layer/start-x layer)
                                                    :layer/end-x (:layer/end-x layer)
                                                    :layer/current-x (:layer/end-x layer)
                                                    :layer/current-y (:layer/end-y layer)
                                                    :layer/ui-id (when (:layer/ui-id layer)
                                                                   (inc-str-id @(:db state) (:layer/ui-id layer) :offset index))
                                                    :layer/ui-target (when (:layer/ui-target layer)
                                                                       (:layer/ui-target layer)))
                                             (utils/update-when-in [:layer/points-to]
                                                                   (fn [ps]
                                                                     (set (map (fn [p]
                                                                                 {:db/id (get eid-map (:db/id p) (:db/id p))})
                                                                               ps))))))
                                         layers (range)))
      (assoc-in [:drawing :moving?] true)
      (assoc-in [:drawing :starting-mouse-position] [rx ry]))))

(defmethod control-event :text-layer-edited
  [browser-state message {:keys [value]} state]
  (-> state
    (assoc-in [:drawing :layers 0 :layer/text] value)))

(defn det [[ax ay] [bx by] [x y]]
  (math/sign (- (* (- bx ax)
                   (- y ay))
                (* (- by ay)
                   (- x ax)))))

(defn eids-in-bounding-box [db {:keys [start-x end-x start-y end-y] :as box}]
  (let [x0 (min start-x end-x)
        x1 (max start-x end-x)
        y0 (min start-y end-y)
        y1 (max start-y end-y)

        above-x0-start (d/index-range db :layer/start-x x0 nil)
        above-x0-start-e (set (map :e above-x0-start))

        above-x0-end (d/index-range db :layer/end-x x0 nil)
        above-x0-end-e (set (map :e above-x0-end))

        below-x1-start (d/index-range db :layer/start-x nil x1)
        below-x1-start-e (set (map :e below-x1-start))

        below-x1-end (d/index-range db :layer/end-x nil x1)
        below-x1-end-e (set (map :e below-x1-end))

        above-y0-start (d/index-range db :layer/start-y y0 nil)
        above-y0-start-e (set (map :e above-y0-start))

        above-y0-end (d/index-range db :layer/end-y y0 nil)
        above-y0-end-e (set (map :e above-y0-end))

        below-y1-start (d/index-range db :layer/start-y nil y1)
        below-y1-start-e (set (map :e below-y1-start))

        below-y1-end (d/index-range db :layer/end-y nil y1)
        below-y1-end-e (set (map :e below-y1-end))

        overlapping (set/intersection
                     (set/union above-x0-start-e
                                above-x0-end-e)

                     (set/union below-x1-start-e
                                below-x1-end-e)

                     (set/union above-y0-start-e
                                above-y0-end-e)

                     (set/union below-y1-start-e
                                below-y1-end-e))]
    (set (filter (fn [eid]
                   ;; TODO: optimize by looking up start-x, end-x, etc. from the index ranges
                   ;;       we've already created
                   (let [layer (d/entity db eid)]
                     (and (not (:layer/deleted layer))
                          (if (keyword-identical? (:layer/type layer) :layer.type/line)
                            (or
                             ;; has an endpoint
                             (and (contains? above-x0-start-e eid)
                                  (contains? below-x1-start-e eid)
                                  (contains? above-y0-start-e eid)
                                  (contains? below-y1-start-e eid))
                             ;; has an endpoint
                             (and (contains? above-x0-end-e eid)
                                  (contains? below-x1-end-e eid)
                                  (contains? above-y0-end-e eid)
                                  (contains? below-y1-end-e eid))
                             ;; all points aren't on one side of the line
                             (not= 4 (js/Math.abs
                                      (reduce + (map (partial det
                                                              [(:layer/start-x layer)
                                                               (:layer/start-y layer)]
                                                              [(:layer/end-x layer)
                                                               (:layer/end-y layer)])
                                                     [[x0 y0] [x0 y1] [x1 y0] [x1 y1]])))))
                            (let [sx (min (:layer/start-x layer)
                                          (:layer/end-x layer))
                                  ex (max (:layer/start-x layer)
                                          (:layer/end-x layer))
                                  sy (min (:layer/start-y layer)
                                          (:layer/end-y layer))
                                  ey (max (:layer/start-y layer)
                                          (:layer/end-y layer))]
                              ;; don't count a layer as selected if it fully contains the selected region
                              (or (< ex x1)
                                  (< ey y1)
                                  (> sx x0)
                                  (> sy y0)))))))
                 overlapping))))

(defn eids-containing-point [db x y]
  (let [candidates (set (map :e (d/datoms db :avet :layer/type :layer.type/rect)))

        above-x-start (d/index-range db :layer/start-x (- x 5) nil)
        above-x-start-e (set (map :e above-x-start))

        above-x-end (d/index-range db :layer/end-x (- x 5) nil)
        above-x-end-e (set (map :e above-x-end))

        above-y-start (d/index-range db :layer/start-y (- y 5) nil)
        above-y-start-e (set (map :e above-y-start))

        above-y-end (d/index-range db :layer/end-y (- y 5) nil)
        above-y-end-e (set (map :e above-y-end))

        below-x-start (d/index-range db :layer/start-x nil (+ x 5))
        below-x-start-e (set (map :e below-x-start))

        below-x-end (d/index-range db :layer/end-x nil (+ x 5))
        below-x-end-e (set (map :e below-x-end))

        below-y-start (d/index-range db :layer/start-y nil (+ y 5))
        below-y-start-e (set (map :e below-y-start))

        below-y-end (d/index-range db :layer/end-y nil (+ y 5))
        below-y-end-e (set (map :e below-y-end))

        rejects (set/union
                 (set/intersection above-x-start-e
                                   above-x-end-e)

                 (set/intersection below-x-start-e
                                   below-x-end-e)

                 (set/intersection above-y-start-e
                                   above-y-end-e)

                 (set/intersection below-y-start-e
                                   below-y-end-e))]
    (set/difference candidates
                    rejects)))

(defn arrows-in-bounding-box [db {:keys [start-x end-x start-y end-y]}]
  (let [x0 (min start-x end-x)
        x1 (max start-x end-x)
        y0 (min start-y end-y)
        y1 (max start-y end-y)]
    (reduce (fn [acc d]
              (let [origin (d/entity db (:e d))
                    dest (d/entity db (:v d))
                    origin-center (layers/center origin)
                    dest-center (layers/center dest)
                    [ox oy] (layers/layer-intercept origin dest-center)
                    [dx dy] (layers/layer-intercept dest origin-center)]
                (if (or
                     ;; has endpoint
                     (and (< x0 ox x1)
                          (< y0 oy y1))
                     ;; has endpoint
                     (and (< x0 dx x1)
                          (< y0 dy y1))

                     (and
                      (> (max ox dx) x0)
                      (< (min ox dx) x1)
                      (> (max oy dy) y0)
                      (< (min oy dy) y1)
                      (not= 4 (Math/abs (reduce + (map (partial det [ox oy] [dx dy])
                                                       [[x0 y0] [x0 y1] [x1 y0] [x1 y1]]))))))
                  (conj acc {:origin-id (:e d) :dest-id (:v d)})
                  acc)))
            #{} (d/datoms db :aevt :layer/points-to))))

(defn draw-in-progress-drawing [state x y {:keys [force-even? delta]}]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        [snap-x snap-y] (cameras/snap-to-grid (:camera state) rx ry)
        tool (get-in state state/current-tool-path)
        points (when (= :pen tool)
                 ((fnil conj []) (get-in state [:drawing :layers 0 :points]) {:x x :y x :rx rx :ry ry}))
        group? (= (get-in state [:drawing :layers 0 :layer/type]) :layer.type/group)
        bounding-eids (when group?
                        (eids-in-bounding-box (-> state :db deref)
                                              {:start-x (get-in state [:drawing :layers 0 :layer/start-x])
                                               :end-x snap-x
                                               :start-y (get-in state [:drawing :layers 0 :layer/start-y])
                                               :end-y snap-y}))
        bounding-arrows (when group?
                          (arrows-in-bounding-box @(:db state)
                                                  {:start-x (get-in state [:drawing :layers 0 :layer/start-x])
                                                   :end-x snap-x
                                                   :start-y (get-in state [:drawing :layers 0 :layer/start-y])
                                                   :end-y snap-y}))]
    (-> state
      (update-in [:drawing :layers 0] #(-> %
                                         (assoc :points points
                                                :force-even? force-even?
                                                :layer/current-x snap-x
                                                :layer/current-y snap-y)
                                         (cond-> (and (= tool :text)
                                                      (get-in state [:mouse-down]))
                                           ((fn [s]
                                              (let [zoom (get-in state [:camera :zf])]
                                                (-> s
                                                  (update-in [:layer/start-x] + (* (/ 1 zoom)
                                                                                   (:x delta)))
                                                  (update-in [:layer/start-y] + (* (/ 1 zoom)
                                                                                   (:y delta))))))))))
      (update-in [:drawing :layers 0]
                 (fn [layer]
                   (merge
                    layer
                    (when (= :pen tool)
                      {:layer/path (svg/points->path points)})
                    (when (or (= :circle tool)
                              ;; TODO: hack to preserve border-radius for re-editing circles
                              (layers/circle? layer))
                      {:layer/rx (js/Math.abs (- (:layer/start-x layer)
                                                 (:layer/current-x layer)))
                       :layer/ry (js/Math.abs (- (:layer/start-y layer)
                                                 (:layer/current-y layer)))})
                    (when (seq bounding-eids)
                      {:layer/child bounding-eids}))))
      (assoc-in [:editing-eids :editing-eids] #{(get-in state [:drawing :layers 0 :db/id])})
      (cond-> group? (assoc-in [:selected-eids :selected-eids] (set bounding-eids))
              group? (assoc-in [:selected-arrows :selected-arrows] (set bounding-arrows))))))

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

(defn draw-in-progress-relation [state x y]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)]
    (-> state
      (update-in [:drawing :relation] merge {:rx rx :ry ry}))))

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
    (-> state
      (assoc-in [:drawing :layers] layers)
      (assoc-in [:editing-eids :editing-eids] (set (map :db/id layers))))))

(defn pan-canvas [state x y]
  (-> state
    (assoc-in [:pan :position] {:x x :y y})
    (update-in [:camera] (fn [c]
                           (cameras/move-camera c
                                                (- x (get-in state [:pan :position :x]))
                                                (- y (get-in state [:pan :position :y])))))))

(defmethod control-event :mouse-moved
  [browser-state message [x y {:keys [shift?]}] state]
  (-> state

      (update-mouse x y)
      (cond-> (get-in state [:drawing :in-progress?])
        (draw-in-progress-drawing x y {:force-even? shift?
                                       :delta {:x (- x (get-in state [:mouse :x]))
                                               :y (- y (get-in state [:mouse :y]))}})

        (get-in state [:drawing :relation-in-progress?])
        (draw-in-progress-relation x y)

        (get-in state [:drawing :moving?])
        (move-drawings x y)

        (keyboard/pan-shortcut-active? state)
        ((fn [s]
            (if (:mouse-down s)
              (pan-canvas s x y)
              (assoc-in s [:pan :position] {:x x :y y})))))))

(defmethod post-control-event! :text-layer-edited
  [browser-state message _ previous-state current-state]
  (maybe-notify-subscribers! current-state nil nil))

(defmethod post-control-event! :mouse-moved
  [browser-state message [x y] previous-state current-state]
  (maybe-notify-subscribers! current-state x y))

(defn finalize-layer [state]
  (let [{:keys [x y]} (get-in state [:mouse])
        [rx ry] (cameras/screen->point (:camera state) x y)
        [snap-x snap-y] (cameras/snap-to-grid (:camera state) rx ry)
        layer (get-in state [:drawing :layers 0])
        layer-type (:layer/type layer)]
    (-> state
      (update-in [:drawing] assoc :in-progress? false)
      (assoc-in [:mouse-down] false)
      (assoc-in [:drawing :layers] [])
      (assoc-in [:editing-eids :editing-eids] #{})
      ;; TODO: get rid of nils (datomic doesn't like them)
      (assoc-in [:drawing :finished-layers] [(-> layer
                                                (assoc :layer/end-x snap-x
                                                       :layer/end-y snap-y)
                                                (#(if (:force-even? layer)
                                                    (layers/force-even %)
                                                    %))
                                                (dissoc :points :force-even? :layer/current-x :layer/current-y)
                                                (#(merge %
                                                         (when (= :circle (get-in state state/current-tool-path))
                                                           {:layer/rx (js/Math.abs (- (:layer/start-x %)
                                                                                      (:layer/end-x %)))
                                                            :layer/ry (js/Math.abs (- (:layer/start-y %)
                                                                                      (:layer/end-y %)))})
                                                         (when (= layer-type :layer.type/path)
                                                           (let [xs (map :rx (:points layer))
                                                                 ys (map :ry (:points layer))]
                                                             {:layer/path (svg/points->path (:points layer))
                                                              :layer/start-x (apply min xs)
                                                              :layer/end-x (apply max xs)
                                                              :layer/start-y (apply min ys)
                                                              :layer/end-y (apply max ys)}))
                                                         (when (= layer-type :layer.type/text)
                                                           {:layer/end-x (+ (get-in layer [:layer/start-x])
                                                                            (utils/measure-text-width (:layer/text layer)
                                                                                                      (:layer/font-size layer state/default-font-size)
                                                                                                      (:layer/font-family layer state/default-font-family)))
                                                            :layer/end-y (- (get-in layer [:layer/start-y])
                                                                            (:layer/font-size layer state/default-font-size))}))))])
      (assoc-in [:camera :moving?] false))))


;; should only get here if we ended on empty canvas
(defn finalize-relation [state]
  (let [{:keys [x y]} (get-in state [:mouse])
        [rx ry] (cameras/screen->point (:camera state) x y)
        origin-layer (get-in state [:drawing :relation :layer])
        dest-layer-id (first (shuffle (disj (eids-containing-point @(:db state) rx ry)
                                            (:db/id origin-layer))))]
    (-> state
      (update-in [:drawing] assoc :relation-in-progress? false)
      (assoc-in [:mouse-down] false)
      (assoc-in [:drawing :relation] {})
      #_(assoc-in [:drawing :finished-relation] {:origin-layer origin-layer
                                               :dest-layer-id dest-layer-id})
      (assoc-in [:camera :moving?] false))))

(defn drop-layers
  "Finalizes layer translation"
  [state]
  (let [layers (get-in state [:drawing :layers])]
    (-> state
      (assoc-in [:drawing :finished-layers] (mapv #(dissoc % :layer/current-x :layer/current-y) layers))
      (assoc-in [:drawing :layers] [])
      (assoc-in [:drawing :moving?] false)
      (assoc-in [:mouse-down] false)
      (assoc-in [:editing-eids :editing-eids] #{}))))

(defn handle-start-pan [state x y]
  (-> state
    (assoc-in [:pan :position] {:x x :y y})))

(defn mouse-depressed-intents [state button ctrl? shift?]
  (let [tool (get-in state state/current-tool-path)
        drawing-text? (and (keyword-identical? :text tool)
                           (get-in state [:drawing :in-progress?]))]
    (concat
     ;; If you click while writing text, you probably wanted to place it there
     ;; You also want the right-click menu to open
     (when drawing-text? [:finish-text-layer])
     (cond
       (keyboard/pan-shortcut-active? state) [:pan]
       (= button 2) [:open-menu]
       (and (= button 0) ctrl? (not shift?)) [:open-menu]
       (get-in state [:layer-properties-menu :opened?]) [:submit-layer-properties]
       (contains? #{:pen :rect :circle :line :select} tool) [:start-drawing]
       (and (keyword-identical? tool :text) (not drawing-text?)) [:start-drawing]
       :else nil))))

(declare handle-menu-opened)
(declare handle-menu-opened-after)
(declare handle-layer-properties-submitted)
(declare handle-layer-properties-submitted-after)
(declare handle-text-layer-finished)
(declare handle-text-layer-finished-after)

(defmethod control-event :mouse-depressed
  [browser-state message [x y {:keys [button type ctrl? shift?]}] state]
  (if (empty? (:frontend-id-state state))
    state
    (let [intents (mouse-depressed-intents state button ctrl? shift?)
          new-state (-> state
                      (update-mouse x y)
                      (assoc-in [:mouse-down] true)
                      (assoc-in [:mouse-type] (if (= type "mousedown") :mouse :touch)))]
      (reduce (fn [s intent]
                (case intent
                  :finish-text-layer (handle-text-layer-finished s)
                  :open-menu (handle-menu-opened s)
                  :start-drawing (handle-drawing-started s x y)
                  :submit-layer-properties (handle-layer-properties-submitted s)
                  :pan (handle-start-pan s x y)
                  s))
              new-state intents))))

(defmethod post-control-event! :mouse-depressed
  [browser-state message [x y {:keys [button ctrl? shift?]}] previous-state current-state]
  (when-not (empty? (:frontend-id-state previous-state))
    ;; use previous state so that we're consistent with the control-event
    (let [intents (mouse-depressed-intents previous-state button ctrl? shift?)]
      (doseq [intent intents]
        (case intent
          :finish-text-layer (handle-text-layer-finished-after current-state)
          :open-menu (handle-menu-opened-after current-state previous-state)
          :start-drawing nil
          :submit-layer-properties (handle-layer-properties-submitted-after current-state)
          nil)))))

(defn handle-relation-finished [state dest x y]
  (let [origin (get-in state [:drawing :relation :layer])]
    (-> state
      (assoc-in [:drawing :relation-in-progress?] false)
      (assoc-in [:mouse-down] false)
      (assoc-in [:drawing :relation] {})
      (assoc-in [:drawing :finished-relation] {:origin-layer origin
                                               :dest-layer-id (:db/id dest)})
      (update-in [:selected-eids :selected-eids] conj (:db/id dest))
      (update-in [:selected-arrows :selected-arrows] conj {:origin-id (:db/id origin)
                                                           :dest-id (:db/id dest)})
      (assoc-in [:camera :moving?] false))))

(defn handle-relation-finished-after [previous-state current-state dest x y]
  (let [db (:db current-state)]
    (when (and (get-in previous-state [:drawing :relation-in-progress?])
               (seq (get-in current-state [:drawing :finished-relation :origin-layer])))
      (d/transact! db [[:db/add
                        (get-in current-state [:drawing :finished-relation :origin-layer :db/id])
                        :layer/points-to
                        (get-in current-state [:drawing :finished-relation :dest-layer-id])]]
                   {:can-undo? true})
      (maybe-notify-subscribers! current-state x y))))

(defmethod control-event :layer-relation-mouse-down
  [browser-state message {:keys [layer x y]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)]
    (if (and (seq (get-in state [:drawing :relation :layer]))
             (not= (:db/id layer) (get-in state [:drawing :relation :layer :db/id])))
      (handle-relation-finished state layer x y)
      (-> state
        (update-mouse x y)
        (assoc-in [:mouse-down] true)
        (assoc-in [:drawing :relation-in-progress?] true)
        (assoc-in [:drawing :relation] {:layer layer
                                        :rx rx
                                        :ry ry})
        (assoc-in [:selected-eids :selected-eids] #{(:db/id layer)})))))

(defmethod post-control-event! :layer-relation-mouse-down
  [browser-state message {:keys [dest x y]} previous-state current-state]
  ;; only saves if there is a finished relation
  (handle-relation-finished-after previous-state current-state dest x y))

(defmethod control-event :layer-relation-mouse-up
  [browser-state message {:keys [dest x y]} state]
  (let [{:keys [x y]} (get-in state [:mouse])
        origin-layer (get-in state [:drawing :relation :layer])]
    ;; don't create a relation to yourself
    (if (= (:db/id dest) (:db/id origin-layer))
      state
      (handle-relation-finished state dest x y))))

(defmethod post-control-event! :layer-relation-mouse-up
  [browser-state message {:keys [dest x y]} previous-state current-state]
  (handle-relation-finished-after previous-state current-state dest x y))

(defn detectable-movement?
  "Checks to make sure we moved the layer from its starting position"
  [original-layer layer]
  (or (not= (:layer/start-x layer)
            (:layer/start-x original-layer))
      (not= (:layer/start-y layer)
            (:layer/start-y original-layer))))

(defmethod control-event :mouse-released
  [browser-state message [x y] state]
  (if (and (not (get-in state [:drawing :moving?]))
           (get-in state [:drawing :in-progress?])
           (= :layer.type/text (get-in state [:drawing :layers 0 :layer/type])))
    (assoc-in state [:mouse-down] false)
    (-> state
      (update-mouse x y)
      (assoc-in [:mouse-down] false)

      (cond-> (get-in state [:drawing :in-progress?])
        (finalize-layer)

        (get-in state [:drawing :relation-in-progress?])
        (finalize-relation)

        (get-in state [:drawing :moving?])
        (drop-layers)))))

(defmethod post-control-event! :mouse-released
  [browser-state message [x y {:keys [button type ctrl?]}] previous-state current-state]
  (let [cast! #(put! (get-in current-state [:comms :controls]) %)
        db           (:db current-state)
        was-drawing? (or (get-in previous-state [:drawing :in-progress?])
                         (get-in previous-state [:drawing :moving?]))
        original-layers (get-in previous-state [:drawing :original-layers])
        layers        (mapv #(-> %
                               (dissoc :points)
                               (utils/update-when-in [:layer/points-to] (fn [p] (set (map :db/id p))))
                               (utils/remove-map-nils))
                            (get-in current-state [:drawing :finished-layers]))]
    (cond
     (and (not= type "touchend")
          (not= button 2)
          (not (and (= button 0) ctrl?))
          (get-in current-state [:menu :open?]))
     (cast! [:menu-closed])

     (and (get-in previous-state [:drawing :relation-in-progress?])
          (seq (get-in current-state [:drawing :finished-relation :origin-layer])))
     (do
       (d/transact! db [[:db/add
                         (get-in current-state [:drawing :finished-relation :origin-layer :db/id])
                         :layer/points-to
                         (get-in current-state [:drawing :finished-relation :dest-layer-id])]]
                    {:can-undo? true})
       (maybe-notify-subscribers! current-state x y))

     (and (not (get-in previous-state [:drawing :moving?]))
          (every? #(= :layer.type/text (:layer/type %)) layers))
     nil

     was-drawing? (do (when (and (some layer-model/detectable? layers)
                                 (or (not (get-in previous-state [:drawing :moving?]))
                                     (some true? (map detectable-movement? original-layers layers))))
                        (doseq [layer-group (partition-all 100 layers)]
                          (d/transact! db (if (= :read (:max-document-scope current-state))
                                            (map #(assoc % :unsaved true) layer-group)
                                            layer-group)
                                       {:can-undo? true})))
                      (maybe-notify-subscribers! current-state x y))

     :else nil)))

(def handle-text-layer-finished finalize-layer)

(defmethod control-event :text-layer-finished
  [browser-state message _ state]
  (finalize-layer state))

(defn handle-text-layer-finished-after [current-state]
  (let [db (:db current-state)
        layer (utils/remove-map-nils (get-in current-state [:drawing :finished-layers 0]))
        layer (if (= :read (:max-document-scope current-state))
                (assoc layer :unsaved true)
                layer)]
    (when (layer-model/detectable? layer)
      (d/transact! db [layer] {:can-undo? true}))
    (maybe-notify-subscribers! current-state nil nil)))

(defmethod post-control-event! :text-layer-finished
  [browser-state message _ previous-state current-state]
  (handle-text-layer-finished-after current-state))

(defmethod control-event :deleted-selected
  [browser-state message _ state]
  (-> state
    (cancel-drawing)
    (assoc-in [:selected-eids :selected-eids] #{})
    (assoc-in [:editing-eids :editing-eids] #{})
    (assoc-in [:selected-arrows :selected-arrows] #{})))

(defmethod post-control-event! :deleted-selected
  [browser-state message _ previous-state current-state]
  (let [selected-eids (seq (get-in previous-state [:selected-eids :selected-eids]))
        selected-arrows (seq (get-in previous-state [:selected-arrows :selected-arrows]))
        db (:db current-state)
        document-id (:document/id current-state)
        txes (concat (for [eid selected-eids] [:db.fn/retractEntity eid])
                     (for [{:keys [origin-id dest-id]} selected-arrows] [:db/retract origin-id :layer/points-to dest-id]))]
    (doseq [tx-group (partition-all 100 txes)]
      (d/transact! db tx-group {:can-undo? true}))))

(defn conjv [& args]
  (apply (fnil conj []) args))

(defmethod control-event :layer-selected
  [browser-state message {:keys [layer x y append?]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)]
    (-> state
      (update-in [:selected-eids :selected-eids] (fn [eids]
                                                   (conj (if append? eids #{}) (:db/id layer))))
      (cond-> (not append?)
        (assoc-in [:selected-arrows :selected-arrows] #{}))
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

(defmethod control-event :arrow-selected
  [browser-state message {:keys [origin dest append?]} state]
  (-> state
    (update-in [:selected-arrows :selected-arrows] (fn [sels]
                                                     (conj (if append? sels #{}) {:origin-id (:db/id origin) :dest-id (:db/id dest)})))
    (cond-> (not append?)
      (assoc-in [:selected-eids :selected-eids] #{}))))

(defmethod control-event :arrow-deselected
  [browser-state message {:keys [origin dest]} state]
  (-> state
    (update-in [:selected-arrows :selected-arrows] disj {:origin-id (:db/id origin) :dest-id (:db/id dest)})))

;; TODO: hook this up. Unsure how to tell the difference between layer deselected and group-selected
(defmethod control-event :layer-deselected
  [browser-state message {:keys [layer]} state]
  (let [selected-eids (remove #{(:db/id layer)} (get-in state [:selected-eids :selected-eids]))]
    (-> state
      (assoc-in [:selected-eids :selected-eids] (set selected-eids))
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
  [browser-state message {:keys [x y]} state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)
        db @(:db state)
        layer-eids (get-in state [:selected-eids :selected-eids])
        layers (mapv #(ds/touch+ (d/entity db %)) layer-eids)]
    (-> state
      ;; TODO: this should just read from state, I think instead of passing it in
      (assoc-in [:selected-eids :selected-eids] (set layer-eids))
      (assoc-in [:drawing :layers] (mapv (fn [layer]
                                           (assoc layer
                                                  :layer/current-x (:layer/end-x layer)
                                                  :layer/current-y (:layer/end-y layer)
                                                  :points (when (:layer/path layer) (parse-points-from-path (:layer/path layer)))))
                                         layers))
      (assoc-in [:drawing :original-layers] layers)
      (assoc-in [:drawing :moving?] true)
      (assoc-in [:drawing :starting-mouse-position] [rx ry]))))

(defn handle-menu-opened [state]
  (-> state
    (update-in [:menu] assoc
               :open? true
               :x (get-in state [:mouse :x])
               :y (get-in state [:mouse :y]))
    (assoc-in [:drawing :in-progress?] false)
    (assoc-in state/right-click-learned-path true)))
    (assoc-in [:layer-properties-menu :opened?] false)))

(defmethod control-event :menu-opened
  [browser-state message _ state]
  (handle-menu-opened state))

(defn handle-menu-opened-after [previous-state current-state]
  (when (and (not (get-in previous-state state/right-click-learned-path))
             (get-in current-state state/right-click-learned-path))
    (analytics/track "Radial menu learned")))

(defmethod post-control-event! :menu-opened
  [browser-state message _ previous-state current-state]
  (handle-menu-opened-after previous-state current-state))

(defmethod control-event :menu-closed
  [browser-state message _ state]
  (-> state
    close-menu))

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
    (assoc-in [:selected-eids :selected-eids] #{(:db/id layer)})
    (assoc-in [:editing-eids :editing-eids] #{(:db/id layer)})
    (assoc-in [:drawing :in-progress?] true)
    (assoc-in [:mouse-down] true)
    (assoc-in state/current-tool-path :text)))

(defmethod post-control-event! :text-layer-re-edited
  [browser-state message layer previous-state current-state]
  (when (get-in previous-state [:drawing :in-progress?])
    (let [layers (get-in (finalize-layer previous-state) [:drawing :finished-layers])]
      (when (some layer-model/detectable? layers)
        (d/transact! (:db current-state)
                     (mapv (fn [l]
                             (-> l
                               utils/remove-map-nils))
                           layers)
                     {:can-undo? true}))))
  (maybe-notify-subscribers! current-state nil nil))

(defmethod control-event :chat-db-updated
  [browser-state message _ state]
  (if (get-in state state/chat-opened-path)
    (let [db @(:db state)
          last-chat-time (last (sort (chat-model/chat-timestamps-since db (js/Date. 0))))]
      (assoc-in state (state/last-read-chat-time-path (:document/id state)) last-chat-time))
    state))

(defmethod post-control-event! :chat-db-updated
  [browser-state message _ previous-state current-state]
  (cond
   (.isHidden (:visibility-monitor browser-state))
   (favicon/set-unread!)

   (not (get-in current-state state/chat-opened-path))
   (let [db @(:db current-state)
         last-time (get-in current-state (state/last-read-chat-time-path (:document/id current-state)))
         unread-chats? (pos? (chat-model/compute-unread-chat-count db last-time))]
     (when unread-chats?
       (favicon/set-unread!)))))

(defmethod post-control-event! :visibility-changed
  [browser-state message {:keys [hidden?]} previous-state current-state]
  (when (and (not hidden?)
             (get-in current-state state/chat-opened-path))
    (favicon/set-normal!)))

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

(defmethod handle-cmd-chat "replay"
  [state cmd chat]
  (update-in state [:db] frontend.db/reset-db!))

(defmethod control-event :chat-submitted
  [browser-state message {:keys [chat-body]} state]
  (let [{:keys [entity-id state]} (frontend.db/get-entity-id state)]
    (-> state
      (assoc-in state/chat-submit-learned-path true)
      (handle-cmd-chat (chat-cmd chat-body) chat-body)
      (assoc-in [:chat :entity-id] entity-id))))

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
    ;; this is a bit silly, but we have to make sure the chat tx msg
    ;; goes through before the send-invite message, since the messages are
    ;; serialized.
    (js/setTimeout
     #(sente/send-msg (:sente state) [:frontend/send-invite {:document/id (:document/id state)
                                                             :email email}])
     100)))

(defmethod post-handle-cmd-chat "toggle-grid"
  [state cmd body]
  ::stop-save)

(defmethod post-handle-cmd-chat "replay"
  [state cmd body]
  (@frontend.careful/om-setup-debug)

  (let [[_ delay-ms sleep-ms] (re-find #"/replay (\d+)s*(\d*)" body)]
    (replay/replay state
                   :sleep-ms (or (when (seq sleep-ms) (js/parseInt sleep-ms))
                                 150)
                   :delay-ms (or (when (seq delay-ms) (js/parseInt delay-ms))
                                 0)))
  ::stop-save)

(defmethod post-control-event! :chat-submitted
  [browser-state message {:keys [chat-body]} previous-state current-state]
  (let [db (:db current-state)
        client-id (:client-id previous-state)
        color (get-in previous-state [:subscribers :info client-id :color])
        stop-save? (= ::stop-save (when-let [cmd (chat-cmd chat-body)]
                                    (post-handle-cmd-chat current-state cmd chat-body)))]
    (when-not stop-save?
      (d/transact! db [(utils/remove-map-nils {:chat/body chat-body
                                               :chat/color color
                                               :cust/uuid (get-in current-state [:cust :cust/uuid])
                                               :db/id (get-in current-state [:chat :entity-id])
                                               :session/uuid (:sente-id previous-state)
                                               :chat/document (:document/id previous-state)
                                               :client/timestamp (datetime/server-date)
                                               ;; server will overwrite this
                                               :server/timestamp (datetime/server-date)})]))))

(defmethod control-event :chat-toggled
  [browser-state message _ state]
  (let [chat-open? (not (get-in state state/chat-opened-path))
        db @(:db state)
        last-chat-time (or (last (sort (chat-model/chat-timestamps-since db (js/Date. 0))))
                           (js/Date. 0))]
    (-> state
        (assoc-in state/chat-opened-path chat-open?)
        (assoc-in state/chat-button-learned-path true)
        (assoc-in (state/last-read-chat-time-path (:document/id state)) last-chat-time)
        (assoc-in [:drawing :in-progress?] false))))

(defmethod post-control-event! :chat-toggled
  [browser-state message _ previous-state current-state]
  (if (get-in current-state state/chat-opened-path)
    (do (analytics/track "Chat opened")
        (favicon/set-normal!))
    (analytics/track "Chat closed")))

(defmethod control-event :overlay-info-toggled
  [browser-state message _ state]
  (-> state
      (handle-add-menu :info)
      (assoc-in state/info-button-learned-path true)))

(defmethod control-event :overlay-username-toggled
  [browser-state message _ state]
  (-> state
      (handle-replace-menu :username)))

(defmethod post-control-event! :overlay-info-toggled
  [browser-state message _ previous-state current-state]
  (when (and (not (get-in previous-state state/info-button-learned-path))
             (get-in current-state state/info-button-learned-path))
    (analytics/track "What's this learned")))

(defmethod control-event :overlay-closed
  [target message _ state]
  (overlay/clear-overlays state))

(defmethod control-event :overlay-menu-closed
  [target message _ state]
  (overlay/pop-overlay state))

(defmethod control-event :roster-closed
  [target message _ state]
  (overlay/pop-overlay state))

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
     (assoc-in state/chat-opened-path true)
     (assoc-in state/chat-mobile-opened-path true)
     (assoc-in [:chat :body] "@prcrsr ")))

(defmethod post-control-event! :chat-link-clicked
  [browser-state message _ previous-state current-state]
  (.focus (goog.dom/getElement "chat-input")))

(defmethod control-event :invite-link-clicked
  [browser-state message _ state]
   (-> state
     (overlay/clear-overlays)
     (assoc-in state/chat-opened-path true)
     (assoc-in state/chat-mobile-opened-path true)
     (assoc-in [:chat :body] "/invite ")))

(defmethod post-control-event! :invite-link-clicked
  [browser-state message _ previous-state current-state]
  (.focus (goog.dom/getElement "chat-input")))

(defmethod control-event :chat-user-clicked
  [browser-state message {:keys [id-str]} state]
   (-> state
     (assoc-in state/chat-opened-path true)
     (assoc-in state/chat-mobile-opened-path true)))

(defmethod post-control-event! :chat-user-clicked
  [browser-state message {:keys [id-str]} previous-state current-state]
  (let [chat-input (goog.dom/getElement "chat-input")]
    (.focus chat-input)
    ;; TODO: need a better way to handle this, possibly by resurrecting
    ;;       the inputs things I built for Circle
    (let [s (.-value chat-input)]
      (set! (.-value chat-input)
            (str (when (seq s)
                   ;; maybe separate with space
                   (str s (when (not= " " (last s)) " ")))
                 "@" id-str " ")))))

(defmethod control-event :self-updated
  [browser-state message {:keys [name color]} state]
  (cond-> state
    name (-> (assoc-in [:cust :cust/name] name)
           (assoc-in [:cust-data :uuid->cust (get-in state [:cust :cust/uuid]) :cust/name] name))
    color (-> (assoc-in [:cust :cust/color-name] color)
           (assoc-in [:cust-data :uuid->cust (get-in state [:cust :cust/uuid]) :cust/color-name] color))))

(defmethod post-control-event! :self-updated
  [browser-state message {:keys [name color]} previous-state current-state]
  (sente/send-msg (:sente current-state) [:frontend/update-self (merge {:document/id (:document/id current-state)}
                                                                       (when name {:cust/name name})
                                                                       (when color {:cust/color-name color}))]))


(defmethod control-event :canvas-aligned-to-layer-center
  [browser-state message {:keys [ui-id canvas-size]} state]
  ;; TODO: how to handle no layer for ui-id
  (if-let [layer (layer-model/find-by-ui-id @(:db state) ui-id)]
    (let [zoom (:zf (:camera state))
          layer-width (js/Math.abs (- (:layer/start-x layer)
                                      (:layer/end-x layer)))
          layer-height (js/Math.abs (- (:layer/start-y layer)
                                       (:layer/end-y layer)))
          layer-start-x (min (:layer/start-x layer)
                             (:layer/end-x layer))
          layer-start-y (min (:layer/start-y layer)
                             (:layer/end-y layer))
          center-x (+ layer-start-x (/ layer-width 2))
          center-y (+ layer-start-y (/ layer-height 2))
          new-x (-  (/ (:width canvas-size) 2)
                    (* center-x zoom))
          new-y (- (/ (:height canvas-size) 2)
                   (* center-y zoom))]
      (-> state
        (assoc-in [:camera :x] new-x)
        (assoc-in [:camera :y] new-y)
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
        (assoc-in [:layer-properties-menu :y] ry)
        (assoc-in [:radial :open?] false))))

(defn handle-layer-properties-submitted [state]
  (-> state
    (assoc-in [:layer-properties-menu :opened?] false)))

(defmethod control-event :layer-properties-submitted
  [browser-state message _ state]
  (handle-layer-properties-submitted state))

(defn handle-layer-properties-submitted-after [current-state]
  (let [db (:db current-state)]
    (d/transact! db [(utils/remove-map-nils
                      (select-keys (get-in current-state [:layer-properties-menu :layer])
                                   [:db/id :layer/ui-id :layer/ui-target]))])))

(defmethod post-control-event! :layer-properties-submitted
  [browser-state message _ previous-state current-state]
  (handle-layer-properties-submitted-after current-state))

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
  (let [{:keys [entity-ids state]} (frontend.db/get-entity-ids state (count layers))
        eid-map (zipmap (map :db/id layers) entity-ids)
        doc-id (:document/id state)
        camera (:camera state)
        zoom (:zf camera)
        center-x (+ min-x (/ width 2))
        center-y (+ min-y (/ height 2))
        new-x (+ (* (- center-x) zoom)
                 (/ (:width canvas-size) 2))
        new-y (+ (* (- center-y) zoom)
                 (/ (:height canvas-size) 2))
        [move-x move-y] (cameras/screen->point camera new-x new-y)
        [snap-move-x snap-move-y] (cameras/snap-to-grid (:camera state) move-x move-y)
        new-layers (mapv (fn [l]
                           (-> l
                             (assoc :layer/ancestor (:db/id l)
                                    :db/id (get eid-map (:db/id l))
                                    :layer/document doc-id
                                    :points (when (:layer/path l) (parse-points-from-path (:layer/path l))))
                             (utils/update-when-in [:layer/points-to] (fn [dests]
                                                                        (set (filter :db/id (map #(update-in % [:db/id] eid-map) dests)))))
                             (#(move-layer % %
                                           {:snap-x snap-move-x :snap-y snap-move-y
                                            :move-x move-x :move-y move-y :snap-paths? true}))
                             (dissoc :layer/current-x :layer/current-y :points)))
                         layers)]
    (-> state
      (assoc-in [:clipboard :layers] new-layers)
      (assoc-in [:selected-eids :selected-eids] (set entity-ids))
      (assoc-in [:selected-arrows :selected-arrows] (set (reduce (fn [acc layer]
                                                                   (if-let [pointer (:layer/points-to layer)]
                                                                     (conj acc {:origin-id (:db/id layer)
                                                                                :dest-id (:db/id pointer)})
                                                                     acc))
                                                                   #{} new-layers))))))

(defmethod post-control-event! :layers-pasted
  [browser-state message _ previous-state current-state]
  (let [db (:db current-state)
        layers (mapv utils/remove-map-nils (get-in current-state [:clipboard :layers]))]
    (doseq [layer-group (partition-all 100 layers)]
      (d/transact! db
                   (if (= :read (:max-document-scope current-state))
                     (map #(assoc % :unsaved true) layer-group)
                     layer-group)
                   {:can-undo? true}))))

(defmethod control-event :your-docs-opened
  [browser-state message _ state]
  (-> state
      (handle-add-menu :doc-viewer)
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

(defmethod control-event :team-docs-opened
  [browser-state message _ state]
  (-> state
      (handle-add-menu :team-doc-viewer)))

(defmethod post-control-event! :team-docs-opened
  [browser-state message _ previous-state current-state]
  (sente/send-msg
   (:sente current-state)
   [:team/fetch-touched {:team/uuid (get-in current-state [:team :team/uuid])}]
   10000
   (fn [{:keys [docs]}]
     (when docs
       (put! (get-in current-state [:comms :api]) [:team-docs :success {:docs docs}])))))

(defmethod control-event :main-menu-opened
  [browser-state message _ state]
  (-> state
      (handle-replace-menu :start)
      (assoc-in state/main-menu-learned-path true)))

(defmethod control-event :roster-opened
  [browser-state message _ state]
  (-> state
      (handle-replace-menu :roster)))

(defmethod control-event :sharing-menu-opened
  [browser-state message _ state]
  (-> state
      (handle-add-menu :sharing)
      (assoc-in state/sharing-menu-learned-path true)))

(defmethod control-event :shortcuts-menu-opened
  [browser-state message _ state]
  (-> state
      (handle-add-menu :shortcuts)
      (assoc-in state/shortcuts-menu-learned-path true)))


(defmethod control-event :document-permissions-opened
  [browser-state message _ state]
  (-> state
      (handle-add-menu :document-permissions)))

(defmethod control-event :manage-permissions-opened
  [browser-state message _ state]
  (-> state
    (handle-add-menu :manage-permissions)))

(defmethod control-event :team-settings-opened
  [browser-state message _ state]
  (-> state
    (handle-add-menu :team-settings)))

(defmethod control-event :invite-to-changed
  [browser-state message {:keys [value]} state]
  (-> state
      (assoc-in state/invite-to-path value)))

(defmethod control-event :invite-submitted
  [browser-state message _ state]
  (assoc-in state state/invite-to-path nil))

(defmethod post-control-event! :invite-submitted
  [browser-state message _ previous-state current-state]
  (let [to (get-in previous-state state/invite-to-path)
        doc-id (:document/id previous-state)]
    (if (pos? (.indexOf to "@"))
      (sente/send-msg (:sente current-state) [:frontend/send-invite {:document/id doc-id
                                                                     :email to
                                                                     :invite-loc :overlay}])
      (sente/send-msg (:sente current-state) [:frontend/sms-invite {:document/id doc-id
                                                                    :phone-number to
                                                                    :invite-loc :overlay}]))))

(defmethod control-event :permission-grant-email-changed
  [browser-state message {:keys [value]} state]
  (-> state
      (assoc-in state/permission-grant-email-path value)))

(defmethod control-event :permission-grant-submitted
  [browser-state message _ state]
  (assoc-in state state/permission-grant-email-path nil))

(defmethod post-control-event! :permission-grant-submitted
  [browser-state message _ previous-state current-state]
  (let [email (get-in previous-state state/permission-grant-email-path)
        doc-id (:document/id previous-state)]
    (sente/send-msg (:sente current-state) [:frontend/send-permission-grant {:document/id doc-id
                                                                             :email email
                                                                             :invite-loc :overlay}])))

(defmethod post-control-event! :team-permission-grant-submitted
  [browser-state message {:keys [email]} previous-state current-state]
  (let [team-uuid (get-in current-state [:team :team/uuid])]
    (sente/send-msg (:sente current-state) [:team/send-permission-grant {:team/uuid team-uuid
                                                                         :email email
                                                                         :invite-loc :overlay}])))


(defmethod post-control-event! :document-privacy-changed
  [browser-state message {:keys [doc-id setting]} previous-state current-state]
  ;; privacy is on the write blacklist until we have a better way to do attribute-level permissions
  (d/transact! (:db current-state)
               [{:db/id doc-id :document/privacy setting}])
  (sente/send-msg (:sente current-state) [:frontend/change-privacy {:document/id doc-id
                                                                    :setting setting}]))


(defmethod post-control-event! :permission-requested
  [browser-state message {:keys [doc-id]} previous-state current-state]
  (sente/send-msg (:sente current-state) [:frontend/send-permission-request {:document/id doc-id
                                                                             :invite-loc :overlay}]))

(defmethod post-control-event! :access-request-granted
  [browser-state message {:keys [request-id doc-id team-uuid]} previous-state current-state]
  (sente/send-msg (:sente current-state) [(keyword (if doc-id
                                                     "frontend"
                                                     "team")
                                                   "grant-access-request")
                                          {:document/id doc-id
                                           :team/uuid team-uuid
                                           :request-id request-id
                                           :invite-loc :overlay}]))

(defmethod post-control-event! :access-request-denied
  [browser-state message {:keys [request-id doc-id team-uuid]} previous-state current-state]
  (sente/send-msg (:sente current-state) [(keyword (if doc-id
                                                     "frontend"
                                                     "team")
                                                   "deny-access-request")
                                          {:document/id doc-id
                                           :team/uuid team-uuid
                                           :request-id request-id
                                           :invite-loc :overlay}]))

(defmethod post-control-event! :make-button-clicked
  [browser-state message _ previous-state current-state]
  (put! (get-in current-state [:comms :nav]) [:navigate! {:path (str "/document/" (:document/id current-state))}]))

(defmethod post-control-event! :launch-app-clicked
  [browser-state message _ previous-state current-state]
  (put! (get-in current-state [:comms :nav]) [:navigate! {:path (str "/document/" (:document/id current-state))}]))

(defmethod control-event :subscriber-updated
  [browser-state message {:keys [client-id fields]} state]
  (subs/add-subscriber-data state client-id fields))

(defmethod control-event :viewers-opened
  [browser-state message _ state]
  (-> state
    (assoc :show-viewers? true)))

(defmethod control-event :viewers-closed
  [browser-state message _ state]
  (-> state
    (assoc :show-viewers? false)))

(defmethod control-event :landing-animation-completed
  [browser-state message _ state]
  (assoc state :show-scroll-to-arrow true))

(defmethod control-event :scroll-to-arrow-clicked
  [browser-state message _ state]
  (assoc state :show-scroll-to-arrow false))

(defmethod post-control-event! :scroll-to-arrow-clicked
  [browser-state message _ previous-state current-state]
  (let [body (.-body js/document)
        vh (.-height (goog.dom/getViewportSize))]
    (.play (goog.fx.dom.Scroll. body
                                #js [(.-scrollLeft body) (.-scrollTop body)]
                                #js [(.-scrollLeft body) vh]
                                375))))

(defmethod control-event :privacy-stats-clicked
  [browser-state message _ state]
  (-> state
    (handle-add-menu :sharing)))

(defmethod post-control-event! :mouse-stats-clicked
  [browser-state message _ previous-state current-state]
  (let [canvas-size (utils/canvas-size)
        camera (:camera current-state)
        z (:zf camera)
        [sx sy] [(/ (:width canvas-size) 2)
                 (/ (:height canvas-size) 2)]
        {:keys [x y]} (cameras/set-zoom camera [sx sy] (constantly 1))
        history (:history-imp browser-state)
        [_ path query-str] (re-find #"^([^\?]+)\?{0,1}(.*)$" (.getToken history))
        query (merge (url/query->map query-str)
                     {"cx" (int (+ (- x) sx))
                      "cy" (int (+ (- y) sy))
                      "z" (:zf camera)})]
    (.replaceToken history (str path (when (seq query)
                                       (str "?" (url/map->query query)))))))

(defmethod control-event :handle-camera-query-params
  [browser-state message {:keys [cx cy x y z]} state]
  (let [x (when x (js/parseInt x))
        y (when y (js/parseInt y))
        z (or (when z (js/parseFloat z))
              (get-in state [:camera :zf]))
        cx (when cx (js/parseInt cx))
        cy (when cy (js/parseInt cy))
        canvas-size (utils/canvas-size)
        [sx sy] [(/ (:width canvas-size) 2)
                 (/ (:height canvas-size) 2)]]
    (cond-> state
      x (assoc-in [:camera :x] x)
      y (assoc-in [:camera :y] y)
      cx (assoc-in [:camera :x] (- (- cx sx)))
      cy (assoc-in [:camera :y] (- (- cy sy)))
      z (update-in [:camera] #(-> %
                                (assoc :zf 1 :z-exact 1)
                                (cameras/set-zoom [sx sy] (constantly z)))))))
