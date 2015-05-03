(ns pc.http.issues
  (:require [pc.models.issue :as issue-model]))

(defonce issue-subs (atom #{}))

(defn subscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (swap! issue-subs conj client-id)
  (let [issues (map issue-model/read-api (issue-model/all (:db req)))]
    (?reply-fn {:entities issues
                :entity-type :issue})))

(defn unsubscribe [client-id]
  (swap! issue-subs disj client-id))
