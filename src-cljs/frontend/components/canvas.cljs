(ns frontend.components.canvas
  (:require [cljs.core.async :refer [put!]]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript.core :as d]
            [frontend.auth :as auth]
            [frontend.camera :as cameras]
            [frontend.clipboard :as clipboard]
            [frontend.colors :as colors]
            [frontend.components.common :as common]
            [frontend.components.context :as context]
            [frontend.components.key-queue :as keyq]
            [frontend.components.mouse :as mouse]
            [frontend.cursors :as cursors]
            [frontend.db :as fdb]
            [frontend.datascript :as ds]
            [frontend.keyboard :as keyboard]
            [frontend.layers :as layers]
            [frontend.models.layer :as layer-model]
            [frontend.models.issue :as issue-model]
            [frontend.settings :as settings]
            [frontend.state :as state]
            [frontend.svg :as svg]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.font-map :as font-map]
            [goog.dom]
            [goog.dom.forms :as gforms]
            [goog.labs.userAgent.browser :as ua-browser]
            [goog.string :as gstring]
            [goog.string.linkify]
            [goog.style]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

;; layers are always denominated in absolute coordinates
;; transforms are applied to handle panning and zooming

(defmulti svg-element (fn [layer] (:layer/type layer)))

(defmethod svg-element :default
  [layer]
  (utils/mlog "No svg element for " layer))

