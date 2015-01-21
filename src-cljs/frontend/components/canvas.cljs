(ns frontend.components.canvas
  (:require [datascript :as d]
            [cljs.core.async :refer [put!]]
            [clojure.string :as str]
            [frontend.camera :as cameras]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.layers :as layers]
            [frontend.models.layer :as layer-model]
            [frontend.settings :as settings]
            [frontend.state :as state]
            [frontend.svg :as svg]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [goog.style]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

;; layers are always denominated in absolute coordinates
;; transforms are applied to handle panning and zooming

(defmulti svg-element (fn [selected-eids layer] (:layer/type layer)))

(defmethod svg-element :default
  [selected-eids layer]
  (print "No svg element for " layer))

(defn maybe-add-selected [svg-layer layer selected-eids]
  (if (contains? selected-eids (:db/id layer))
    (update-in svg-layer [:className] #(str % " selected"))
    svg-layer))

(defmethod svg-element :layer.type/rect
  [selected-eids layer]
  (-> (svg/layer->svg-rect layer)
      (maybe-add-selected layer selected-eids)
      (clj->js)
      (dom/rect)))

(defmethod svg-element :layer.type/text
  [selected-eids layer]
  (-> (svg/layer->svg-text layer)
      (maybe-add-selected layer selected-eids)
      (clj->js)
      (dom/text (:layer/text layer))))

(defmethod svg-element :layer.type/line
  [selected-eids layer]
  (dom/line (clj->js (merge
                      layer
                      (if (contains? selected-eids (:db/id layer))
                        {:className (str (:className layer) " selected shape-layer")}
                        {:className (str (:className layer) " shape-layer")})
                      (svg/layer->svg-line layer)))))

(defmethod svg-element :layer.type/path
  [selected-eids layer]
  (dom/path
   (clj->js (merge (dissoc layer :points)
                   (svg/layer->svg-path layer)
                   {:className (str (:className layer)
                                    " shape-layer "
                                    (when (contains? selected-eids (:db/id layer)) " selected"))}))))

(defmethod svg-element :layer.type/group
  [state selected-eids layer]
  (print "Nothing to do for groups, yet."))

(defn state->cursor [state]
  (case (get-in state state/current-tool-path)
    :text "text"
    :select "default"
    "crosshair"))

(defn handles [layer owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner [:cast!])
            ;; easier than calcing width, b/c we can just multiply by 2
            handle-offset (max 1
                               (min 4
                                    (int (/ (min (js/Math.abs (- (:layer/start-x layer) (:layer/end-x layer)))
                                                 (js/Math.abs (- (:layer/start-y layer) (:layer/end-y layer))))
                                            4))))]
        (apply dom/g #js {:className "edit-handles"}
               (for [[x y] (layers/endpoints layer)]
                 (dom/rect #js {:className (str "edit-handle "
                                                (if (= x (max (:layer/start-x layer) (:layer/end-x layer)))
                                                  "right "
                                                  "left ")
                                                (if (= y (max (:layer/start-y layer) (:layer/end-y layer)))
                                                  "bottom "
                                                  "top "))
                                :x (- x handle-offset)
                                :y (- y handle-offset)
                                :width (* 2 handle-offset)
                                :height (* 2 handle-offset)
                                :onMouseDown #(do (.stopPropagation %)
                                                  (cast! :drawing-edited {:layer layer
                                                                          :x x
                                                                          :y y}))
                                :key (str "edit-handle-" x "-" y)})))))))

