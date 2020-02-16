(ns frontend.rtc.stats
  (:require [goog.object]))

;; https://w3c.github.io/webrtc-pc/#h-methods-7
(defn spec-get-stats
  "selector should be a media track"
  [conn selector success failure]
  (.getStats conn selector success failure))

(defn webkit-get-stats [conn selector success failure]
  (.getStats conn success))

(def get-stats (if js/window.webkitRTCPeerConnection
                 webkit-get-stats
                 spec-get-stats))

(defn report-data [report]
  (reduce (fn [acc stat-name]
            (assoc acc stat-name (.stat report stat-name)))
          {} (.names report)))

(defn webkit-report->map [report]
  (reduce (fn [acc r]
            (assoc acc (.-type r) (report-data r)))
          {} (.result report)))

(defn spec-report->map [report]
  (reduce (fn [acc k]
            ;; check that compiler doesn't squish
            (if (.hasOwnProperty report k)
              (assoc acc k (js->clj (.get report k)))
              acc))
          {} (goog.object/getKeys report)))

(def report->map (if js/window.webkitRTCPeerConnection
                   webkit-report->map
                   spec-report->map))

(defn track-stats [track]
  {:id (.-id track)
   :enabled (.-enabled track)
   :kind (.-kind track)
   :label (.-label track)})

(defn stream-stats [stream]
  {:id (.-id stream)
   :label (.-label stream)
   :ended (.-ended stream)
   :tracks [(track-stats stream)]})

(defn connection-stats [conn-data]
  (let [conn (:conn conn-data)]
    (merge (select-keys conn-data [:consumer :producer :stream-id :stats])
           {:local-streams (mapv stream-stats (.getSenders conn))
            :remote-streams (mapv stream-stats (.getReceivers conn))
            :connection-state (.-iceConnectionState conn)
            :gathering-state (.-iceGatheringState conn)
            :signaling-state (.-signalingState conn)
            :remote-description (js/JSON.stringify (.-remoteDescription conn))
            :local-description (js/JSON.stringify (.-localDescription conn))})))

(defn gather-stats [conns-atom stream-atom]
  {:user-agent js/navigator.userAgent
   :connections (mapv connection-stats (vals @conns-atom))
   :stream (when-let [s @stream-atom] (stream-stats s))})