(defn maybe-add-selected [svg-layer layer]
  (if (:selected? layer)
    (update-in svg-layer [:className] #(str % " selected"))
    svg-layer))

(defn maybe-add-deleted [svg-layer layer]
  (if (:layer/deleted layer)
    (update-in svg-layer [:className] #(str % " layer-deleted"))
    svg-layer))

(defn maybe-add-unsaved [svg-layer layer]
  (if (:unsaved layer)
    (update-in svg-layer [:className] #(str % " unsaved "))
    svg-layer))

(defn maybe-add-classes [svg-layer layer]
  (-> svg-layer
    (maybe-add-selected layer)
    (maybe-add-deleted layer)
    (maybe-add-unsaved layer)))

(defmethod svg-element :layer.type/rect
  [layer]
  (-> (svg/layer->svg-rect layer)
    (maybe-add-classes layer)
    (clj->js)
    (dom/rect)))

(defn fontify [text]
  (let [matches (map last (re-seq #":(fa-[^:]+):" text))
        ;; may need to add [""], split can return empty array
        parts (or (seq (str/split text #":fa-[^:]+:")) [""])]
    (loop [parts parts
           matches matches
           acc []]
      (let [res (concat acc
                        [(dom/tspan nil (first parts))]
                        (when (first matches)
                          (if-let [unicode (font-map/class->unicode (first matches))]
                            [(dom/tspan #js {:fontFamily "FontAwesome"}
                               unicode)]
                            [(dom/tspan nil (str ":" (first matches) ":"))])))]
        (if (next parts)
          (recur (next parts) (next matches) res)
          res)))))

(defmethod svg-element :layer.type/text
  [layer]
  (let [text-props (svg/layer->svg-text layer)]
    (-> text-props
      (maybe-add-classes layer)
      (clj->js)
      (#(apply dom/text % (reduce (fn [tspans text]
                                    (conj tspans (apply dom/tspan #js {:dy (if (seq tspans) "1em" "0")
                                                                       :x (:x text-props)}
                                                        (fontify text))))
                                  [] (str/split (:layer/text layer) #"\n")))))))

;; debug method for showing text with bounding box
#_(defmethod svg-element :layer.type/text
  [layer]
  (let [text-props (svg/layer->svg-text layer)]
    (dom/g nil
      (svg-element (assoc layer
                          :layer/type :layer.type/rect
                          :key (str (:db/id layer) "-text-bbox")
                          :style #js {:stroke "yellow"}))
      (-> text-props
        (maybe-add-classes layer)
        (clj->js)
        (#(apply dom/text % (reduce (fn [tspans text]
                                      (conj tspans (apply dom/tspan #js {:dy (if (seq tspans) "1em" "0")
                                                                         :x (:x text-props)}
                                                          (fontify text))))
                                    [] (str/split (:layer/text layer) #"\n"))))))))

(defmethod svg-element :layer.type/line
  [layer]
  (-> (svg/layer->svg-line layer)
    (merge layer)
    (update-in [:className] #(str % " shape-layer"))
    (maybe-add-classes layer)
    (clj->js)
    (dom/line)))

(defmethod svg-element :layer.type/path
  [layer]
  (-> (merge (dissoc layer :points) (svg/layer->svg-path layer))
    (update-in [:className] #(str % " shape-layer"))
    (maybe-add-classes layer)
    (clj->js)
    (dom/path)))

(defmethod svg-element :layer.type/group
  [layer]
  (print "Nothing to do for groups, yet."))

(defn handles [layer owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas Handles")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner [:cast!])
            width (js/Math.abs (- (:layer/start-x layer) (:layer/end-x layer)))
            height (js/Math.abs (- (:layer/start-y layer) (:layer/end-y layer)))
            ;; easier than calcing width, b/c we can just multiply by 2
            handle-offset (max 1
                               (min 4
                                    (int (/ (Math/sqrt (+ (* width width) (* height height)))
                                            6))))]
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

(defn layer-group [{:keys [layer-id tool selected? part-of-group? live?]} owner]
  (reify
    om/IDisplayName (display-name [_] "Layer Group")
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-entity-listener (om/get-shared owner :db)
                               layer-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 (om/refresh! owner)))
      (fdb/add-attribute-listener (om/get-shared owner :db)
                                  :layer/ui-id
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (let [targets (reduce (fn [acc datom]
                                                            (if (= :layer/ui-id (:a datom))
                                                              (conj acc (:v datom))
                                                              acc))
                                                          #{} (:tx-data tx-report))
                                          db @(om/get-shared owner :db)]
                                      (when (contains? targets
                                                       (:layer/ui-target (d/entity db layer-id)))
                                        (om/refresh! owner))))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :db)
                                  layer-id
                                  (om/get-state owner :listener-key))
      (fdb/remove-attribute-listener (om/get-shared owner :db)
                                     :layer/ui-id
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            layer (ds/touch+ (d/entity @db layer-id))
            external-target? (and (:layer/ui-target layer)
                                  (= (:layer/ui-target layer)
                                     (goog.string.linkify/findFirstUrl (:layer/ui-target layer))))
            invalid? (and (:layer/ui-target layer)
                          (and (not (pos? (layer-model/count-by-ui-id @db (:layer/ui-target layer))))
                               (not external-target?)))
            show-handles? (and (not part-of-group?)
                               selected?
                               (not (:unsaved layer))
                               (contains? #{:layer.type/rect :layer.type/line} (:layer/type layer))
                               (or (= :select tool)
                                   (and (= :circle tool) (layers/circle? layer))
                                   (and (= :line tool) (= :layer.type/line (:layer/type layer)))
                                   (and (= :rect tool) (not (layers/circle? layer)))))]
        (dom/g #js {:className (str "layer "
                                    (when invalid? "invalid "))
                    :key (:db/id layer)}

          (when (and show-handles? (layers/circle? layer))
            (let [layer (layers/normalized-abs-coords layer)]
              (dom/rect #js {:className "handle-outline"
                             :x (:layer/start-x layer)
                             :y (:layer/start-y layer)
                             :width (- (:layer/end-x layer) (:layer/start-x layer))
                             :height (- (:layer/end-y layer) (:layer/start-y layer))
                             :fill "none"
                             :strokeWidth 1})))

          (svg-element (assoc
                        layer
                        :selected? selected?
                        :className (str "selectable-layer layer-handle "
                                        (when (and (= :layer.type/text (:layer/type layer))
                                                   (= :text tool)) "editable ")
                                        (when (:layer/signup-button layer)
                                          " signup-layer"))
                        :key (str "selectable-" (:db/id layer))
                        :onMouseDown
                        #(do
                           (.stopPropagation %)
                           (let [group? part-of-group?]

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
                                      {:x (first (cameras/screen-event-coords %))
                                       :y (second (cameras/screen-event-coords %))})

                               (and (.-altKey %) (not group?))
                               (cast! :layer-duplicated
                                      {:layer layer
                                       :x (first (cameras/screen-event-coords %))
                                       :y (second (cameras/screen-event-coords %))})

                               (and (.-shiftKey %) selected?)
                               (cast! :layer-deselected {:layer layer})


                               group?
                               (cast! :group-selected {:x (first (cameras/screen-event-coords %))
                                                       :y (second (cameras/screen-event-coords %))})

                               :else
                               (cast! :layer-selected {:layer layer
                                                       :x (first (cameras/screen-event-coords %))
                                                       :y (second (cameras/screen-event-coords %))
                                                       :append? (.-shiftKey %)}))))))
          (when-not (= :layer.type/text (:layer/type layer))
            (svg-element (assoc layer
                                :selected? selected?
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
            (svg-element (assoc layer
                                :selected? selected?
                                :padding 4 ;; only works for rects right now
                                :onClick (when (:layer/signup-button layer)
                                           #(do
                                              (.preventDefault %)
                                              (set! js/window.location (auth/auth-url :source "prcrsr-bot-drawing"))))
                                :onMouseDown #(when (= tool :select)
                                                (.stopPropagation %)
                                                (cond
                                                  (or (= (.-button %) 2)
                                                      (and (= (.-button %) 0) (.-ctrlKey %)))
                                                  (when-not (:layer/signup-button layer)
                                                    (cast! :layer-properties-opened {:layer layer
                                                                                     :x (first (cameras/screen-event-coords %))
                                                                                     :y (second (cameras/screen-event-coords %))}))

                                                  selected?
                                                  (cast! :group-selected
                                                         {:x (first (cameras/screen-event-coords %))
                                                          :y (second (cameras/screen-event-coords %))})

                                                  :else
                                                  (when-not (:layer/signup-button layer)
                                                    (cast! :layer-target-clicked
                                                           {:ui-id (:layer/ui-target layer)
                                                            :canvas-size (utils/canvas-size)}))))
                                :onTouchStart (fn [event]
                                                (when (= (.-length (.-touches event)) 1)
                                                  (utils/stop-event event)
                                                  (cast! :layer-target-clicked
                                                         {:ui-id (:layer/ui-target layer)
                                                          :canvas-size (utils/canvas-size)})))

                                :className (str "action interactive-fill "
                                                (when part-of-group?
                                                  "selected-group ")
                                                (when invalid?
                                                  "invalid ")
                                                (when external-target?
                                                  "external ")
                                                (when (:layer/signup-button layer)
                                                  " signup-layer"))
                                :key (str "action-" (:db/id layer))))))))))

(defn svg-layers [{:keys [tool] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "SVG Layers")
    om/IInitState
    (init-state [_]
      {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-attribute-listener (om/get-shared owner :db)
                                  :layer/name
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :db)
                                     :layer/name
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            selected-eids (:selected-eids (cursors/observe-selected-eids owner))
            selected-group? (< 1 (count selected-eids))
            sub-eids (:entity-ids (cursors/observe-subscriber-entity-ids owner))
            editing-eids (:editing-eids (cursors/observe-editing-eids owner))
            editing-eids (set/union sub-eids editing-eids)
            live-ids (set/difference (set (map :e (d/datoms @db :aevt :layer/ui-target)))
                                     editing-eids)
            idle-ids (set/difference (set (map :e (d/datoms @db :aevt :layer/name)))
                                     live-ids
                                     editing-eids)]
        ;; TODO: this should probably be split into a couple of components
        (dom/g #js {:className (if (= :select tool)
                                 "interactive"
                                 "static")}
          (apply dom/g #js {:className "layers idle"}
                 (om/build-all layer-group (mapv (fn [id]
                                                   (let [selected? (contains? selected-eids id)
                                                         part-of-group? (and selected? selected-group?)]
                                                     {:live? false
                                                      :tool tool
                                                      :selected? selected?
                                                      :part-of-group? part-of-group?
                                                      :layer-id id}))
                                                 (sort idle-ids))
                               {:key :layer-id}))
          (apply dom/g #js {:className "layers live"}
                 (om/build-all layer-group (mapv (fn [id]
                                                   (let [selected? (contains? selected-eids id)
                                                         part-of-group? (and selected? selected-group?)]
                                                     {:live? true
                                                      :tool tool
                                                      :selected? selected?
                                                      :part-of-group? part-of-group?
                                                      :layer-id id}))
                                                 (sort live-ids))
                               {:key :layer-id})))))))

(defn issue [{:keys [document-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Issue layer")
    om/IInitState (init-state [_] {:layer-source (utils/squuid)
                                   :listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-attribute-listener (om/get-shared owner :issue-db)
                                  :issue/document
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner)))
      (fdb/add-attribute-listener (om/get-shared owner :issue-db)
                                  :issue/title
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :issue-db)
                                     :issue/document
                                     (om/get-state owner :listener-key))
      (fdb/remove-attribute-listener (om/get-shared owner :issue-db)
                                     :issue/title
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! issue-db cust]} (om/get-shared owner)
            font-size 24]
        (dom/g nil
          (when-let [issue (issue-model/find-by-doc-id @issue-db document-id)]
            (dom/g #js {:className "layer"}
              (svg-element {:layer/type :layer.type/text
                            :layer/text (gstring/truncate (:issue/title issue) 40)
                            :layer/start-x 100
                            :layer/end-x 100
                            :layer/start-y 100
                            :layer/end-y 100
                            :layer/font-size font-size
                            :key "issue-title"
                            :className "text-layer issue-layer"
                            :onClick #(cast! :issue-layer-clicked {:frontend/issue-id (:frontend/issue-id issue)})})
              (svg-element {:layer/type :layer.type/text
                            :layer/text (if (= (:cust/email cust) (:issue/author issue))
                                          "Draw anything here to help illustrate your idea."
                                          "(click to view issue)")
                            :layer/start-x 100
                            :layer/end-x 100
                            :layer/start-y (+ 100 16)
                            :layer/end-y (+ 100 16)
                            :layer/font-size 12
                            :key "doc-instructions"
                            :className "text-layer issue-layer"
                            :onClick #(cast! :issue-layer-clicked {:frontend/issue-id (:frontend/issue-id issue)})}))))))))

(defn subscriber-cursor-icon [tool]
  (case (name tool)
    "pen" :crosshair
    "line" :crosshair
    "rect" :crosshair
    "circle" :crosshair

    "text" :ibeam

    "select" :cursor))

(defn volume-icon [subscriber color-class]
  (let [level (get-in subscriber [:recording :media-stream-volume] 0)
        icon (common/volume-icon-kw level)]
    (common/svg-icon icon
                     {:svg-props {:height 16 :width 16
                                  :className "mouse-tool"
                                  :x (- (first (:mouse-position subscriber)) (- 16))
                                  :y (- (last (:mouse-position subscriber)) 8)
                                  :key (:client-id subscriber)}
                      :path-props {:className color-class}})))

(defn cursor [{:keys [subscriber uuid->cust]} owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas Cursor")
    om/IRender

    (render [_]
      (if (and (:tool subscriber)
               (:show-mouse? subscriber)
               (:mouse-position subscriber))
        (let [color-class (name (colors/find-color uuid->cust
                                                   (:cust/uuid subscriber)
                                                   (:client-id subscriber)))]
          (dom/g nil
            (dom/g {:key "recording"}
              (when (:recording subscriber)
                (volume-icon subscriber color-class)))
            (common/svg-icon (subscriber-cursor-icon (:tool subscriber))
                             {:svg-props {:height 16 :width 16
                                          :className "mouse-tool"
                                          :x (- (first (:mouse-position subscriber)) 8)
                                          :y (- (last (:mouse-position subscriber)) 8)
                                          :key (:client-id subscriber)}
                              :path-props {:className color-class}})))
        (dom/circle #js {:cx 0 :cy 0 :r 0})))))

(defn cursors [{:keys [client-id uuid->cust]} owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas Cursors")
    om/IRender
    (render [_]
      (let [subscribers (cursors/observe-subscriber-mice owner)]
        (apply dom/g nil
               (for [subscriber (vals (dissoc subscribers client-id))]
                 (om/build cursor {:subscriber subscriber
                                   :uuid->cust uuid->cust}
                           {:react-key (:client-id subscriber)})))))))

(defn single-subscriber-layers [{:keys [subscriber uuid->cust]} owner]
  (reify
    om/IDisplayName (display-name [_] "Single Subscriber Layers")
    om/IRender
    (render [_]
      (let [color-class (name (colors/find-color uuid->cust (:cust/uuid subscriber) (:client-id subscriber)))]
        (apply dom/g nil
               (when-let [relation (:relation subscriber)]
                 (let [layer (:layer relation)
                       layer-center (layers/center layer)]
                   (svg-element (assoc layer
                                       :selected? true
                                       :className color-class
                                       :layer/start-x (first layer-center)
                                       :layer/start-y (second layer-center)
                                       :layer/end-x (:rx relation)
                                       :layer/end-y (:ry relation)
                                       :layer/path (layers/arrow-path layer-center
                                                                      [(:rx relation) (:ry relation)]
                                                                      ;; looks cooler with a larger arrow head
                                                                      :r 15)
                                       :layer/type :layer.type/path
                                       :strokeDasharray "5,5"
                                       :layer/fill "none"
                                       :fillOpacity "0.5"))))
               (mapv (fn [l] (svg-element (merge l {:layer/end-x (:layer/current-x l)
                                                    :layer/end-y (:layer/current-y l)
                                                    :strokeDasharray "5,5"
                                                    :layer/fill "none"
                                                    :fillOpacity "0.5"
                                                    :className color-class
                                                    :key (str (:db/id l) "-subscriber-layer-" (:client-id subscriber))}
                                                 (when (= :layer.type/text (:layer/type l))
                                                   {:layer/stroke "none"}))))
                     (:layers subscriber)))))))

(defn subscribers-layers [{:keys [client-id uuid->cust]} owner]
  (reify
    om/IDisplayName (display-name [_] "Subscribers Layers")
    om/IRender
    (render [_]
      (let [subscribers (cursors/observe-subscriber-layers owner)]
        (apply dom/g nil
               (for [subscriber (vals (dissoc subscribers client-id))]
                 (om/build single-subscriber-layers {:subscriber subscriber
                                                     :uuid->cust uuid->cust} {:react-key (:client-id subscriber)})))))))

(defn text-input [layer owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas Text Input")
    om/IDidMount
    (did-mount [_]
      (.focus (om/get-node owner "input")))
    om/IDidUpdate
    (did-update [_ _ _]
      (.focus (om/get-node owner "input")))
    om/IRender
    (render [_]
      (let [{:keys [cast!]} (om/get-shared owner)
            text-style {:font-size (:layer/font-size layer state/default-font-size)}]
        (dom/g #js {:key "text-input-group"}
          (dom/foreignObject #js {:width "100%"
                                  :height "100%"
                                  :x (:layer/start-x layer)
                                  ;; TODO: defaults for each layer when we create them
                                  :y (- (:layer/start-y layer) (:layer/font-size layer state/default-font-size))}
            (dom/form #js {:className "svg-text-form"
                           :onMouseDown #(.stopPropagation %)
                           :onWheel #(.stopPropagation %)
                           :onSubmit (fn [e]
                                       (cast! :text-layer-finished)
                                       (utils/stop-event e))
                           :onMouseMove (when-not (:moving? layer)
                                          #(.stopPropagation %))
                           :onKeyDown #(cond (= "Enter" (.-key %))
                                             (do (cast! :text-layer-finished)
                                                 (utils/stop-event %))

                                             (= "Escape" (.-key %))
                                             (do (cast! :cancel-drawing)
                                                 (utils/stop-event %))

                                             :else nil)}
                      ;; TODO: experiment with a contentEditable div
                      (dom/input #js {:type "text"
                                      :className "text-layer-input"
                                      ;; Don't let the user accidentally select the text when they're dragging it
                                      :placeholder "Type something..."
                                      :value (or (:layer/text layer) "")
                                      ;; TODO: defaults for each layer when we create them
                                      :style #js {:font-size (:layer/font-size layer state/default-font-size)
                                                  :width (+ 50 (max 160
                                                                    (utils/measure-text-width
                                                                     (or (:layer/text layer) "")
                                                                     (:layer/font-size layer state/default-font-size)
                                                                     (:layer/font-family layer state/default-font-family))))}
                                      :ref "input"
                                      :onChange #(cast! :text-layer-edited {:value (.. % -target -value)})}))))))))

(defn layer-properties [{:keys [layer x y]} owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas Layer Props")
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
    om/IDidUpdate
    (did-update [_ prev-props _]
      (when (not= (:db/id (:layer prev-props)) (:db/id layer))
        (.focus (om/get-node owner "id-input"))))
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
                      sort)
            camera (cursors/observe-camera owner)
            [rx ry] (cameras/point->screen camera x y)]
        (dom/div #js {:className "layer-properties-container"
                      :style #js {:transform (str "translate(" rx "px, " ry "px)"
                                                  " scale(" (min 1 (max 0.7 (:zf camera))) ")")}}
          (dom/div #js {:className "layer-properties"
                        :onMouseDown #(.stopPropagation %)
                        :onMouseUp #(.stopPropagation %)
                        :onWheel #(.stopPropagation %)}
            (dom/div #js {:className "adaptive"}
              (dom/input #js {:type "text"
                              :ref "id-input"
                              :className "layer-property-id"
                              :onClick #(.focus (om/get-node owner "id-input"))
                              :required "true"
                              :value (or (:layer/ui-id layer) "")
                              ;; TODO: defaults for each layer when we create them
                              :onChange #(cast! :layer-ui-id-edited {:value (.. % -target -value)})
                              :onKeyDown #(when (= "Enter" (.-key %))
                                            (cast! :layer-properties-submitted)
                                            (utils/stop-event %))})
              (dom/label #js {:data-label "This shape"
                              :data-placeholder "Name shape."
                              :data-focus "Name it to link other shapes here"}))
            (when-not (= :layer.type/line (:layer/type layer))
              (dom/div #js {:className "adaptive"}
                (dom/input #js {:type "text"
                                :ref "target-input"
                                :className (if (om/get-state owner :input-expanded)
                                             "layer-property-target expanded"
                                             "layer-property-target")
                                :required "true"
                                :value (or (:layer/ui-target layer) "")
                                :onClick #(.focus (om/get-node owner "target-input"))
                                :onChange #(cast! :layer-ui-target-edited {:value (.. % -target -value)})
                                :onKeyDown #(when (= "Enter" (.-key %))
                                              (cast! :layer-properties-submitted)
                                              (utils/stop-event %))})
                (dom/label #js {:data-label "links to"
                                :data-placeholder "Link shape."
                                :data-focus "Link it to a url or another shape"})))
            (when-not (= :layer.type/line (:layer/type layer))
              (when (seq targets)
                (dom/button #js {:className "layer-property-button"
                                 :onClick #(do (om/update-state! owner :input-expanded not)
                                               (utils/stop-event %))}
                            (if (om/get-state owner :input-expanded)
                              (common/icon :dot-menu)
                              (common/icon :ellipsis)))))
            (when-not (= :layer.type/line (:layer/type layer))
              (apply dom/div #js {:className (if (om/get-state owner :input-expanded)
                                               "property-dropdown-targets expanded"
                                               "property-dropdown-targets")}
                     (for [target targets]
                       (dom/a #js {:className "property-dropdown-target"
                                   :role "button"
                                   :onClick #(do (cast! :layer-ui-target-edited {:value target})
                                                 (om/set-state! owner :input-expanded false)
                                                 (.focus (om/get-node owner "target-input")))}
                              target))))))))))

(defn in-progress [{:keys [mouse-down]} owner]
  (reify
    om/IDisplayName (display-name [_] "In Progress Layers")
    om/IRender
    (render [_]
      (let [drawing (cursors/observe-drawing owner)]
        (dom/g nil
          (when (and (:in-progress? drawing)
                     (= :layer.type/text (get-in drawing [:layers 0 :layer/type])))
            (om/build text-input (assoc (get-in drawing [:layers 0])
                                        :moving? mouse-down)))

          (when-let [sels (cond
                            (:moving? drawing) (:layers drawing)
                            (= :layer.type/text (get-in drawing [:layers 0 :layer/type])) nil
                            (:in-progress? drawing) (:layers drawing)
                            :else nil)]
            (apply dom/g #js {:className "layers"}
                   (map (fn [sel]
                          (let [sel (if (:force-even? sel)
                                      (layers/force-even sel)
                                      sel)
                                sel (merge sel
                                           {:layer/end-x (:layer/current-x sel)
                                            :layer/end-y (:layer/current-y sel)}
                                           (when (or (:moving? drawing)
                                                     (not= :layer.type/text (:layer/type sel)))
                                             {:className "layer-in-progress"})
                                           (when (= :layer.type/group (:layer/type sel))
                                             {:layer/type :layer.type/rect
                                              :className "layer-in-progress selection"
                                              :strokeDasharray "2,3"}))]
                            (svg-element (assoc sel :key (str (:db/id sel) "-in-progress")))))
                        sels))))))))

(defn find-arrow-eids [db]
  (reduce (fn [acc d]
            (conj acc (:e d) (:v d)))
          #{} (d/datoms db :aevt :layer/points-to)))

(defn arrow-handle [layer owner]
  (reify
    om/IDisplayName (display-name [_] "Arrow handle")
    om/IWillReceiveProps
    (will-receive-props [_ next-props]
      ;; prevent arrow-hint popping up right after we make a new relation
      (when (and (:selected? next-props)
                 (not (:selected? (om/get-props owner))))
        (om/set-state! owner :mouse-pos nil)))
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            [rx ry] (om/get-state owner :mouse-pos)
            center (layers/center layer)
            text? (keyword-identical? :layer.type/text (:layer/type layer))]
        (dom/g #js {:className "arrow-handle-group"}
          (when (and rx ry)
            (dom/g nil
              (dom/line #js {:className "shape-layer layer-outline arrow-hint"
                             :x1 (first center)
                             :x2 rx
                             :y1 (second center)
                             :y2 ry
                             :markerEnd (utils/absolute-css-hash "arrow-point")})))
          (svg-element (assoc layer
                              :key (str (:db/id layer) "-handler")
                              :className (str "arrow-handle "
                                              (when text? "arrow-outline ")
                                              (when (om/get-state owner :hovered?)
                                                "hovered "))
                              ;; hack to workaround missing hover effect when holding "a"
                              ;; and click->drag from one handle to the other.
                              :onMouseEnter (fn [e]
                                              (om/set-state! owner :hovered? true))

                              :onMouseLeave (fn [e]
                                              (om/set-state! owner :hovered? false))

                              :onMouseMove (fn [e]
                                             (om/set-state! owner
                                                            :mouse-pos
                                                            (apply cameras/screen->point
                                                                   ;; bad idea in general, but without this panning is completely broken
                                                                   (:camera @(om/get-shared owner :_app-state-do-not-use))
                                                                   (cameras/screen-event-coords e))))
                              :onMouseDown (fn [e]
                                             (utils/stop-event e)
                                             (cast! :layer-relation-mouse-down
                                                    {:layer layer
                                                     :x (first (cameras/screen-event-coords e))
                                                     :y (second (cameras/screen-event-coords e))}))
                              :onMouseUp (fn [e]
                                           (utils/stop-event e)
                                           (cast! :layer-relation-mouse-up
                                                  {:dest layer
                                                   :x (first (cameras/screen-event-coords e))
                                                   :y (second (cameras/screen-event-coords e))}))))
          (when-not text?
            (svg-element (assoc layer :className "arrow-outline"
                                :key (str (:db/id layer) "-outline")))))))))

(defn arrows [app owner]
  (reify
    om/IDisplayName (display-name [_] "Arrows")
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                                   :arrow-eids (find-arrow-eids @(om/get-shared owner :db))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   (when (some #(or (contains? (om/get-state owner :arrow-eids) (:e %))
                                    (= :layer/points-to (:a %)))
                               (:tx-data tx-report))
                     (om/set-state! owner :arrow-eids (find-arrow-eids (:db-after tx-report)))))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [db @(om/get-shared owner :db)
            cast! (om/get-shared owner :cast!)
            drawing (cursors/observe-drawing owner)
            drawing-layers (reduce (fn [acc layer]
                                     (assoc acc (:db/id layer) (assoc layer
                                                                      :layer/end-x (:layer/current-x layer)
                                                                      :layer/end-y (:layer/current-y layer))))
                                   {} (:layers drawing))
            subscriber-layers (reduce (fn [acc layer]
                                        (assoc acc (:db/id layer) (assoc layer
                                                                         :layer/end-x (:layer/current-x layer)
                                                                         :layer/end-y (:layer/current-y layer))))
                                      {} (mapcat :layers (vals (cursors/observe-subscriber-layers owner))))
            pointer-datoms (set (concat (map (fn [d] {:e (:e d) :v (:v d)})
                                             (d/datoms db :aevt :layer/points-to))
                                        (mapcat (fn [l]
                                                  (map (fn [p] {:e (:db/id l) :v (:db/id p)})
                                                       (:layer/points-to l)))
                                                (filter :layer/points-to
                                                        (concat (:layers drawing)
                                                                (vals subscriber-layers))))))

            selected-eids (:selected-eids (cursors/observe-selected-eids owner))

            selected-arrows (:selected-arrows (cursors/observe-selected-arrows owner))]
        (dom/g #js {:className "arrows-container"}

          ;; in-progress arrow
          (when (and (:relation-in-progress? drawing)
                     (get-in drawing [:relation :layer]))
            (let [layer (get-in drawing [:relation :layer])
                  layer-center (layers/center layer)]
              (dom/g #js {:className "layer-arrows in-progress"
                          :key "in-progress"}
                (svg-element (assoc layer
                                    :selected? true
                                    :className "shape-layer layer-outline layer-arrow"
                                    :layer/start-x (first layer-center)
                                    :layer/start-y (second layer-center)
                                    :layer/end-x (get-in drawing [:relation :rx])
                                    :layer/end-y (get-in drawing [:relation :ry])
                                    :layer/type :layer.type/path
                                    :layer/path (layers/arrow-path layer-center [(get-in drawing [:relation :rx]) (get-in drawing [:relation :ry])]))))))

          ;; in-progress outlines for shapes
          (when (keyboard/arrow-shortcut-active? app)
            (apply dom/g #js {:className "arrow-handles"
                              :key "arrow-handles"}
                   (for [layer (concat (ds/touch-all '[:find ?t :where [?t :layer/type :layer.type/rect]] db)
                                       (ds/touch-all '[:find ?t :where [?t :layer/type :layer.type/text]] db))]
                     (om/build arrow-handle
                               (assoc layer
                                      :selected? (contains? selected-eids (:db/id layer)))
                               {:react-key (:db/id layer)}))))


          (apply dom/g #js {:className "layer-arrows"
                            :key "layer-arrows"}
                 (for [pointer-datom pointer-datoms
                       :let [origin (or (get drawing-layers (:e pointer-datom))
                                        (get subscriber-layers (:e pointer-datom))
                                        (ds/touch+ (d/entity db (:e pointer-datom))))
                             origin-center (layers/center origin)
                             dest (or (get drawing-layers (:v pointer-datom))
                                      (get subscriber-layers (:v pointer-datom))
                                      (ds/touch+ (d/entity db (:v pointer-datom))))
                             dest-center (layers/center dest)
                             [start-x start-y] (layers/layer-intercept origin dest-center :padding 10)
                             [end-x end-y] (layers/layer-intercept dest origin-center :padding 10)]
                       :when (not (or (= [start-x start-y]
                                         [end-x end-y])
                                      (layers/contains-point? dest [start-x start-y] :padding 10)
                                      (layers/contains-point? origin [end-x end-y] :padding 10)))]
                   (let [selected? (contains? selected-arrows {:origin-id (:db/id origin)
                                                               :dest-id (:db/id dest)})
                         props (-> origin
                                 (assoc :layer/start-x start-x
                                        :layer/start-y start-y
                                        :layer/end-x end-x
                                        :layer/end-y end-y
                                        :layer/type :layer.type/path
                                        :layer/path (layers/arrow-path [start-x start-y] [end-x end-y])
                                        :selected? selected?))]
                     (dom/g #js {:key (str (:db/id origin) "-" (:db/id dest))}
                       (svg-element (assoc props
                                           :className "layer-handle"
                                           :onMouseDown #(do (utils/stop-event %)
                                                             (cast! (if selected? :arrow-deselected :arrow-selected)
                                                                    {:origin origin
                                                                     :dest dest
                                                                     :append? (.-shiftKey %)}))))
                       (svg-element (assoc props
                                           :className "layer-outline")))))))))))