(defn layer-group [layer {:keys [tool selected-eids cast! db live?]}]
  (let [invalid? (and (:layer/ui-target layer)
                      (not (pos? (layer-model/count-by-ui-id @db (:layer/ui-target layer)))))
        show-handles? (and (= 1 (count selected-eids))
                           (contains? selected-eids (:db/id layer))
                           (contains? #{:layer.type/rect :layer.type/line} (:layer/type layer))
                           (or (= :select tool)
                               (and (= :circle tool) (layers/circle? layer))
                               (and (= :line tool) (= :layer.type/line (:layer/type layer)))
                               (and (= :rect tool) (not (layers/circle? layer)))))]
    (dom/g #js {:className (str "layer "
                                (when (= :select tool) "selectable-group ")
                                (when invalid? "invalid "))
                :key (str (:db/id layer) live?)}

           (when (and show-handles? (layers/circle? layer))
             (let [layer (layers/normalized-abs-coords layer)]
               (dom/rect #js {:className "handle-outline"
                              :x (:layer/start-x layer)
                              :y (:layer/start-y layer)
                              :width (- (:layer/end-x layer) (:layer/start-x layer))
                              :height (- (:layer/end-y layer) (:layer/start-y layer))
                              :fill "none"
                              :strokeWidth 1})))

           (svg-element selected-eids
                        (assoc layer
                               :className (str "selectable-layer layer-handle "
                                               (when (and (= :layer.type/text (:layer/type layer))
                                                          (= :text tool)) "editable "))
                               :key (str "selectable-" (:db/id layer))
                               :onMouseDown
                               #(do
                                  (.stopPropagation %)
                                  (let [group? (and (< 1 (count selected-eids))
                                                    (contains? selected-eids (:db/id layer)))]

                                    (cond
                                      (and (= :text tool)
                                           (= :layer.type/text (:layer/type layer)))
                                      (cast! :text-layer-re-edited layer)

                                      (not= :select tool) nil

                                      (or (= (.-button %) 2)
                                          (and (= (.-button %) 0) (.-ctrlKey %)))
                                      (cast! :layer-properties-opened {:layer layer
                                                                       :x (first (cameras/screen-event-coords %))
                                                                       :y (second (cameras/screen-event-coords %))})


                                      (and (.-altKey %) group?)
                                      (cast! :group-duplicated
                                             {:layer-eids selected-eids
                                              :x (first (cameras/screen-event-coords %))
                                              :y (second (cameras/screen-event-coords %))})

                                      (and (.-altKey %) (not group?))
                                      (cast! :layer-duplicated
                                             {:layer layer
                                              :x (first (cameras/screen-event-coords %))
                                              :y (second (cameras/screen-event-coords %))})

                                      (and (.-shiftKey %) (contains? selected-eids (:db/id layer)))
                                      (cast! :layer-deselected {:layer layer})


                                      group?
                                      (cast! :group-selected {:x (first (cameras/screen-event-coords %))
                                                              :y (second (cameras/screen-event-coords %))
                                                              :layer-eids selected-eids})

                                      :else
                                      (cast! :layer-selected {:layer layer
                                                              :x (first (cameras/screen-event-coords %))
                                                              :y (second (cameras/screen-event-coords %))
                                                              :append? (.-shiftKey %)}))))))
           (when-not (= :layer.type/text (:layer/type layer))
             (svg-element selected-eids (assoc layer
                                               :className (str "layer-outline ")
                                               :key (:db/id layer))))
           ;; TODO: figure out what to do with this title
           ;; (when invalid?
           ;;   (dom/title nil
           ;;              (str "This action links to \""  (:layer/ui-target layer) "\", but no shapes have that name."
           ;;                   " Right-click on a shape's border to name it " (:layer/ui-target layer))))

           (when show-handles?
             (om/build handles layer))

           (when (:layer/ui-target layer)
             (svg-element selected-eids
                          (assoc layer
                                 :padding 4 ;; only works for rects right now
                                 :onMouseDown #(when (= tool :select)
                                                 (.stopPropagation %)
                                                 (cond
                                                   (or (= (.-button %) 2)
                                                       (and (= (.-button %) 0) (.-ctrlKey %)))
                                                   (cast! :layer-properties-opened {:layer layer
                                                                                    :x (first (cameras/screen-event-coords %))
                                                                                    :y (second (cameras/screen-event-coords %))})

                                                   (and (< 1 (count selected-eids))
                                                        (contains? selected-eids (:db/id layer)))
                                                   (cast! :group-selected
                                                          {:x (first (cameras/screen-event-coords %))
                                                           :y (second (cameras/screen-event-coords %))
                                                           :layer-eids selected-eids})

                                                   :else
                                                   (cast! :canvas-aligned-to-layer-center
                                                          {:ui-id (:layer/ui-target layer)
                                                           :canvas-size (let [size (goog.style/getSize (goog.dom/getElement "svg-canvas"))]
                                                                          {:width (.-width size)
                                                                           :height (.-height size)})})))

                                 :className (str "action interactive-fill "
                                                 (when (and (< 1 (count selected-eids))
                                                            (contains? selected-eids (:db/id layer)))
                                                   "selected-group ")
                                                 (when invalid?
                                                   "invalid"))
                                 :key (str "action-" (:db/id layer))))))))

