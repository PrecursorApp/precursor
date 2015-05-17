(ns pc.http.immutant-adapter
  (:require [clojure.tools.logging :as log]
            [taoensso.sente.interfaces :as i]
            [immutant.web.async :as immutant])
  (:import [com.google.common.base Throwables]))

(extend-type org.projectodd.wunderboss.web.async.Channel
  i/IAsyncNetworkChannel
  (open?  [im-ch] (immutant/open? im-ch))
  (close! [im-ch] (immutant/close im-ch))
  (send!* [im-ch msg close-after-send? on-complete]
    (let [queued? (immutant/send! im-ch msg (merge {:close? close-after-send?}
                                                   (when on-complete
                                                     {:on-success on-complete
                                                      :on-error (fn [throwable]
                                                                  (log/error throwable)
                                                                  (on-complete))})))]
      (when (and (not queued?)
                 on-complete)
        (on-complete)))))

(deftype ImmutantAsyncNetworkChannelAdapter []
  i/IAsyncNetworkChannelAdapter
  (ring-req->net-ch-resp [net-ch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-msg on-close]} callbacks-map]
      ;; Returns {:status 200 :body <immutant-implementation-channel>}:
      (immutant/as-channel ring-req
        :on-open     (when on-open (fn [im-ch] (on-open im-ch)))
        ;; :on-error (fn [im-ch throwable]) ; TODO Do we need/want this?
        :on-close    (when on-close
                       (fn [im-ch {:keys [code reason] :as status-map}]
                         (on-close im-ch status-map)))
        :on-message  (when on-msg (fn [im-ch message] (on-msg im-ch message)))
        :on-error (fn [im-ch throwable]
                    (log/errorf "immutant channel error %s" throwable))))))

(def immutant-adapter (ImmutantAsyncNetworkChannelAdapter.))
(def sente-web-server-adapter immutant-adapter) ; Alias for ns import convenience
