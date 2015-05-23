(ns pc.http.integrations
  (:require [clojure.core.async :as async]
            [crypto.random]
            [pc.http.urls :as urls]
            [pc.slack :as slack]))


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
