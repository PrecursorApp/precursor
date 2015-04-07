(ns frontend.rtc
  (:require [cljs.core.async :as async :refer (put! <!)]
            [frontend.utils :as utils])
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

;; How it works:
;;  1. User A hits the record button, which calls getUserMedia and stores the
;;     stream in `stream` and stores `:recording? true` in subscriber-info
;;  2. User B notices that A is recording, and sends a :subscribe-to-recording?
;;     message through the signal channel (a websocket handler)
;;  3. A gets the message, creates a peer connection, adds the stream to it, then
;;     starts sending connection info to B
;;  4. B gets the connection info, creates a peer connection, then sends back some
;;     connection info to A
;;  5. A adds B's connection info to its peer connection and data starts flowing
;;  6. B gets the media stream out of its peer connection, turns it into a URL, and
;;     adds the url to the state, which the view turns into an audio element

;; Things to keep in mind
;;  1. Single peer connection for each pair of peers for each direction of data flow.
;;     Probably only need one for each pair, but 2 is simpler
;;  2. No turn fallback if peers can't connect directly
;;  3. The API that the specs mention doesn't seem to be implemented

;; Still todo
;;  1. Way to turn off recording
;;  2. Mute users
;;  3. Cleanup peer connections when users leave
;;  4. Do something useful with errors (currently reports them)

(def config {:iceServers [{:url "stun:stun.l.google.com:19302"}
                          {:url "stun:global.stun.twilio.com:3478?transport=udp"}]})

(def PeerConnection (or js/window.RTCPeerConnection
                        js/window.mozRTCPeerConnection
                        js/window.webkitRTCPeerConnection))

(def RTCIceCandidate (or js/window.RTCIceCandidate
                         js/window.mozRTCIceCandidate))

(def RTCSessionDescription (or js/window.RTCSessionDescription
                               js/window.mozRTCSessionDescription))

;; map of (str producer - consumer) to map with keys :conn, :producer, and :consumer
(defonce conns (atom {}))

;; single media stream from microphone
(defonce stream (atom nil))

(defn new-peer-conn []
  (PeerConnection. (clj->js config)))

(defn handle-negotiation [conn signal-fn]
  (.createOffer conn
                (fn [offer]
                  (.setLocalDescription conn
                                        offer
                                        #(signal-fn {:sdp (js/JSON.stringify (.-localDescription conn))})))
                #(utils/report-error "Error handling negotitation" %)))

(defn handle-ice-candidate [conn signal-fn event]
  (when-let [c (.-candidate event)]
    (signal-fn {:candidate (js/JSON.stringify c)})))

(defn setup-listeners [conn signal-fn]
  (doseq [event-name ["connecting" "track" "negotiationneeded"
                      "signalingstatechange" "iceconnectionstatechange"
                      "icegatheringstatechange" "icecandidate" "datachannel"
                      "isolationchange" "identityresult" "peeridentity"
                      "idpassertionerror" "idpvalidationerror"]]
    (.addEventListener conn event-name (fn [& args] (utils/mlog event-name args))))
  (.addEventListener conn "icecandidate" #(handle-ice-candidate conn signal-fn %))
  conn)

;; Handle navigator.mediaStreams.getUserMedia, which doesn't seem to exist in the wild
;; https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia
(def getUserMedia (or js/navigator.mozGetUserMedia
                      js/navigator.webkitGetUserMedia))


(defn get-user-media [config success error]
  (.call getUserMedia js/navigator (clj->js config) success error))

;; http://www.w3.org/TR/mediacapture-streams/#h-event-summary
(defn add-stream-watcher [stream ch]
  ;; TODO: figure out which of these events is real
  (.addEventListener stream "inactive" #(put! ch [:media-stream-stopped {:stream-id (.-id stream)}]))
  (.addEventListener stream "ended" #(put! ch [:media-stream-stopped {:stream-id (.-id stream)}])))

(defn setup-stream [ch]
  (get-user-media {:audio true}
                  (fn [s]
                    (when @stream
                      (.stop @stream))
                    (reset! stream s)
                    (add-stream-watcher s ch)
                    (put! ch [:media-stream-started {:stream-id (.-id s)}]))
                  #(put! ch [:media-stream-failed {:error (.-name %)}])))

(defn add-stream [conn stream]
  ;; spec says this should be addMediaTrack
  (.addStream conn (utils/inspect stream)))

(defn conn-id [producer consumer]
  (str producer "-" consumer))

(defn setup-producer [{:keys [signal-fn stream producer consumer]}]
  (let [conn (new-peer-conn)]
    (swap! conns assoc (conn-id producer consumer) {:conn conn :consumer consumer :producer producer})
    (setup-listeners conn signal-fn)
    (add-stream conn stream)
    (handle-negotiation conn signal-fn)))

(defn get-or-create-peer-conn [signal-fn producer consumer]
  (let [new-conns (swap! conns update-in [(conn-id producer consumer)] #(or % {:conn (setup-listeners (new-peer-conn) signal-fn)
                                                                               :consumer consumer
                                                                               :producer producer}))]
    (get-in new-conns [(conn-id producer consumer) :conn])))

(defn get-peer-conn [producer consumer]
  (get-in @conns [(conn-id producer consumer) :conn]))

(defn handle-sdp [{:keys [signal-fn sdp-str producer consumer controls-ch]}]
  (let [desc (RTCSessionDescription. (js/JSON.parse sdp-str))]
    (if (= "offer" (.-type desc))
      (let [conn (get-or-create-peer-conn signal-fn producer consumer)
            ch (async/chan)]
        (go
          (try
            (.setRemoteDescription conn desc #(put! ch :desc) #(put! ch {:error %}))
            (if-let [error (:error (<! ch))]
              (utils/report-error "error setting remote description" error)
              (.createAnswer conn #(put! ch {:answer %}) #(put! ch {:error %})))
            (let [resp (<! ch)]
              (if-let [error (:error resp)]
                (utils/report-error "error creating answer" error)
                (.setLocalDescription conn (:answer resp) #(put! ch :desc) #(put! ch {:error %}))))
            (if-let [error (:error (<! ch))]
              (utils/report-error "error setting local description" error)
              (do
                (signal-fn {:sdp (js/JSON.stringify (.-localDescription conn))})
                (put! controls-ch [:remote-media-stream-ready {:stream-url (js/window.URL.createObjectURL (first (.getRemoteStreams conn)))
                                                               :producer producer}])))
            (catch js/Error e
              (utils/report-error "error in handle-sdp for offer" e))
            (finally
              (async/close! ch)))))
      (let [conn (get-peer-conn producer consumer)]
        (.setRemoteDescription conn desc
                               #(utils/mlog "successfully set remote description")
                               #(utils/report-error "error setting remote description in handle-sdp" %))))))

(defn add-candidate [signal-fn candidate-str producer consumer]
  (let [conn (get-or-create-peer-conn signal-fn producer consumer)]
    (.addIceCandidate conn (RTCIceCandidate. (js/JSON.parse candidate-str))
                      #(utils/mlog "successfully set ice candidate")
                      #(utils/report-error "error setting ice candidate" %))))

;; signal-fn takes a map of data, e.g. {:candidate "candidate-string"}
(defn handle-signal [{:keys [send-msg producer consumer controls-ch] :as data}]
  (let [signal-fn (fn [d] (send-msg (merge d {:producer producer :consumer consumer})))]
    (cond (:candidate data)
          (add-candidate signal-fn (:candidate data) producer consumer)

          (:sdp data)
          (handle-sdp {:sdp-str (:sdp data) :producer producer :consumer consumer :signal-fn signal-fn :controls-ch controls-ch})


          (:subscribe-to-recording? data)
          (if-let [stream @stream]
            (setup-producer {:signal-fn signal-fn :stream stream :consumer consumer :producer producer})
            (utils/report-error "Subscribe to recording without stream")))))