(defn defs [camera]
  (dom/defs nil
    (dom/marker #js {:id "arrow-point"
                     :viewBox "0 0 10 10"
                     :refX 5
                     :refY 5
                     :markerUnits "strokeWidth"
                     :markerWidth 5
                     :markerHeight 5
                     :orient "auto"}
                (dom/path #js {:d "M 0 0 L 10 5 L 0 10 z"}))
    (dom/marker #js {:id "selected-arrow-point"
                     :viewBox "0 0 10 10"
                     :refX 5
                     :refY 5
                     :markerUnits "strokeWidth"
                     :markerWidth 5
                     :markerHeight 5
                     :orient "auto"}
                (dom/path #js {:d "M 0 0 L 10 5 L 0 10 z"}))
    (dom/pattern #js {:id           "small-grid"
                      :width        (str (cameras/grid-width camera))
                      :height       (str (cameras/grid-height camera))
                      :patternUnits "userSpaceOnUse"}
                 (dom/path #js {:d           (str "M " (cameras/grid-width camera) " 0 L 0 0 0 " (cameras/grid-width camera))
                                :className   "grid-lines-small"}))
    (dom/pattern #js {:id               "grid"
                      :width            (str (* 10 (cameras/grid-width camera)))
                      :height           (str (* 10 (cameras/grid-height camera)))
                      :patternUnits     "userSpaceOnUse"
                      :patternTransform (str "translate(" (:x camera) "," (:y camera) ")")}
                 (dom/rect #js {:width  (str (* 10 (cameras/grid-width camera)))
                                :height (str (* 10 (cameras/grid-height camera)))
                                :fill   (utils/absolute-css-hash "small-grid")})
                 (dom/path #js {:d           (str "M " (str (* 10 (cameras/grid-width camera))) " 0 L 0 0 0 " (str (* 10 (cameras/grid-width camera))))
                                :className   "grid-lines-large"}))))

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
  (js/Math.sqrt (+ (js/Math.pow (- x2 x1) 2)
                   (js/Math.pow (- y2 y1) 2))))

