(ns frontend.clipboard
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [cljs.core.async :as async :refer [>! <! alts! put! chan sliding-buffer close!]]
            [cljs-http.client :as http]
            [datascript :as d]
            [frontend.camera :as cameras]
            [frontend.datascript :as ds]
            [frontend.sente :as sente]
            [frontend.models.layer :as layer-model]
            [frontend.svg :as svg]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [goog.dom.xml :as xml]
            [goog.string :as gstring]
            [goog.style]
            [hiccups.runtime :as hiccupsrt]
            [taoensso.sente])
  (:require-macros [hiccups.core :as hiccups]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

;; TODO: all of this rendering code is shared with the backend. Obviously, this
;;       isn't the best place for it, but no great ideas for how to move it.
;;       Waiting on http://dev.clojure.org/display/design/Feature+Expressions
(defn normalized-abs-coords [coords]
  (cond-> coords
          (> (:layer/start-x coords) (or (:layer/current-x coords)
                                         (:layer/end-x coords)))
          (assoc
              :layer/start-x (or (:layer/current-x coords)
                                 (:layer/end-x coords))
              :layer/current-x (:layer/start-x coords)
              :layer/end-x (:layer/start-x coords))

          (> (:layer/start-y coords) (or (:layer/current-y coords)
                                         (:layer/end-y coords)))
          (assoc
              :layer/start-y (or (:layer/current-y coords)
                                 (:layer/end-y coords))
              :layer/current-y (:layer/start-y coords)
              :layer/end-y (:layer/start-y coords))))

(defn rect-width [rect]
  (- (:layer/end-x rect) (:layer/start-x rect)))

(defn rect-height [rect]
  (- (:layer/end-y rect) (:layer/start-y rect)))

(defmulti abs-bounding-box :layer.type)

(defmethod abs-bounding-box :layer.type/rect
  [rect]
  (let [w        (rect-width rect)
        h        (rect-height rect)
        scaled-w (* (get-in rect [:transforms :scale :x] 1) w)
        scaled-h (* (get-in rect [:transforms :scale :y] 1) h)
        cx       (+ (:layer/start-x rect) (/ w 2))
        cy       (+ (:layer/start-y rect) (/ h 2))
        sx       (- cx (/ scaled-w 2))
        sy       (- cy (/ scaled-h 2))
        ex       (+ cx (/ scaled-w 2))
        ey       (+ cy (/ scaled-h 2))]
    {:layer/start-x sx
     :layer/start-y sy
     :layer/end-x   ex
     :layer/end-y   ey}))

(defn make-layer [entity-id document-id x y]
  {:db/id              entity-id
   :document/id        document-id
   :layer/type         :layer.type/rect
   :layer/start-x      x
   :layer/start-y      y
   :layer/end-x        x
   :layer/end-y        y
   :layer/fill         "white"
   :layer/stroke-width 1
   :layer/stroke-color "black"
   :layer/name         "placeholder"
   :layer/opacity      1
   :entity/type        :layer})


(defn points->path [points]
  (str "M" (str/join " " (map (fn [p] (str (:rx p) " " (:ry p))) points))))

(defn layer->svg-rect [layer {:keys [invert-colors?]}]
  (let [layer (normalized-abs-coords layer)]
    {:x             (:layer/start-x layer)
     :y             (:layer/start-y layer)
     :width         (- (or (:layer/current-x layer)
                           (:layer/end-x layer)) (:layer/start-x layer))
     :height        (- (or (:layer/current-y layer)
                           (:layer/end-y layer)) (:layer/start-y layer))
     :fill          "none"
     :key           (:layer/id layer)
     :stroke        (if invert-colors? "#ccc" "black")
     :stroke-width   2
     :vector-effect  "non-scaling-stroke"
     :rx            (:layer/rx layer)
     :ry            (:layer/ry layer)     }))

(defn layer->svg-text [layer {:keys [invert-colors?]}]
  {:x (:layer/start-x layer)
   :y (:layer/start-y layer)
   :fill (if invert-colors? "#ccc" "black")
   :stroke-width 0
   :font-family (:layer/font-family layer "Helvetica")
   :font-size   (:layer/font-size layer 20)})

(defn layer->svg-line [layer {:keys [invert-colors?]}]
  {:x1          (:layer/start-x layer)
   :y1          (:layer/start-y layer)
   :x2          (:layer/end-x layer)
   :y2          (:layer/end-y layer)
   :stroke (if invert-colors? "#ccc" "black")
   :stroke-width 2
   :vector-effect "non-scaling-stroke"})

(defn layer->svg-path [layer {:keys [invert-colors?]}]
  {:d (:layer/path layer)
   :stroke (if invert-colors? "#ccc" "black")
   :fill "none"
   :stroke-width 2
   :vector-effect "non-scaling-stroke"})


(defmulti svg-element (fn [layer opts] (:layer/type layer)))

(defmethod svg-element :default
  [layer opts]
  (js/Rollbar.error "Called clipboard/svg-element for non-drawing" layer)
  nil)

(defmethod svg-element :layer.type/rect
  [layer opts]
  [:rect (layer->svg-rect layer opts)])

(defmethod svg-element :layer.type/text
  [layer opts]
  [:text (layer->svg-text layer opts) (goog.string/htmlEscape (:layer/text layer))])

(defmethod svg-element :layer.type/line
  [layer opts]
  [:line (layer->svg-line layer opts)])

(defmethod svg-element :layer.type/path
  [layer opts]
  [:path (layer->svg-path layer opts)])

;; TODO: take path width/height into account
(defn render-layers [{:keys [layers] :as layer-data} & {:keys [invert-colors?]}]
  (let [layers (filter #(not= :layer.type/group (:layer/type %)) layers)
        start-xs (remove #(js/isNaN %) (map :layer/start-x layers))
        start-ys (remove #(js/isNaN %) (map :layer/start-y layers))
        end-xs (remove #(js/isNaN %) (map :layer/end-x layers))
        end-ys (remove #(js/isNaN %) (map :layer/end-y layers))
        xs (or (seq (concat start-xs end-xs)) [0])
        ys (or (seq (concat start-ys end-ys)) [0])
        min-x (apply min xs)
        min-y (apply min ys)
        max-x (apply max xs)
        max-y (apply max ys)
        width (+ 2 (js/Math.abs (- max-x min-x))) ;; room for stroke
        height (+ 2 (js/Math.abs (- max-y min-y)))
        offset-top (- 1 min-y)
        offset-left (- 1 min-x)]
    (hiccups/html
     [:svg (merge
            {:viewBox (str "0 0 " width " " height)
             :width width
             :height height
             :xmlns "http://www.w3.org/2000/svg"
             :xmlns:xlink "http://www.w3.org/1999/xlink"
             :version "1.1"}
            (when invert-colors?
              {:style "background: #333"}))
      ;; hack to make pngs work
      (when invert-colors?
        [:rect {:width "100%" :height "100%" :fill "#333"}])
      [:metadata (pr-str (assoc layer-data
                           :width width
                           :height height
                           :min-x min-x
                           :min-y min-y))]
      [:g {:transform (gstring/format "translate(%s, %s)" offset-left offset-top)}
       (map #(svg-element % {:invert-colors? invert-colors?}) layers)]])))

(defn upload-clipboard-to-s3 [sente-state rendered-layers]
  (sente/send-msg sente-state [:cust/fetch-clipboard-s3-url]
                  5000
                  (fn [reply]
                    (when (taoensso.sente/cb-success? reply)
                      (go (let [res (async/<! (http/put (:url reply)
                                                        {:body rendered-layers
                                                         :headers {"Content-Type" "image/svg+xml"}}))]
                            (sente/send-msg sente-state [:cust/store-clipboard {:key (:key reply)}])))))))

(defn handle-copy! [app-state e]
  (when (let [target (.-target e)]
          ;; don't apply if input or textarea is focused, unless the copy-hack
          ;; element is selected (see components.canvas)
          (or (= "_copy-hack" (.-id target))
              (and (str/blank? (js/window.getSelection))
                   (not (contains? #{"input" "textarea"} (str/lower-case (.-tagName target)))))))
    (.preventDefault e)
    (when-let [layers (seq (remove
                            #(= :layer.type/group (:layer/type %))
                            (map #(dissoc (ds/touch+ (d/entity @(:db app-state) %))
                                          :unsaved)
                                 (get-in app-state [:selected-eids :selected-eids]))))]
      (let [rendered-layers (render-layers {:layers layers})]
        (.setData (.-clipboardData e) "text" rendered-layers)
        (upload-clipboard-to-s3 (:sente app-state) rendered-layers)))))

(defn parse-pasted [pasted-data]
  (some->> pasted-data
      (re-find #"<metadata>(.+)</metadata>")
    last
    reader/read-string))

(defn handle-paste! [app-state e]
  (when (let [target (.-target e)]
          ;; don't apply if input or textarea is focused, unless the copy-hack
          ;; element is selected (see components.canvas)
          (or (= "_copy-hack" (.-id target))
              (not (contains? #{"input" "textarea"} (str/lower-case (.-tagName target))))))
    (when-let [layer-data (some->> (.getData (.-clipboardData e) "text")
                            (parse-pasted))]
      (let [canvas-size (utils/canvas-size)]
        (put! (get-in app-state [:comms :controls]) [:layers-pasted (assoc layer-data :canvas-size canvas-size)])))))
