(ns frontend.components.drawing
  (:require [datascript :as d]
            [frontend.db :as ds]
            [frontend.state :as state]
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
  (add-tick tick-state tick (fn [owner]
                              ((om/get-shared owner :cast!)
                               :subscriber-updated {:client-id (:client-id state/subscriber-bot)
                                                    :fields (merge state/subscriber-bot {:mouse-position nil
                                                                                         :tool nil
                                                                                         :show-mouse? false})}))))

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

(defn draw-shape [tick-state {:keys [start-tick end-tick start-x end-x
                                     start-y end-y tool doc-id props source]
                              :or {tool :rect}}]
  (let [base-layer (merge {:layer/start-x start-x
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
                          props)
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
                  (d/transact! (om/get-shared owner :db) [base-layer] {:bot-layer true})))
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
                           :document/id doc-id
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

(defn signup-animation [document vw]
  (let [text "Sign in with Google"
        text-width 171

        rect-width 208
        rect-height 48

        rect-offset-x 8
        rect-offset-y 8

        rect-start-x (- vw rect-width rect-offset-x)
        rect-start-y (+ rect-offset-y rect-height)
        rect-end-x (+ rect-start-x rect-width)
        rect-end-y rect-offset-y

        text-start-x (+ rect-start-x (/ (- rect-width text-width) 2))
        text-start-y (+ rect-end-y text-height (/ (- rect-height text-height) 2))]
    (-> {:tick-ms 16
         :ticks {}}
      (move-mouse {:start-tick 10 :end-tick 45 :start-x vw :end-x rect-start-x :start-y 0 :end-y rect-start-y})
      (draw-shape {:doc-id (:db/id document) :tool :rect :start-tick 50 :end-tick 100
                   :start-x rect-start-x :end-x rect-end-x
                   :start-y rect-start-y :end-y rect-end-y
                   :source "signup-animation"
                   :props {;; look into this later, right now it interfers with signup action
                           ;; :layer/ui-target "/signup"
                           :layer/signup-button true}})
      (move-mouse {:tool :text :start-tick 100 :end-tick 120
                   :start-x rect-end-x :end-x text-start-x
                   :start-y rect-end-y :end-y (- text-start-y (/ text-height 2))})
      (draw-text {:text text :doc-id (:db/id document) :start-tick 120 :end-tick 200
                  :start-x text-start-x :end-x (+ text-start-x text-width)
                  :start-y text-start-y :end-y (- text-start-y text-height)
                  :source "signup-animation"
                  :props {;; look into this later, right now it interfers with signup action
                          ;;:layer/ui-target "/signup"
                          :layer/signup-button true}})
      (clear-subscriber 201))))

(defn signup-button [document owner]
  (reify
    om/IDisplayName (display-name [_] "Signup Button Animation")
    om/IDidMount
    (did-mount [_]
      ;; TODO: would be nice to get this a different way :(
      (let [vw (:width (utils/canvas-size))]
        (when (and (< 640 vw) ;; only if not on mobile
                   (empty? (d/datoms @(om/get-shared owner :db) :avet :layer/source "signup-animation")))
          (run-animation owner (signup-animation document vw)))))
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

(defn add-mouse-transition [tick-state tick-count previous-shape next-shape]
  (move-mouse tick-state (let [pause (+ 6 (inc (rand-int 4)))
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

(defn menu-bar [document layer-source viewport]
  (let [browser (browser document layer-source viewport)]
    {:doc-id (:db/id document) :tool :line
     :start-x (:start-x browser) :end-x (:end-x browser)
     :start-y (+ (:start-y browser) 30) :end-y (+ (:start-y browser) 30)
     :source layer-source}))

(defn close-button [document layer-source viewport]
  (let [browser (browser document layer-source viewport)]
    {:doc-id (:db/id document) :tool :circle
     :start-x (+ (:start-x browser) 10) :end-x (+ (:start-x browser) 20)
     :start-y (+ (:start-y browser) 10) :end-y (+ (:start-y browser) 20)
     :source layer-source}))

(defn minimize-button [document layer-source viewport]
  (let [browser (browser document layer-source viewport)]
    {:doc-id (:db/id document) :tool :circle
     :start-x (+ (:start-x browser) 25) :end-x (+ (:start-x browser) 35)
     :start-y (+ (:start-y browser) 10) :end-y (+ (:start-y browser) 20)
     :source layer-source}))

(defn expand-button [document layer-source viewport]
  (let [browser (browser document layer-source viewport)]
    {:doc-id (:db/id document) :tool :circle
     :start-x (+ (:start-x browser) 40) :end-x (+ (:start-x browser) 50)
     :start-y (+ (:start-y browser) 10) :end-y (+ (:start-y browser) 20)
     :source layer-source}))

(defn search-box [document layer-source viewport]
  (let [browser (browser document layer-source viewport)
        width (- (:end-x browser)
                 (:start-x browser))
        height (- (:end-y browser)
                  (:start-y browser))
        box-width 540
        start-x (int (- (+ (:start-x browser) (/ width 2))
                        (/ box-width 2)))
        box-height 40
        start-y (int (- (+ (:start-y browser) (/ height 2))
                        (* 2 box-height)))]
    {:doc-id (:db/id document) :tool :rect
     :start-x start-x :end-x (+ start-x box-width)
     :start-y start-y :end-y (+ start-y box-height)
     :source layer-source}))

(defn submit-button [document layer-source viewport]
  (let [search-box (search-box document layer-source viewport)
        width (- (:end-x search-box)
                 (:start-x search-box))
        height (- (:end-y search-box)
                  (:start-y search-box))
        start-x (int (+ (:start-x search-box)
                        (/ width 4)))
        start-y (int (+ (:start-y search-box)
                        (* 1.5 height)))
        ]
    {:doc-id (:db/id document) :tool :rect
     :start-x start-x :end-x (+ start-x (int (/ width 4)))
     :start-y start-y :end-y (+ start-y (int (* height .80)))
     :source layer-source}))

(defn lucky-button [document layer-source viewport]
  (let [submit-button (submit-button document layer-source viewport)
        start-x (:end-x submit-button)
        width (- (:end-x submit-button)
                 (:start-x submit-button))]
    {:doc-id (:db/id document) :tool :rect
     :start-x start-x :end-x (+ start-x width)
     :start-y (:start-y submit-button) :end-y (:end-y submit-button)
     :source layer-source}))

(defn footer-1 [document layer-source viewport]
  (let [browser (browser document layer-source viewport)
        width (- (:end-x browser)
                 (:start-x browser))
        line-width 500
        start-x (int (- (+ (:start-x browser) (/ width 2))
                        (/ line-width 2)))
        start-y (- (:end-y browser) 60)]
    {:doc-id (:db/id document) :tool :line
     :start-x start-x  :end-x (+ start-x line-width)
     :start-y start-y :end-y start-y
     :source layer-source}))

(defn footer-2 [document layer-source viewport]
  (let [browser (browser document layer-source viewport)
        width (- (:end-x browser)
                 (:start-x browser))
        line-width 400
        start-x (int (- (+ (:start-x browser) (/ width 2))
                        (/ line-width 2)))
        start-y (- (:end-y browser) 40)]
    {:doc-id (:db/id document) :tool :line
     :start-x start-x  :end-x (+ start-x line-width)
     :start-y start-y :end-y start-y
     :source layer-source}))

(defn landing-animation [document layer-source viewport]
  (let [browser (browser document layer-source viewport)
        menu-bar (menu-bar document layer-source viewport)
        close-button (close-button document layer-source viewport)
        minimize-button (minimize-button document layer-source viewport)
        expand-button (expand-button document layer-source viewport)
        search-box (search-box document layer-source viewport)
        submit-button (submit-button document layer-source viewport)
        lucky-button (lucky-button document layer-source viewport)
        footer-1 (footer-1 document layer-source viewport)
        footer-2 (footer-2 document layer-source viewport)]
    (-> {:tick-ms 16
         :ticks {}}
      ;; the mouse could be automatic
      (add-mouse-transition 50
                            {:end-x (:width viewport)
                             :end-y 100
                             :tool :rect}
                            browser)
      (add-shape 100 browser)
      (add-mouse-transition 50 browser menu-bar)
      (add-shape 50 menu-bar)
      (add-mouse-transition 25 menu-bar close-button)
      (add-shape 10 close-button)
      (add-mouse-transition 12 close-button minimize-button)
      (add-shape 10 minimize-button)
      (add-mouse-transition 8 minimize-button expand-button)
      (add-shape 10 expand-button)
      (add-mouse-transition 30 expand-button search-box)
      (add-shape 30  search-box)
      (add-mouse-transition 20 search-box submit-button)
      (add-shape 30 submit-button)
      (add-mouse-transition 12 submit-button lucky-button)
      (add-shape 30 lucky-button)
      (add-mouse-transition 20 lucky-button footer-1)
      (add-shape 20 footer-1)
      (add-mouse-transition 10 footer-1 footer-2)
      (add-shape 18 footer-2))))

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
                   (zero? (count (remove (comp :hide-in-list? second) (utils/inspect subscribers)))))
          (run-animation owner (landing-animation {:db/id doc-id}
                                                  (om/get-state owner :layer-source)
                                                  viewport)))))
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