(defn center [[x1 y1] [x2 y2]]
  [(/ (+ x2 x1) 2)
   (/ (+ y2 y1) 2)])

(defn svg-canvas [app owner]
  (reify
    om/IDisplayName (display-name [_] "SVG Canvas")
    om/IInitState (init-state [_]
                    ;; use an atom for performance, don't want to constantly
                    ;; re-render when we set-state!
                    {:touches (atom nil)})
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            camera (cursors/observe-camera owner)
            in-progress? (settings/drawing-in-progress? app)
            relation-in-progress? (get-in app [:drawing :relation-in-progress?])
            tool (get-in app state/current-tool-path)
            mouse-down? (get-in app [:mouse-down])
            right-click-learned? (get-in app state/right-click-learned-path)]
        (dom/svg #js {:width "100%"
                      :height "100%"
                      :id "svg-canvas"
                      :xmlns "http://www.w3.org/2000/svg"
                      :key "svg-canvas"
                      :mask (when (and (ua-browser/isFirefox)
                                       (get-in app state/chat-opened-path))
                              (utils/absolute-css-hash "canvas-mask"))
                      :className (str "canvas-frame "
                                      (cond (keyboard/arrow-shortcut-active? app) " arrow-tool "
                                            (keyboard/pan-shortcut-active? app) " pan-tool "
                                            :else (str " tool-" (name tool) " "))
                                      (when (and mouse-down?
                                                 (keyword-identical? :text tool)
                                                 in-progress?)
                                        " tool-text-move ")

                                      (when relation-in-progress?
                                        " relation-in-progress ")

                                      (when-not right-click-learned?
                                        "radial-not-learned"))
                      :onTouchStart (fn [event]
                                      (let [touches (.-touches event)]
                                        (cond
                                          (= (.-length touches) 1)
                                          (do
                                            (.preventDefault event)
                                            (js/clearInterval (om/get-state owner :touch-timer))
                                            (om/set-state! owner :touch-timer (js/setTimeout #(cast! :radial-opened) 500))
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
                                               ((:handle-mouse-move handlers) (aget touches "0")))

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
                                                 (om/transact! camera (fn [camera]
                                                                        (-> camera
                                                                          (cameras/set-zoom c-center
                                                                                            (partial + (* -0.004 spread)))
                                                                          (cameras/move-camera drift-x drift-y))))))
                                             :else nil)))
                      :onMouseDown (fn [event]
                                     ((:handle-mouse-down handlers) event)
                                     (.stopPropagation event))
                      :onMouseUp (fn [event]
                                   ((:handle-mouse-up handlers) event)
                                   (.stopPropagation event))
                      :onMouseMove (fn [event]
                                     ((:handle-mouse-move handlers) event)
                                     (.preventDefault event)
                                     (.stopPropagation event))
                      :onWheel (fn [event]
                                 (let [dx (- (aget event "deltaX"))
                                       dy (aget event "deltaY")]
                                   (om/transact! camera (fn [c]
                                                          (if (or (aget event "altKey")
                                                                  ;; http://stackoverflow.com/questions/15416851/catching-mac-trackpad-zoom
                                                                  ;; ctrl means pinch-to-zoom
                                                                  (aget event "ctrlKey"))
                                                            (cameras/set-zoom c (cameras/screen-event-coords event)
                                                                              (partial + (* -0.002
                                                                                            dy
                                                                                            ;; pinch-to-zoom needs a boost to feel natural
                                                                                            (if (.-ctrlKey event) 10 1))))
                                                            (cameras/move-camera c dx (- dy))))))
                                 (utils/stop-event event))}
                 (defs camera)

                 (when (cameras/show-grid? camera)
                   (dom/rect #js {:id        "background-grid"
                                  :className "grid-lines-pattern"
                                  :width     "100%"
                                  :height    "100%"
                                  :fill      (utils/absolute-css-hash "grid")}))

                 (dom/g
                   #js {:transform (cameras/->svg-transform camera)}
                   (om/build issue {:document-id (:document/id app)} {:react-key "issue"})

                   (om/build cursors {:client-id (:client-id app)
                                      :uuid->cust (get-in app [:cust-data :uuid->cust])}
                             {:react-key "cursors"})

                   (om/build svg-layers {:tool tool
                                         ;; we want this to re-render when doc changes
                                         :document/id (:document/id app)}
                             {:react-key "svg-layers"})

                   (om/build subscribers-layers {:client-id (:client-id app)
                                                 :uuid->cust (get-in app [:cust-data :uuid->cust])}
                             {:react-key "subscribers-layers"})

                   (om/build arrows app {:react-key "arrows"})

                   (om/build in-progress (select-keys app [:mouse-down]) {:react-key "in-progress"})))))))