(defn svg-layers [{:keys [editing-eids selected-eids tool] :as data} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (:tx-data tx-report))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            selected-eids (or selected-eids #{})
            layers (ds/touch-all '[:find ?t :where [?t :layer/name]] @db)
            renderable-layers (remove #(or (= :layer.type/group (:layer/type %))
                                           (contains? editing-eids (:db/id %))) layers)
            {idle-layers false live-layers true} (group-by (comp boolean :layer/ui-target)
                                                           renderable-layers)]
        ;; TODO: this should probably be split into a couple of components
        (dom/g #js {:className (if (= :select tool)
                                 "interactive"
                                 "static")}
               (apply dom/g #js {:className "layers idle"}
                      (mapv #(layer-group % {:live? false
                                             :cast! cast!
                                             :tool tool
                                             :selected-eids selected-eids
                                             :db db})
                            idle-layers))
               (apply dom/g #js {:className "layers live"}
                      (mapv #(layer-group % {:live? true
                                             :cast! cast!
                                             :tool tool
                                             :selected-eids selected-eids
                                             :db db})
                            live-layers)))))))

(defn subscriber-cursor-icon [tool]
  (case (name tool)
    "pen" :crosshair
    "line" :crosshair
    "rect" :crosshair
    "circle" :crosshair

    "text" :ibeam

    "select" :cursor))

(defn cursor [[id subscriber] owner]
  (reify
    om/IRender
    (render [_]
      (if (and (:tool subscriber)
               (:show-mouse? subscriber))
        (html (common/svg-icon (subscriber-cursor-icon (:tool subscriber))
                               {:svg-props {:height 16 :width 16
                                            :class "mouse-tool"
                                            :x (- (first (:mouse-position subscriber)) 8)
                                            :y (- (last (:mouse-position subscriber)) 8)
                                            :key id}
                                :path-props {:style {:stroke (:color subscriber)}}}))
        (dom/circle #js {:cx 0 :cy 0 :r 0})))))

(defn cursors [{:keys [subscribers client-id]} owner]
  (reify
    om/IRender
    (render [_]
      (apply dom/g nil
             (om/build-all cursor (dissoc subscribers client-id))))))

(defn text-input [layer owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (.focus (om/get-node owner "input"))
      (om/set-state! owner :input-min-width (.-width (goog.style/getSize (om/get-node owner "input-width-tester")))))
    om/IDidUpdate
    (did-update [_ _ _]
      (.focus (om/get-node owner "input"))
      (om/set-state! owner :input-min-width (.-width (goog.style/getSize (om/get-node owner "input-width-tester")))))
    om/IInitState
    (init-state [_]
      {:input-min-width 0})
    om/IRender
    (render [_]
      (let [{:keys [cast!]} (om/get-shared owner)
            text-style {:font-size (:layer/font-size layer 20)}]
        (dom/foreignObject #js {:width "100%"
                                :height "100%"
                                :x (:layer/start-x layer)
                                ;; TODO: defaults for each layer when we create them
                                :y (- (:layer/start-y layer) (:layer/font-size layer 22))}
          (dom/form #js {:className "svg-text-form"
                         :onMouseDown #(.stopPropagation %)
                         :onMouseUp #(.stopPropagation %)
                         :onWheel #(.stopPropagation %)

                         :onSubmit (fn [e]
                                     (cast! :text-layer-finished)
                                     (utils/stop-event e))
                         :onKeyDown #(cond (= "Enter" (.-key %))
                                           (do (cast! :text-layer-finished)
                                               (utils/stop-event %))

                                           (= "Escape" (.-key %))
                                           (do (cast! :cancel-drawing)
                                               (utils/stop-event %))

                                           :else nil)}
                    ;; TODO: experiment with a contentEditable div
                    (dom/input #js {:type "text"
                                    :placeholder "Type something..."
                                    :value (or (:layer/text layer) "")
                                    ;; TODO: defaults for each layer when we create them
                                    :style (clj->js (merge text-style
                                                           {:width (+ 256 (om/get-state owner :input-min-width))}))
                                    :ref "input"
                                    :onChange #(cast! :text-layer-edited {:value (.. % -target -value)})})
                    (dom/div #js {:style (clj->js (merge {:visibility "hidden"
                                                          :position "fixed"
                                                          :top "-100px"
                                                          :left "0"
                                                          :display "inline-block"}
                                                         text-style))
                                  :ref "input-width-tester"}
                      (:layer/text layer))))))))

