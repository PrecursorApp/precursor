(ns pc.http.integrations
  (:require [clojure.core.async :as async]
            [crypto.random]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.http.urls :as urls]
            [pc.models.permission :as permission-model]
            [pc.slack :as slack]))

(defn create-slack-hook [team cust channel-name webhook-url]
  (let [tempid (d/tempid :db.part/user)
        permission (permission-model/create-team-image-permission! team)
        {:keys [tempids db-after]}
        @(d/transact (pcd/conn) [{:db/id (d/tempid :db.part/tx)
                                  :transaction/team (:db/id team)
                                  :cust/uuid (:cust/uuid cust)
                                  :transaction/broadcast true}
                                 {:db/id (:db/id team)
                                  :team/slack-hooks {:slack-hook/channel-name channel-name
                                                     :slack-hook/webhook-url webhook-url
                                                     :slack-hook/send-count 0
                                                     :slack-hook/permission (:db/id permission)
                                                     :db/id tempid}}
                                 (web-peer/server-frontend-id tempid (:db/id team))])]
    (d/entity db-after (d/resolve-tempid db-after tempids tempid))))

(defn delete-slack-hook [team cust slack-hook]
  (let [permission-eid (:db/id (:slack-hook/permission slack-hook))]
    @(d/transact (pcd/conn) [{:db/id (d/tempid :db.part/tx)
                              :transaction/team (:db/id team)
                              :cust/uuid (:cust/uuid cust)
                              :transaction/broadcast true}
                             (web-peer/retract-entity permission-eid)
                             (web-peer/retract-entity (:db/id slack-hook))])))


(defn send-slack-webhook [slack-hook doc & {:keys [message]}]
  (let [title (if (= "Untitled" (:document/name doc))
                (str "Precursor document " (:db/id doc))
                (:document/name doc))]
    (slack/queue-slack-webhook slack-hook
                               {:username "Precursor"
                                :icon_url "https://dtwdl3ecuoduc.cloudfront.net/img/precursor-logo.png"
                                :attachments [(merge {:fallback title
                                                      :title title
                                                      :title_link (urls/from-doc doc)
                                                      :image_url (urls/svg-from-doc doc
                                                                                    :query {:auth-token (-> slack-hook
                                                                                                          :slack-hook/permission
                                                                                                          :permission/token)})}
                                                     (when message {:text message}))]})))
