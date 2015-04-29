(ns frontend.components.drawing
  (:require [datascript :as d]
            [frontend.camera :as cameras]
            [frontend.db :as ds]
            [frontend.db.trans :as trans]
            [frontend.state :as state]
            [frontend.svg :as svg]
            [frontend.utils :as utils]
            [goog.dom]
            [goog.style]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(defn add-tick [tick-state tick tick-fn]
  (update-in tick-state [:ticks tick] (fn [f] (fn [owner]
                                                (when f (f owner))
                                                (tick-fn owner)))))

(defn annotate-keyframes [tick-state & ticks]
  (update-in tick-state [:keyframes] #(apply (fnil conj #{}) % ticks)))

(defn clear-subscriber [tick-state tick]
  (-> tick-state
    (add-tick tick (fn [owner]
                     ((om/get-shared owner :cast!)
                      :subscriber-updated {:client-id (:client-id state/subscriber-bot)
                                           :fields (merge state/subscriber-bot {:mouse-position nil
                                                                                :tool nil
                                                                                :show-mouse? false})})))
    (annotate-keyframes tick)))

(defn move-mouse [tick-state {:keys [start-tick end-tick start-x end-x start-y end-y tool]
                              :or {tool :rect}}]
  (-> (reduce (fn [tick-state relative-tick]
                (add-tick tick-state
                          (+ start-tick relative-tick)
                          (fn [owner]
                            (let [ex (+ start-x (* relative-tick
                                                   (/ (- end-x start-x)
                                                      (- end-tick start-tick))))
                                  ey (+ start-y (* relative-tick
                                                   (/ (- end-y start-y)
                                                      (- end-tick start-tick))))]
                              ((om/get-shared owner :cast!)
                               :subscriber-updated {:client-id (:client-id state/subscriber-bot)
                                                    :fields (merge state/subscriber-bot {:mouse-position [ex ey]
                                                                                         :show-mouse? true
                                                                                         :tool tool})})))))
              tick-state
              (range 0 (inc (- end-tick start-tick))))
    (annotate-keyframes end-tick)))

(defn props->base-layer [{:keys [start-tick end-tick start-x end-x
                                 start-y end-y tool doc-id props source]
                          :or {tool :rect}}]
  (merge {:layer/start-x start-x
          :layer/start-y start-y
          :layer/end-x end-x
          :layer/end-y end-y
          :layer/type (keyword "layer.type" (if (= :circle tool)
                                              "rect"
                                              (name tool)))
          :layer/name "placeholder"
          :layer/stroke-width 1
          :document/id doc-id
          :db/id (- (inc (rand-int 1000)))}
         (when (= :circle tool)
           {:layer/rx 1000
            :layer/ry 1000})
         (when source
           {:layer/source source})
         props))

(defn draw-shape [tick-state {:keys [start-tick end-tick start-x end-x
                                     start-y end-y tool doc-id props source]
                              :or {tool :rect}
                              :as properties}]
  (let [base-layer (props->base-layer properties)
        ;; number of ticks to pause before saving layer
        pause-ticks 2]
    (-> (reduce (fn [tick-state relative-tick]
                  (add-tick tick-state
                            (+ start-tick relative-tick)
                            (fn [owner]
                              (let [ex (+ start-x (* relative-tick
                                                     (/ (- end-x start-x)
                                                        (- end-tick start-tick pause-ticks))))
                                    ey (+ start-y (* relative-tick
                                                     (/ (- end-y start-y)
                                                        (- end-tick start-tick pause-ticks))))]
                                ((om/get-shared owner :cast!)
                                 :subscriber-updated {:client-id (:client-id state/subscriber-bot)
                                                      :fields (merge state/subscriber-bot {:mouse-position [ex ey]
                                                                                           :show-mouse? true
                                                                                           :layers [(assoc base-layer
                                                                                                           :layer/current-x ex
                                                                                                           :layer/current-y ey)]
                                                                                           :tool tool})})))))
                tick-state
                (range 0 (inc (- end-tick start-tick pause-ticks))))
      (add-tick end-tick
                (fn [owner]
                  ((om/get-shared owner :cast!)
                   :subscriber-updated {:client-id (:client-id state/subscriber-bot)
                                        :fields {:mouse-position nil
                                                 :layers nil
                                                 :tool tool}})
                  (let [conn (om/get-shared owner :db)]
                    (d/transact! conn [(assoc base-layer :db/id (trans/get-next-transient-id conn))] {:bot-layer true}))))
      (annotate-keyframes start-tick end-tick))))

(defn move-points [points move-x move-y]
  (map (fn [{:keys [rx ry]}]
         {:rx (+ rx move-x)
          :ry (+ ry move-y)})
       points))

(defn move-layer [layer x y]
  (-> layer
    (assoc :layer/start-x (+ x (:layer/start-x layer))
           :layer/end-x (+ x (:layer/end-x layer))
           :layer/current-x (+ x (:layer/end-x layer))
           :layer/start-y (+ y (:layer/start-y layer))
           :layer/end-y (+ y (:layer/end-y layer))
           :layer/current-y (+ y (:layer/end-y layer)))
    (cond-> (= :layer.type/path (:layer/type layer))
      (assoc :layer/path (svg/points->path (move-points (:points layer) x y))))))

(defn alt-drag [tick-state {:keys [start-tick end-tick start-x end-x
                                   start-y end-y doc-id props source
                                   layers]
                            :as properties}]
  (let [x-distance (- end-x start-x)
        y-distance (- end-y start-y)
        base-layers (map (fn [p] (props->base-layer (merge properties p)))
                         layers)
        ;; number of ticks to pause before saving layers
        pause-ticks 2]
    (-> (reduce (fn [tick-state relative-tick]
                  (add-tick tick-state
                            (+ start-tick relative-tick)
                            (fn [owner]
                              (let [move-x (* relative-tick
                                              (/ x-distance
                                                 (- end-tick start-tick pause-ticks)))
                                    move-y (* relative-tick
                                              (/ y-distance
                                                 (- end-tick start-tick pause-ticks)))]
                                ((om/get-shared owner :cast!)
                                 :subscriber-updated {:client-id (:client-id state/subscriber-bot)
                                                      :fields (merge state/subscriber-bot {:mouse-position [(+ start-x move-x)
                                                                                                            (+ start-y move-y)]
                                                                                           :show-mouse? true
                                                                                           :layers (map (fn [l] (move-layer l move-x move-y))
                                                                                                        base-layers)
                                                                                           :tool :select})})))))
                tick-state
                (range 0 (inc (- end-tick start-tick pause-ticks))))
      (add-tick end-tick
                (fn [owner]
                  ((om/get-shared owner :cast!)
                   :subscriber-updated {:client-id (:client-id state/subscriber-bot)
                                        :fields {:mouse-position nil
                                                 :layers nil
                                                 :tool :select}})
                  (let [conn (om/get-shared owner :db)]
                    (d/transact! conn  (map (fn [l] (assoc (move-layer l x-distance y-distance)
                                                           :db/id (trans/get-next-transient-id conn)))
                                            base-layers)
                                 {:bot-layer true}))))
      (annotate-keyframes start-tick end-tick))))

(def text-height 14)

(defn draw-text [tick-state {:keys [start-tick end-tick start-x end-x
                                    start-y end-y doc-id text source props]}]
  (let [base-layer (merge {:layer/start-x start-x
                           :layer/start-y start-y
                           :layer/end-x end-x
                           :layer/end-y end-y
                           :layer/type :layer.type/text
                           :layer/name "placeholder"
                           :layer/text text
                           :layer/stroke-width 1
                           :layer/document doc-id
                           :db/id (inc (rand-int 1000))}
                          (when source
                            {:layer/source source})
                          props)
        ;; number of ticks to pause before saving layer
        pause-ticks 2]
    (-> (reduce (fn [tick-state relative-tick]
                  (add-tick tick-state
                            (+ start-tick relative-tick)
                            (fn [owner]
                              (let [letter-count (int (/ (count text)
                                                         (/ (- end-tick start-tick pause-ticks)
                                                            relative-tick)))]
                                ((om/get-shared owner :cast!)
                                 :subscriber-updated {:client-id (:client-id state/subscriber-bot)
                                                      :fields (merge state/subscriber-bot {:mouse-position [start-x (- start-y (/ text-height 2))]
                                                                                           :show-mouse? true
                                                                                           :layers [(assoc base-layer
                                                                                                           :layer/text (apply str (take letter-count text))
                                                                                                           :layer/current-x end-x
                                                                                                           :layer/current-y end-y)]
                                                                                           :tool :text})})))))
                tick-state
                (map int
                     (range 0
                            (inc (- end-tick start-tick pause-ticks))
                            (/ (- end-tick start-tick pause-ticks)
                               (count text)))))
      (add-tick end-tick
                (fn [owner]
                  ((om/get-shared owner :cast!)
                   :subscriber-updated {:client-id (:client-id state/subscriber-bot)
                                        :fields (merge state/subscriber-bot {:mouse-position nil
                                                                             :layers nil
                                                                             :tool :text})})
                  (d/transact! (om/get-shared owner :db) [base-layer] {:bot-layer true})))
      (annotate-keyframes start-tick end-tick))))

(defn clear-ticks [tick-state ticks]
  (update-in tick-state [:ticks] #(apply dissoc % ticks)))

(defn middle-elem [v]
  (if (seq v)
    (nth v (quot (count v) 2))
    nil))

(defn run-animation* [owner start-ms current-ms tick-state min-tick max-tick]
  (when (and (om/mounted? owner)
             (seq (:ticks tick-state)))
    (let [latest-tick (int (/ (- current-ms start-ms)
                              (:tick-ms tick-state)))
          tick-range (vec (range min-tick (inc latest-tick)))
          ticks (-> (filter #(contains? (:keyframes tick-state) %) tick-range)
                  set
                  (conj (middle-elem tick-range)))]
      (doseq [tick (sort ticks)]
        (when-let [tick-fn (get-in tick-state [:ticks tick])]
          (tick-fn owner)))
      (utils/rAF (fn [timestamp]
                   (run-animation* owner start-ms timestamp (clear-ticks tick-state tick-range) latest-tick max-tick))))))

(defn run-animation
  "tick-state should be a map with keys :tick-ms, whose value is the number of ms between
  ticks, and :ticks, whose value is a map of tick-number to function to call on that tick.
  The function will be passed an owner.

  Example tick-state:
  {:tick-ms 16
   :ticks {0 #((om/get-shared % :cast!) :subscriber-updated {:client-id \"prcrsr-bot\" :fields {:mouse-position [1 2]}})
           5 #((om/get-shared % :cast!) :subscriber-updated {:client-id \"prcrsr-bot\" :fields {:mouse-position [2 3]}})

  Unmount the component to stop the animation."
  [owner tick-state]
  (let [max-tick (apply max (keys (:ticks tick-state)))]
    (utils/rAF (fn [timestamp]
                 (run-animation* owner timestamp timestamp tick-state 0 max-tick)))))

(defn cleanup [owner]
  ((om/get-shared owner :cast!) :subscriber-updated {:client-id (:client-id state/subscriber-bot)
                                                     :fields (merge state/subscriber-bot {:mouse-position nil
                                                                                          :layers nil
                                                                                          :show-mouse? false})}))

(defn signup-animation [document top-right]
  (let [text "Sign in with Google"
        text-width 171

        rect-width 208
        rect-height 48

        rect-offset-x 8
        rect-offset-y 8

        ;; e stands for edge
        [ex ey] top-right

        rect-start-x (- ex rect-width rect-offset-x)
        rect-start-y (+ ey (+ rect-offset-y rect-height))
        rect-end-x (+ rect-start-x rect-width)
        rect-end-y (+ ey rect-offset-y)

        text-start-x (+ rect-start-x (/ (- rect-width text-width) 2))
        text-start-y (+ rect-end-y text-height (/ (- rect-height text-height) 2))]
    (-> {:tick-ms 16
         :ticks {}}
      (move-mouse {:tool :text
                   :start-tick 10
                   :end-tick 45
                   :start-x ex :end-x text-start-x
                   :start-y ey :end-y (- text-start-y (/ text-height 2))})
      (draw-text {:text text
                  :doc-id (:db/id document)
                  :start-tick 50
                  :end-tick 130
                  :start-x text-start-x :end-x (+ text-start-x text-width)
                  :start-y text-start-y :end-y (- text-start-y text-height)
                  :source "signup-animation"
                  :props {:layer/signup-button true}})
      (move-mouse {:start-tick 140
                   :end-tick 145
                   :start-x text-start-x :end-x rect-start-x
                   :start-y text-start-y :end-y rect-start-y})
      (draw-shape {:doc-id (:db/id document)
                   :tool :rect
                   :start-tick 150
                   :end-tick 200
                   :start-x rect-start-x :end-x rect-end-x
                   :start-y rect-start-y :end-y rect-end-y
                   :source "signup-animation"
                   :props {:layer/ui-target "/signup"
                           :layer/ui-id "/signup"
                           :layer/signup-button true}})
      (clear-subscriber 201))))

(defn signup-button [{:keys [document camera]} owner]
  (reify
    om/IDisplayName (display-name [_] "Signup Button Animation")
    om/IDidMount
    (did-mount [_]
      ;; TODO: would be nice to get this a different way :(
      (let [viewport (utils/canvas-size)]
        (when (and (< 640 (:width viewport)) ;; only if not on mobile
                   (empty? (d/datoms @(om/get-shared owner :db) :avet :layer/source "signup-animation")))
          (run-animation owner (signup-animation document (cameras/top-right camera viewport))))))
    om/IWillUnmount
    (will-unmount [_]
      (cleanup owner))
    om/IRender
    (render [_]
      ;; dummy span so that the component can be mounted
      (dom/span #js {:className "hidden"}))))

(defn add-shape [tick-state tick-count props]
  (draw-shape tick-state (merge (let [pause (+ 3 (inc (rand-int 5)))
                                      start-tick (+ pause (apply max (conj (keys (:ticks tick-state)) -1)))]
                                  {:start-tick start-tick
                                   :end-tick (+ start-tick (int (* 1.5 tick-count)))})
                                props)))

(defn add-alt-drag [tick-state tick-count props]
  (alt-drag tick-state (merge (let [pause (+ 3 (inc (rand-int 5)))
                                    start-tick (+ pause (apply max (conj (keys (:ticks tick-state)) -1)))]
                                {:start-tick start-tick
                                 :end-tick (+ start-tick (int (* 1.5 tick-count)))})
                              props)))

(defn add-text [tick-state tick-count props]
  (draw-text tick-state (merge (let [pause (+ 3 (inc (rand-int 5)))
                                     start-tick (+ pause (apply max (conj (keys (:ticks tick-state)) -1)))]
                                 {:start-tick start-tick
                                  :end-tick (+ start-tick (int (* 1.5 tick-count)))})
                               props)))

(defn add-mouse-transition [tick-state tick-count previous-shape next-shape]
  (move-mouse tick-state (let [pause (+ 2 (inc (rand-int 4)))
                               start-tick (+ pause (apply max (conj (keys (:ticks tick-state)) -1)))]
                           {:start-tick start-tick
                            :end-tick (+ start-tick (int (* 1.5 tick-count)))
                            :start-x (:end-x previous-shape)
                            :start-y (:end-y previous-shape)
                            :tool (:tool previous-shape)
                            :end-x (:start-x next-shape)
                            :end-y (:start-y next-shape)})))

(defn browser [document layer-source viewport]
  (let [start-x 100
        start-y 100
        width (int (* (- (:width viewport) 200)
                      0.9))
        height (int (* (- (:height viewport) 200)
                       0.9))]
    {:doc-id (:db/id document) :tool :rect
     :start-x start-x :end-x (+ start-x width)
     :start-y start-y :end-y (+ start-y height)
     :source layer-source}))

(def circle-offset {:x 30 :y 20})
(def circle-size 40)

(defn ph-circle [document layer-source viewport]
  (let [browser (browser document layer-source viewport)]
    {:doc-id (:db/id document) :tool :circle
     :start-x (+ (:start-x browser) (:x circle-offset))
     :end-x (+ (:start-x browser) (:x circle-offset) circle-size)
     :start-y (+ (:start-y browser) (:y circle-offset))
     :end-y (+ (:start-y browser) (:y circle-offset) circle-size)
     :source layer-source}))

(defn ph-p [document layer-source viewport]
  (let [browser (browser document layer-source viewport)]
    {:doc-id (:db/id document) :tool :text
     :start-x (+ (:start-x browser) (:x circle-offset) 10)
     :end-x (+ (:start-x browser) (:x circle-offset) 10)
     :start-y (+ (:start-y browser) (:y circle-offset) (- circle-size 10))
     :end-y (+ (:start-y browser) (:y circle-offset) (- circle-size 10))
     :source layer-source
     :text "P"
     :props {:layer/font-size 30}}))

(def vote-box-offset {:x 30 :y (+ (:y circle-offset) circle-size 30)})

(defn vote-box [document layer-source viewport]
  (let [browser (browser document layer-source viewport)]
    {:doc-id (:db/id document) :tool :rect
     :start-x (+ (:start-x browser) (:x vote-box-offset))
     :end-x (+ (:start-x browser) (:x vote-box-offset) 34)
     :start-y (+ (:start-y browser) (:y vote-box-offset))
     :end-y (+ (:start-y browser) (:y vote-box-offset) 44)
     :source layer-source}))

(defn vote-left [document layer-source viewport]
  (let [vote-box (vote-box document layer-source viewport)
        box-width (Math/abs (- (:start-x vote-box) (:end-x vote-box)))
        box-height (Math/abs (- (:start-y vote-box) (:end-y vote-box)))
        start-y (+ (:start-y vote-box) (/ box-height 2))]
    {:doc-id (:db/id document) :tool :line
     :start-x (- (+ (:start-x vote-box)
                    (/ box-width 2))
                 5)
     :end-x (+ (:start-x vote-box) (/ box-width 2))
     :start-y start-y
     :end-y (- start-y 10)
     :source layer-source}))

(defn vote-right [document layer-source viewport]
  (let [vote-box (vote-box document layer-source viewport)
        box-width (Math/abs (- (:start-x vote-box) (:end-x vote-box)))
        box-height (Math/abs (- (:start-y vote-box) (:end-y vote-box)))
        start-y (+ (:start-y vote-box) (/ box-height 2))]
    {:doc-id (:db/id document) :tool :line
     :start-x (+ (:start-x vote-box) (/ box-width 2))
     :end-x (+ (+ (:start-x vote-box)
                  (/ box-width 2))
               5)
     :start-y (- start-y 10)
     :end-y start-y
     :source layer-source}))

(defn vote-count [document layer-source viewport]
  (let [vote-box (vote-box document layer-source viewport)
        box-width (Math/abs (- (:start-x vote-box) (:end-x vote-box)))
        box-height (Math/abs (- (:start-y vote-box) (:end-y vote-box)))
        y (+ (:start-y vote-box) (* 3 (/ box-height 4)))]
    {:doc-id (:db/id document) :tool :line
     :start-x (+ (:start-x vote-box) (/ box-width 3))
     :end-x (+ (:start-x vote-box) (* 2 (/ box-width 3)))
     :start-y y
     :end-y y
     :source layer-source}))

(defn post-title [document layer-source viewport]
  (let [browser (browser document layer-source viewport)
        vote-box (vote-box document layer-source viewport)
        browser-width (Math/abs (- (:start-x browser) (:end-x browser)))
        box-height (Math/abs (- (:start-y vote-box) (:end-y vote-box)))
        start-x (+ (:end-x vote-box) 20)
        end-x (+ start-x (/ browser-width 4))
        y (+ (:start-y vote-box) (/ box-height 3))]
    {:doc-id (:db/id document) :tool :line
     :start-x start-x
     :end-x end-x
     :start-y y
     :end-y y
     :source layer-source}))

(defn post-tagline [document layer-source viewport]
  (let [browser (browser document layer-source viewport)
        vote-box (vote-box document layer-source viewport)
        browser-width (Math/abs (- (:start-x browser) (:end-x browser)))
        box-height (Math/abs (- (:start-y vote-box) (:end-y vote-box)))
        start-x (+ (:end-x vote-box) 20)
        end-x (+ start-x (/ browser-width 3))
        y (+ (:start-y vote-box) (* 2 (/ box-height 3)))]
    {:doc-id (:db/id document) :tool :line
     :start-x start-x
     :end-x end-x
     :start-y y
     :end-y y
     :source layer-source}))

(def icon-size 30)

(defn hunter-icon-1 [document layer-source viewport]
  (let [browser (browser document layer-source viewport)
        vote-box (vote-box document layer-source viewport)
        browser-width (Math/abs (- (:start-x browser) (:end-x browser)))
        box-height (Math/abs (- (:start-y vote-box) (:end-y vote-box)))
        start-x (- (:end-x browser) (/ browser-width 4))
        end-x (+ start-x icon-size)
        start-y (+ (:start-y vote-box) (/ (- box-height icon-size) 2))
        end-y (+ start-y icon-size)]
    {:doc-id (:db/id document) :tool :circle
     :start-x start-x
     :end-x end-x
     :start-y start-y
     :end-y end-y
     :source layer-source}))

(defn hunter-icon-2 [document layer-source viewport]
  (let [icon-1 (hunter-icon-1 document layer-source viewport)
        start-x (+ (:start-x icon-1) 10)
        end-x (+ start-x icon-size)
        start-y (:start-y icon-1)
        end-y (:end-y icon-1)]
    {:doc-id (:db/id document) :tool :circle
     :start-x start-x
     :end-x end-x
     :start-y start-y
     :end-y end-y
     :source layer-source}))

(defn comment-count [document layer-source viewport]
  (let [icon-2 (hunter-icon-2 document layer-source viewport)
        start-x (+ (:end-x icon-2) (/ icon-size 2))
        end-x (+ start-x (* 1.25 icon-size))

        y (+ (:start-y icon-2) (/ icon-size 2))]
    {:doc-id (:db/id document) :tool :line
     :start-x start-x
     :end-x end-x
     :start-y y
     :end-y y
     :source layer-source}))

(defn shift-post-layer [post-layer y]
  (-> post-layer
    (update-in [:start-y] + y)
    (update-in [:end-y] + y)))

(defn shift-post-layers [post-layers x]
  (map (fn [l] (shift-post-layer l x))
       post-layers))

(defn landing-animation [document layer-source viewport]
  (let [browser (browser document layer-source viewport)
        ph-circle (ph-circle document layer-source viewport)
        ph-p (ph-p document layer-source viewport)
        vote-box (vote-box document layer-source viewport)
        vote-left (vote-left document layer-source viewport)
        vote-right (vote-right document layer-source viewport)
        vote-count (vote-count document layer-source viewport)
        post-title (post-title document layer-source viewport)
        post-tagline (post-tagline document layer-source viewport)
        hunter-icon-1 (hunter-icon-1 document layer-source viewport)
        hunter-icon-2 (hunter-icon-2 document layer-source viewport)
        comment-count (comment-count document layer-source viewport)
        post-layers [vote-box vote-left vote-right vote-count post-title post-tagline
                     hunter-icon-1 hunter-icon-2 comment-count]
        vote-box-height (Math/abs (- (:end-y vote-box) (:start-y vote-box)))
        vote-box-width (Math/abs (- (:end-x vote-box) (:start-x vote-box)))
        browser-height (Math/abs (- (:end-y browser) (:start-y browser)))
        post-layer-offset (* vote-box-height (/ 4 5))
        post-layer-count (Math/floor (/ (- browser-height
                                           (:y vote-box-offset))
                                        (+ vote-box-height post-layer-offset)))
        alt-drags (loop [i 0
                         f identity]
                    (if (>= (inc i) post-layer-count)
                      f
                      (let [build-count (min (- post-layer-count i) (inc i))]
                        (recur (+ i build-count)
                               (let [offset (+ vote-box-height
                                               post-layer-offset)
                                     x (+ (:start-x vote-box) (* vote-box-width (/ 2 3)))
                                     start-y (+ (:start-y vote-box)
                                                (* vote-box-height (/ 2 3))
                                                (* i offset))]
                                 (comp (fn [tick-state]
                                         (add-alt-drag tick-state (min (* build-count 15)
                                                                       30)
                                                       {:layers (reduce (fn [acc i]
                                                                          (into acc (shift-post-layers post-layers (* i offset))))
                                                                        [] (range 0 build-count))
                                                        :start-x x
                                                        :end-x x
                                                        :start-y start-y
                                                        :end-y (+ start-y (* build-count offset))}))
                                       (fn [tick-state]
                                         (add-mouse-transition tick-state 5
                                                               {:end-x x
                                                                :end-y start-y
                                                                :tool :select}
                                                               {:start-x x
                                                                :start-y start-y
                                                                :tool :select}))
                                       f))))))]
    (-> {:tick-ms 16
         :ticks {}}
      (add-tick 50 identity)
      ;; the mouse could be automatic
      (add-mouse-transition 20
                            {:end-x 0
                             :end-y 0
                             :tool :rect}
                            browser)
      (add-shape 60 browser)
      (add-mouse-transition 30 browser ph-circle)
      (add-shape 10 ph-circle)
      (add-mouse-transition 5 ph-circle ph-p)
      (add-text 7 ph-p)
      (add-mouse-transition 7 (assoc ph-p :tool :rect) vote-box)
      (add-shape 10 vote-box)
      (add-mouse-transition 5 vote-box vote-left)
      (add-shape 3 vote-left)
      (add-shape 3 vote-right)
      (add-mouse-transition 5 vote-right vote-count)
      (add-shape 7 vote-count)
      (add-mouse-transition 7 vote-count post-title)
      (add-shape 10 post-title)
      (add-mouse-transition 7 post-title post-tagline)
      (add-shape 15 post-tagline)
      (add-mouse-transition 10 post-tagline hunter-icon-1)
      (add-shape 7 hunter-icon-1)
      (add-mouse-transition 10 hunter-icon-1 hunter-icon-2)
      (add-shape 7 hunter-icon-2)
      (add-mouse-transition 5 hunter-icon-2 comment-count)
      (add-shape 5 comment-count)
      (alt-drags))))

(defn add-landing-cleanup [tick-state]
  (let [max-tick (apply max (keys (:ticks tick-state)))]
    (-> tick-state
      (add-tick (inc max-tick) (fn [owner]
                                 (cleanup owner)
                                 ((om/get-shared owner :cast!) :landing-animation-completed)))
      (annotate-keyframes (inc max-tick)))))

(defn landing-background [{:keys [doc-id subscribers]} owner]
  (reify
    om/IInitState (init-state [_] {:layer-source (utils/uuid)})
    om/IDisplayName (display-name [_] "Landing Animation")
    om/IDidMount
    (did-mount [_]
      ;; TODO: would be nice to get this a different way :(
      (let [viewport (utils/canvas-size)]
        (when (and (< 640 (:width viewport)) ;; only if not on mobile
                   (< 640 (:height viewport))
                   (ds/empty-db? @(om/get-shared owner :db))
                   (zero? (count (remove (comp :hide-in-list? second) subscribers))))
          (run-animation owner (add-landing-cleanup (landing-animation {:db/id doc-id}
                                                                       (om/get-state owner :layer-source)
                                                                       viewport))))))
    om/IWillUnmount
    (will-unmount [_]
      (cleanup owner)
      (let [conn (om/get-shared owner :db)
            source (om/get-state owner :layer-source)]
        (d/transact! conn (mapv (fn [e] [:db/add e :layer/deleted true])
                                (map :e (d/datoms @conn :avet :layer/source source)))
                     {:bot-layer true})
        (js/setTimeout #(d/transact! conn (mapv (fn [e] [:db.fn/retractEntity e])
                                                (map :e (d/datoms @conn :avet :layer/source source)))
                                     {:bot-layer true})
                       1000)))
    om/IRender
    (render [_]
      ;; dummy span so that the component can be mounted
      (dom/span #js {:className "hidden"}))))