(defn subscriber-layers [{:keys [layers]} owner]
  (reify
    om/IRender
    (render [_]
      (if-not (seq layers)
        (dom/g nil nil)
        (apply dom/g nil (mapv (fn [l] (svg-element #{} (merge l {:strokeDasharray "5,5"
                                                                  :layer/fill "none"
                                                                  :style {:stroke (:subscriber-color l)}
                                                                  :fillOpacity "0.5"
                                                                  :key (str (:db/id l) "-subscriber-layer")}
                                                               (when (= :layer.type/text (:layer/type l))
                                                                 {:layer/stroke "none"
                                                                  :style {:fill (:subscriber-color l)}}))))
                               layers))))))

(defn layer-properties [{:keys [layer x y]} owner]
  (reify
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                                   :input-expanded false})
    om/IDidMount
    (did-mount [_]
      (.focus (om/get-node owner "id-input"))
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (some #(= (:a %) :layer/ui-id) (:tx-data tx-report))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            ;; TODO: figure out how to handle nils in datascript
            targets (->> (d/q '{:find [?id]
                                :where [[_ :layer/ui-id ?id]]}
                              @db)
                         (map first)
                         (remove nil?)
                         sort)]
        (dom/foreignObject #js {:width "100%"
                                :height "100%"
                                :x x
                                ;; TODO: defaults for each layer when we create them
                                :y y}
          (dom/form #js {:className "layer-properties"
                         :onMouseDown #(.stopPropagation %)
                         :onMouseUp #(.stopPropagation %)
                         :onWheel #(.stopPropagation %)
                         :onSubmit (fn [e]
                                     (cast! :layer-properties-submitted)
                                     (utils/stop-event e))
                         :onKeyDown #(when (= "Enter" (.-key %))
                                       (cast! :layer-properties-submitted)
                                       (utils/stop-event %))}
                    (dom/input #js {:type "text"
                                    :ref "id-input"
                                    :className "layer-property-id"
                                    :onClick #(.focus (om/get-node owner "id-input"))
                                    :required "true"
                                    :data-adaptive ""
                                    :value (or (:layer/ui-id layer) "")
                                    ;; TODO: defaults for each layer when we create them
                                    :onChange #(cast! :layer-ui-id-edited {:value (.. % -target -value)})})
                    (dom/label #js {:data-placeholder "name"
                                    :data-placeholder-nil "define a name"
                                    :data-placeholder-busy "defining name"})
                    (when-not (= :layer.type/line (:layer/type layer))
                      (dom/input #js {:type "text"
                                      :ref "target-input"
                                      :className (if (om/get-state owner :input-expanded)
                                                   "layer-property-target expanded"
                                                   "layer-property-target")
                                      :required "true"
                                      :data-adaptive ""
                                      :value (or (:layer/ui-target layer) "")
                                      :onClick #(.focus (om/get-node owner "target-input"))
                                      :onChange #(cast! :layer-ui-target-edited {:value (.. % -target -value)})}))
                    (when-not (= :layer.type/line (:layer/type layer))
                      (dom/label #js {:data-placeholder "is targeting"
                                      :data-placeholder-nil "define a target"
                                      :data-placeholder-busy "defining target"}))
                    (when-not (= :layer.type/line (:layer/type layer))
                      (when (seq targets)
                        (dom/button #js {:className "layer-property-button"
                                         :onClick #(do (om/update-state! owner :input-expanded not)
                                                       (utils/stop-event %))}
                                    "...")))
                    (apply dom/div #js {:className (if (om/get-state owner :input-expanded)
                                                     "property-dropdown-targets expanded"
                                                     "property-dropdown-targets")}
                           (for [target targets]
                             (dom/a #js {:className "property-dropdown-target"
                                         :role "button"
                                         :onClick #(do (cast! :layer-ui-target-edited {:value target})
                                                       (om/set-state! owner :input-expanded false)
                                                       (.focus (om/get-node owner "target-input")))}
                                    target)))))))))

(defn touches->clj [touches]
  (mapv (fn [t]
          {:client-x (aget t "clientX")
           :client-y (aget t "clientY")
           :page-x (aget t "pageX")
           :page-y (aget t "pageY")
           :identifier (aget t "identifier")})
        ;; touches aren't seqable
        (js/Array.prototype.slice.call touches)))

