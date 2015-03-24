(ns pc.http.immutant-adapter
  (:require [clojure.tools.logging :as log]
            [taoensso.sente.interfaces :as i]
            [immutant.web.async :as immutant])
  (:import [com.google.common.base Throwables]))

(extend-type org.projectodd.wunderboss.web.async.Channel
  i/IAsyncNetworkChannel
  (open?  [im-ch] (immutant/open? im-ch))
  (close! [im-ch] (immutant/close im-ch))
  (send!* [im-ch msg close-after-send?]
    (immutant/send! im-ch msg {:close? close-after-send?})))

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
        ;; bandaid: adds an on-error handler to deal with clients that disappear
        ;; waiting on fix for https://issues.jboss.org/browse/IMMUTANT-543
        :on-error (when on-close
                    (fn [im-ch throwable]
                      (log/errorf "immutant channel error %s" (Throwables/getStackTraceAsString throwable))
                      (when (instance? java.nio.channels.ClosedChannelException
                                       throwable)
                        (log/infof "closing channel for %s b/c of error" (get-in ring-req [:session :sente-id]))
                        (on-close im-ch {}))))))))

(def immutant-adapter (ImmutantAsyncNetworkChannelAdapter.))
(def sente-web-server-adapter immutant-adapter) ; Alias for ns import convenience
