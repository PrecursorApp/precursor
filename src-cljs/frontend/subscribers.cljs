(ns frontend.subscribers
  (:require [clojure.set :as set]
            [frontend.models.chat :as chat-model]
            [frontend.rtc :as rtc]
            [frontend.utils :as utils]))

(defn subscriber-entity-ids [app-state]
  (reduce (fn [acc [id data]]
            (apply conj acc (map :db/id (:layers data))))
          #{} (get-in app-state [:subscribers :layers])))

(defn update-subscriber-entity-ids [app-state]
  (assoc-in app-state [:subscribers :entity-ids :entity-ids] (subscriber-entity-ids app-state)))

(defn add-subscriber-data [app-state client-id subscriber-data]
  (let [mouse-data (assoc (select-keys subscriber-data [:mouse-position :show-mouse? :tool :color :cust/uuid :recording])
                          :client-id client-id)
        layer-data (assoc (select-keys subscriber-data [:layers :color :cust/uuid :relation])
                          :client-id client-id)
        info-data (assoc (select-keys subscriber-data [:color :cust-name :show-mouse? :hide-in-list? :frontend-id-seed :cust/uuid :recording :chat-body])
                         :client-id client-id)
        chat-data (let [data (select-keys subscriber-data [:cust/uuid :chat-body])]
                    (-> data
                      (assoc :client-id client-id)
                      ;; keep track of last update, so we can stop showing chatting
                      (cond-> (not= (:chat-body data) (get-in app-state [:subscribers :chats client-id :chat-body]))
                        (assoc :last-update (js/Date.)))))
        cust-data (select-keys subscriber-data [:cust/uuid :cust/name :cust/color-name])]
    (cond-> app-state
      (seq mouse-data) (update-in [:subscribers :mice client-id] merge mouse-data)
      (seq layer-data) (update-in [:subscribers :layers client-id] merge layer-data)
      (seq info-data) (update-in [:subscribers :info client-id] merge info-data)
      (seq chat-data) (update-in [:subscribers :chats client-id] merge chat-data)
      true update-subscriber-entity-ids
      (:cust/uuid subscriber-data) (update-in [:cust-data :uuid->cust (:cust/uuid subscriber-data)] merge cust-data))))

(defn maybe-add-subscriber-data [app-state client-id subscriber-data]
  (if (get-in app-state [:subscribers :info client-id])
    (add-subscriber-data app-state client-id subscriber-data)
    app-state))

(defn remove-subscriber [app-state client-id]
  (-> app-state
    (update-in [:subscribers :mice] dissoc client-id)
    (update-in [:subscribers :layers] dissoc client-id)
    (update-in [:subscribers :info] dissoc client-id)
    (update-in [:subscribers :chats] dissoc client-id)
    (update-subscriber-entity-ids)))

(defn add-recording-watcher [app-state signal-fn]
  (add-watch app-state :recording-watcher
             (fn [_ _ old new]
               (when-not (identical? (get-in old [:subscribers :info])
                                     (get-in new [:subscribers :info]))
                 (let [before (reduce (fn [acc [client-id info]]
                                        (if-let [recording (:recording info)]
                                          (assoc acc (:stream-id recording) recording)
                                          acc))
                                      {} (get-in old [:subscribers :info]))
                       after (reduce (fn [acc [client-id info]]
                                       (if-let [recording (:recording info)]
                                         (assoc acc (:stream-id recording) recording)
                                         acc))
                                     {} (get-in new [:subscribers :info]))]
                   (doseq [[stream-id recording] (apply dissoc after (keys before))
                           :when (not= (:producer recording) (:client-id new))]
                     (if rtc/supports-rtc?
                       (signal-fn {:producer (:producer recording)
                                   :consumer (:client-id new)
                                   :subscribe-to-recording recording
                                   :stream-id stream-id})
                       (let [cust-uuid (get-in new [:subscribers :info (:producer recording) :cust/uuid])
                             cust-name (get-in new [:cust-data :uuid->cust cust-uuid :cust/name])]
                         (chat-model/create-bot-chat (:db new)
                                                     new
                                                     (str "Unable to get audio from @"
                                                          (or cust-name (apply str (take 6 (:producer recording))))
                                                          ", your browser doesn't seem to support webRTC."
                                                          " Please try Chrome, Firefox or Opera. Ping @prcrsr for help.")
                                                     {:error/id :error/webrtc-unsupported})))))))))