(defn measure [[x1 y1] [x2 y2]]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2)
                (Math/pow (- y2 y1) 2))))

(defn center [[x1 y1] [x2 y2]]
  [(/ (+ x2 x1) 2)
   (/ (+ y2 y1) 2)])

(defn svg-canvas [payload owner opts]
  (reify
    om/IInitState (init-state [_]
                    ;; use an atom for performance, don't want to constantly
                    ;; re-render when we set-state!
                    {:touches (atom nil)})
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            camera (:camera payload)
            in-progress? (settings/drawing-in-progress? payload)
            subs-layers (reduce (fn [acc [id subscriber]]
                                  (if-let [layers (seq (:layers subscriber))]
                                    (concat acc (map (fn [layer]
                                                       (assoc layer
                                                              :layer/end-x (:layer/current-x layer)
                                                              :layer/end-y (:layer/current-y layer)
                                                              :subscriber-color (:color subscriber)
                                                              :layer/stroke (apply str "#" (take 6 id))))
                                                     layers))
                                    acc))
                                [] (:subscribers payload))]
        (dom/svg #js {:width "100%"
                      :height "100%"
                      :id "svg-canvas"
                      :xmlns "http://www.w3.org/2000/svg"
                      :style #js {:top    0
                                  :left   0
                                  :cursor (state->cursor payload)}
                      :onTouchStart (fn [event]
                                      (let [touches (.-touches event)]
                                        (cond
                                          (= (.-length touches) 1)
                                          (do
                                            (.preventDefault event)
                                            (js/clearInterval (om/get-state owner :touch-timer))
                                            (om/set-state! owner :touch-timer (js/setTimeout #(cast! :menu-opened) 500))
                                            ((:handle-mouse-down handlers) (aget touches "0")))

                                          (= (.-length touches) 2)
                                          (do
                                            (js/clearInterval (om/get-state owner :touch-timer))
                                            (cast! :cancel-drawing)
                                            (reset! (om/get-state owner :touches) (touches->clj touches)))

                                          :else (js/clearInterval (om/get-state owner :touch-timer)))))
                      :onTouchEnd (fn [event]
                                    (.preventDefault event)
                                    (js/clearInterval (om/get-state owner :touch-timer))
                                    (if (= (.-length (aget event "changedTouches")) 1)
                                      ((:handle-mouse-up handlers) event)
                                      (cast! :cancel-drawing)))
                      :onTouchMove (fn [event]
                                     (let [touches (.-touches event)]
                                       (cond (= (.-length touches) 1)
                                             (do
                                               (.preventDefault event)
                                               (.stopPropagation event)
                                               (js/clearInterval (om/get-state owner :touch-timer))
                                               ((:handle-mouse-move! handlers) (aget touches "0")))

                                             (= (.-length touches) 2)
                                             (do
                                               (.preventDefault event)
                                               (.stopPropagation event)
                                               (js/clearInterval (om/get-state owner :touch-timer))
                                               (when in-progress?
                                                 (cast! :cancel-drawing))
                                               (let [touches-atom (om/get-state owner :touches)
                                                     [p-a p-b :as previous-touches] @touches-atom
                                                     [c-a c-b :as current-touches] (touches->clj touches)

                                                     p-center (center [(:page-x p-a) (:page-y p-a)]
                                                                      [(:page-x p-b) (:page-y p-b)])

                                                     c-center (center [(:page-x c-a) (:page-y c-a)]
                                                                      [(:page-x c-b) (:page-y c-b)])

                                                     drift-x (- (first c-center) (first p-center))
                                                     drift-y (- (second c-center) (second p-center))

                                                     spread (- (measure [(:page-x p-a) (:page-y p-a)]
                                                                        [(:page-x p-b) (:page-y p-b)])
                                                               (measure [(:page-x c-a) (:page-y c-a)]
                                                                        [(:page-x c-b) (:page-y c-b)]))]
                                                 (reset! touches-atom current-touches)
                                                 (om/transact! payload (fn [state]
                                                                         (-> state
                                                                           (cameras/set-zoom (partial + (* -0.004 spread)))
                                                                           (cameras/move-camera drift-x drift-y))))))
                                             :else nil)))
                      :onMouseDown (fn [event]
                                     ((:handle-mouse-down handlers) event)
                                     (.stopPropagation event))
                      :onMouseUp (fn [event]
                                   ((:handle-mouse-up handlers) event)
                                   (.stopPropagation event))
                      :onWheel (fn [event]
                                 (let [dx     (- (aget event "deltaX"))
                                       dy     (aget event "deltaY")]
                                   (om/transact! payload (fn [state]
                                                           (let [camera (cameras/camera state)]
                                                             (if (aget event "altKey")
                                                               (cameras/set-zoom state (partial + (* -0.002 dy)))
                                                               (cameras/move-camera state dx (- dy)))))))
                                 (utils/stop-event event))}
                 (dom/defs nil
                   (dom/pattern #js {:id           "small-grid"
                                     :width        (str (cameras/grid-width camera))
                                     :height       (str (cameras/grid-height camera))
                                     :patternUnits "userSpaceOnUse"}
                                (dom/path #js {:d           (str "M " (cameras/grid-width camera) " 0 L 0 0 0 " (cameras/grid-width camera))
                                               :fill        "none"
                                               :stroke      "gray"
                                               :strokeWidth "0.5"}))
                   (dom/pattern #js {:id               "grid"
                                     :width            (str (* 10 (cameras/grid-width camera)))
                                     :height           (str (* 10 (cameras/grid-height camera)))
                                     :patternUnits     "userSpaceOnUse"
                                     :patternTransform (str "translate(" (:x camera) "," (:y camera) ")")}
                                (dom/rect #js {:width  (str (* 10 (cameras/grid-width camera)))
                                               :height (str (* 10 (cameras/grid-height camera)))
                                               :fill   "url(#small-grid)"})
                                (dom/path #js {:d           (str "M " (str (* 10 (cameras/grid-width camera))) " 0 L 0 0 0 " (str (* 10 (cameras/grid-width camera))))
                                               :fill        "none"
                                               :stroke      "gray"
                                               :strokeWidth "1"})))
                 (when (cameras/show-grid? payload)
                   (dom/rect #js {:id     "background-grid"
                                  :width  "100%"
                                  :height "100%"
                                  :fill   "url(#grid)"}))

                 (dom/g
                  #js {:transform (cameras/->svg-transform camera)}
                  (om/build cursors (select-keys payload [:subscribers :client-id]))
                  (om/build svg-layers (assoc (select-keys payload [:selected-eids :document/id])
                                              :editing-eids (set (concat (when (or (settings/drawing-in-progress? payload)
                                                                                   (settings/moving-drawing? payload))
                                                                           (concat [(:db/id (settings/drawing payload))]
                                                                                   (map :db/id (get-in payload [:drawing :layers]))))
                                                                         (remove nil? (map :db/id subs-layers))))
                                              :tool (get-in payload state/current-tool-path)))
                  (om/build subscriber-layers {:layers subs-layers})
                  (when (and (settings/drawing-in-progress? payload)
                             (= :layer.type/text (get-in payload [:drawing :layers 0 :layer/type])))
                    (om/build text-input (get-in payload [:drawing :layers 0])))

                  (when (get-in payload [:layer-properties-menu :opened?])
                    (om/build layer-properties {:layer (get-in payload [:layer-properties-menu :layer])
                                                :x (get-in payload [:layer-properties-menu :x])
                                                :y (get-in payload [:layer-properties-menu :y])}))

                  (when-let [sels (cond
                                    (settings/moving-drawing? payload) (remove #(= :layer.type/group (:layer/type %))
                                                                               (settings/drawing payload))
                                    (= :layer.type/text (get-in payload [:drawing :layers 0 :layer/type])) nil
                                    (settings/drawing-in-progress? payload) (settings/drawing payload)
                                    :else nil)]
                    (apply dom/g #js {:className "layers"}
                           (map (fn [sel]
                                  (let [sel (if (:force-even? sel)
                                              (layers/force-even sel)
                                              sel)
                                        sel (merge sel
                                                   {:layer/end-x (:layer/current-x sel)
                                                    :layer/end-y (:layer/current-y sel)}
                                                   (when (or (settings/moving-drawing? payload)
                                                             (not= :layer.type/text (:layer/type sel)))
                                                     {:className "layer-in-progress"})
                                                   (when (= :layer.type/group (:layer/type sel))
                                                     {:layer/type :layer.type/rect
                                                      :className "layer-in-progress selection"
                                                      :strokeDasharray "2,3"}))]
                                    (svg-element #{} (assoc sel :key (str (:db/id sel) "-in-progress")))))
                                sels)))))))))
