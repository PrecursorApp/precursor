(ns frontend.layers
  (:require [clojure.string :as str]
            [frontend.state :as state]
            [frontend.utils :as utils]
            [frontend.utils.font-map :as font-map]))

(defn layers [state]
  (:shapes state))

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
   :layer/document     document-id
   :layer/type         :layer.type/rect
   :layer/start-x      x
   :layer/start-y      y
   :layer/end-x        x
   :layer/end-y        y
   :layer/fill         "none"
   :layer/stroke-width 1
   :layer/stroke-color "black"
   :layer/name         "placeholder"
   :layer/opacity      1
   :entity/type        :layer})

;; TODO: only the draw-in-progress-drawing function should have to care about layer/current-x
(defmulti force-even (fn [layer] (:layer/type layer)))

(defmethod force-even :default
  [layer]
  layer)

(defmethod force-even :layer.type/rect
  [layer]
  (let [width (js/Math.abs (- (:layer/start-x layer)
                              (or (:layer/current-x layer)
                                  (:layer/end-x layer))))
        height-sign (if (neg? (- (:layer/start-y layer)
                                 (or (:layer/current-y layer)
                                     (:layer/end-y layer))))
                      1
                      -1)
        end-y (+ (:layer/start-y layer) (* height-sign width))]
    (-> layer
        (assoc :layer/current-y end-y
               :layer/end-y end-y)
        (#(if (:layer/rx %)
            (assoc % :layer/ry (:layer/rx %))
            %)))))

(defmethod force-even :layer.type/line
  [layer]
  (let [width (- (:layer/start-x layer)
                 (or (:layer/current-x layer)
                     (:layer/end-x layer)))
        width-sign (if (neg? width) 1 -1)
        height (- (:layer/start-y layer)
                  (or (:layer/current-y layer)
                      (:layer/end-y layer)))
        height-sign (if (neg? height) 1 -1)
        length (js/Math.sqrt (+ (* width width)
                                (* height height)))
        theta (js/Math.asin (/ (Math/abs height) length))
        pi-over-4 (/ js/Math.PI 4)
        even-theta (* pi-over-4 (js/Math.round (/ theta pi-over-4)))
        new-width (* (js/Math.cos even-theta) length width-sign)
        new-height (* (js/Math.sin even-theta) length height-sign)]
    (if (and (zero? height)
             (zero? width))
      layer
      (assoc layer
        :layer/current-x (+ (:layer/start-x layer) new-width)
        :layer/end-x (+ (:layer/start-x layer) new-width)
        :layer/current-y (+ (:layer/start-y layer) new-height)
        :layer/end-y (+ (:layer/start-y layer) new-height)))))

(defmulti endpoints :layer/type)

(defmethod endpoints :layer.type/rect
  [layer]
  (for [x ((juxt :layer/start-x :layer/end-x) layer)
        y ((juxt :layer/start-y :layer/end-y) layer)]
    [x y]))

(defmethod endpoints :layer.type/line
  [layer]
  [[(:layer/start-x layer) (:layer/start-y layer)]
   [(:layer/end-x layer) (:layer/end-y layer)]])

(defn center [{:keys [layer/start-x layer/end-x layer/start-y layer/end-y]}]
  [(+ start-x (/ (- end-x start-x) 2))
   (+ start-y (/ (- end-y start-y) 2))])

;; TODO: hack to determine if shape is a circle, should be stored in the model
(defn circle? [layer]
  (or (:layer/rx layer)
      (:layer/ry layer)))

(defn measure [[x1 y1] [x2 y2]]
  (js/Math.sqrt (+ (js/Math.pow (- x2 x1) 2)
                   (js/Math.pow (- y2 y1) 2))))

(defn radius [layer]
  (measure (center layer) [(:layer/start-x layer)
                           (:layer/start-y layer)]))

(defn circle-intercept
  "Takes radius, (x1, y1) = circle center, (x2, y2) = point outside the circle"
  [r [x1 y1] [x2 y2]]
  (let [sign (if (< x1 x2) 1 -1)
        x (if (= x1 x2)
            x1
            (+ x1
               (* sign
                  (Math/sqrt (/ (* r r)
                                (+ 1
                                   (Math/pow (/ (- y2 y1)
                                                (- x2 x1))
                                             2)))))))
        y (if (= x1 x2)
            (+ y1 r)
            (+ y1 (* (- x x1) (/ (- y2 y1) (- x2 x1)))))]
    [x y]))

(defn determinant [[zx zy :as zero-point] [ax ay] [bx by]]
   (- (* (- ax zx)
         (- by zy))
      (* (- bx zx)
         (- ay zy))))