(defn needs-copy-paste-hack? []
  (not (ua-browser/isChrome)))

(defn copy-paste-hack
  "Creates a special element that we can give focus to on copy and paste"
  [app owner]
  (reify
    om/IDisplayName (display-name [_] "Copy/paste hack")
    om/IRender
    (render [_]
      (let [eids (:selected-eids (cursors/observe-selected-eids owner))
            {:keys [cast! db]} (om/get-shared owner)]
        (dom/textarea #js {:id "_copy-hack"
                           :style #js {:position "fixed"
                                       :top "-100px"
                                       :left "-100px"
                                       :width "0px"
                                       :height "0px"}
                           :onKeyDown #(do (utils/swallow-errors
                                            (let [key-set (keyq/event->key %)]
                                              (when-not (contains? #{#{"ctrl" "v"}
                                                                     #{"ctrl" "c"}
                                                                     #{"meta" "v"}
                                                                     #{"meta" "c"}} key-set)
                                                (.focus (.. % -target -parentNode)))))
                                           true)
                           :onKeyUp #(do (utils/swallow-errors (.focus (.. % -target -parentNode)))
                                         true)
                           ;; need a value here, or Firefox and Safari won't run copy
                           :value "some value"})))))

(defn canvas [app owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas")
    om/IRender
    (render [_]
      (html
       [:div.canvas (merge
                     {:onContextMenu (fn [e]
                                       (.preventDefault e)
                                       (.stopPropagation e))
                      :tabIndex 1
                      :key "canvas"}
                     (when (needs-copy-paste-hack?)
                       ;; gives focus to our copy/paste element, so that we can copy
                       {:onKeyDown #(do (utils/swallow-errors
                                         (let [key-set (keyq/event->key %)
                                               hack-node (goog.dom/getElement "_copy-hack")]
                                           (when (contains? #{#{"ctrl"} #{"meta"}} key-set)
                                             (when-let [hack-node (goog.dom/getElement "_copy-hack")]
                                               (gforms/focusAndSelect hack-node)))))
                                        true)}))
        (when (needs-copy-paste-hack?)
          (om/build copy-paste-hack app {:react-key "copy-paste-hack"}))
        (om/build svg-canvas app {:react-key "svg-canvas"})
        (when (:opened? (:layer-properties-menu app))
          (om/build layer-properties (:layer-properties-menu app)
                    {:react-key "layer-props"}))
        (om/build context/context (utils/select-in app [[:radial]]) {:react-key "context"})
        (om/build mouse/mouse (utils/select-in app [state/right-click-learned-path
                                                    [:keyboard]
                                                    [:keyboard-shortcuts]
                                                    [:mouse-down]
                                                    [:drawing :relation-in-progress?]])
                  {:react-key "mouse"})]))))