;; much better ways to do this, e.g.
;; https://math.stackexchange.com/questions/69099/equation-of-a-rectangle/69134#69134
(defn layer-intercept
  "Takes layer, (x1, y1) = point outside the layer"
  [{:keys [layer/start-x layer/end-x layer/start-y layer/end-y] :as layer} [x1 y1] & {:keys [padding]
                                                                                      :or {padding 10}}]
  (let [max-x (+ padding (max start-x end-x))
        max-y (+ padding (max start-y end-y))
        min-x (+ (- padding) (min start-x end-x))
        min-y (+ (-  padding) (min start-y end-y))
        p (/ (- max-x min-x) 2)
        q (/ (- max-y min-y) 2)
        [cx cy] (center layer)
        quadrant (if (> x1 cx)
                   (if (< y1 cy)
                     1
                     4)
                   (if (< y1 cy)
                     2
                     3))
        corner (case quadrant
                 1 [max-x min-y]
                 2 [min-x min-y]
                 3 [min-x max-y]
                 4 [max-x max-y])
        d (determinant [cx cy] corner [x1 y1])
        m (/ (- y1 cy)
             (- x1 cx))]
    (if (zero? d)
      corner
      (let [[rx ry] (case quadrant
                      1 (if (pos? d)
                          [p (* m p)]
                          [(/ (-  q) m) (- q)])
                      2 (if (pos? d)
                          [(/ (- q) m) (- q)]
                          [(- p) (* m (- p))])
                      3 (if (pos? d)
                          [(- p) (* m (- p))]
                          [(/ q m) q])
                      4 (if (pos? d)
                          [(/ q m) q]
                          [p (* m p)]))]
        [(+ cx rx) (+ cy ry)]))))

(defn contains-point? [{:keys [layer/start-x layer/end-x layer/start-y layer/end-y]} [x y] & {:keys [padding]
                                                                                              :or {padding 0}}]
  (and (<= x (+ (max start-x end-x) padding))
       (<= y (+ (max start-y end-y) padding))
       (>= x (- (min start-x end-x) padding))
       (>= y (- (min start-y end-y) padding))))

(defn arrow-path [[x0 y0] [x1 y1] & {:keys [r]
                                     :or {r 5}}]
  (let [theta (Math/atan2 (determinant [x1 y1] [x0 y0] [(+ x1 1) y1])
                          (- x1 x0))
        theta-arrow (/ Math/PI 6)
        t1 [(- x1 (* r (Math/cos (+ theta theta-arrow))))
            (- y1 (* r (Math/sin (+ theta theta-arrow))))]
        t2 [(- x1 (* r (Math/cos (- theta theta-arrow))))
            (- y1 (* r (Math/sin (- theta theta-arrow))))]
        cross-point [(- x1 (* r (Math/cos theta-arrow) (Math/cos theta)))
                     (- y1 (* r (Math/cos theta-arrow) (Math/sin theta)))]]
    (str "M "
         (clojure.string/join " " (concat [x0 y0]
                                          [x1 y1]
                                          cross-point
                                          t2
                                          [x1 y1]
                                          t1
                                          cross-point)))))


(defn remove-font-awesome-marks [text]
  (str/replace text #":(fa-[^:]+):"
               (fn [m class-name]
                 (if (font-map/class->unicode class-name)
                   ""
                   m))))

(defn font-awesome-width [layer]
  (let [text (:layer/text layer "")]
    (reduce (fn [acc [_ class-name]]
              (if-let [ch (font-map/class->unicode class-name)]
                (+ acc (utils/measure-text-width ch
                                                 (:layer/font-size layer state/default-font-size)
                                                 "FontAwesome"))
                acc))
            0 (re-seq #":(fa-[^:]+):" text))))

(defn calc-text-end-x [layer]
  (+ (:layer/start-x layer)
     (utils/measure-text-width (remove-font-awesome-marks (:layer/text layer ""))
                               (:layer/font-size layer state/default-font-size)
                               (:layer/font-family layer state/default-font-family))
     (font-awesome-width layer)))

(defn calc-text-end-y [layer]
  (- (:layer/start-y layer)
     (:layer/font-size layer state/default-font-size)))

(def pasted-unscaled-width 150)
(def pasted-scaled-width 100)
(def pasted-padding 40)

(defn normalize-pasted-layer-data
  "Makes sure that the layer-data is larger than the required minimum by expanding the width."
  [layer-data]
  (if (< pasted-unscaled-width (:width layer-data))
    layer-data
    (let [width-delta (- pasted-unscaled-width (:width layer-data))]
      (-> layer-data
        (update :min-x - (/ width-delta 2))
        (update :min-y + 16) ; don't let cursor overlap shapes
        (assoc :width pasted-unscaled-width)))))

(defn pasted-inactive-scale [normalized-layer-data]
  (/ pasted-scaled-width
     (:width normalized-layer-data)))

;; remember that it's scrolling to the center
(defn clip-scroll [normalized-layer-datas scrolled-layer-index]
  (- (/ (:width (nth normalized-layer-datas scrolled-layer-index))
        2)))

(defn clip-offset [normalized-layer-datas scrolled-layer-index layer-index]
  (cond (= scrolled-layer-index layer-index)
        (clip-scroll normalized-layer-datas layer-index)

        (> scrolled-layer-index layer-index)
        (- (clip-scroll normalized-layer-datas scrolled-layer-index)
           (* (- scrolled-layer-index layer-index)
              (+ pasted-scaled-width pasted-padding)))

        :else
        (+ (clip-scroll normalized-layer-datas scrolled-layer-index)
           (:width (nth normalized-layer-datas scrolled-layer-index))
           (* (- layer-index scrolled-layer-index)
              pasted-padding)
           (* (dec (- layer-index scrolled-layer-index))
              pasted-scaled-width ))))
