(ns pc.http.urls
  (:require [cemerick.url :as url]
            [pc.profile :as profile]))

(defn make-url [path & {:keys [query subdomain]}]
  (str (url/map->URL (merge {:host (str (when subdomain
                                          (str subdomain "."))
                                        (profile/hostname))
                             :protocol (if (profile/force-ssl?)
                                         "https"
                                         "http")
                             :port (if (profile/force-ssl?)
                                     (profile/https-port)
                                     (profile/http-port))
                             :path path}
                            (when query
                              {:query query})))))

(defn root [& {:keys [query subdomain]}]
  (make-url "/" :query query :subdomain subdomain))

(defn doc-url [doc-id & {:keys [query subdomain]}]
  (make-url (str "/document/" doc-id) :query query :subdomain subdomain))

(defn doc-svg [doc-id & {:keys [query subdomain]}]
  (make-url (str "/document/" doc-id ".svg") :query query :subdomain subdomain))

(defn doc-png [doc-id & {:keys [query subdomain]}]
  (make-url (str "/document/" doc-id ".png") :query query :subdomain subdomain))

(defn blog-root []
  (make-url "/blog"))

(defn blog-url [slug]
  (make-url (str "/blog/" slug)))

(defn from-doc [doc & {:keys [query]}]
  (if-let [team (:document/team doc)]
    (doc-url (:db/id doc) :subdomain (:team/subdomain team) :query query)
    (doc-url (:db/id doc) :query query)))

(defn png-from-doc [doc & {:keys [query]}]
  (if-let [team (:document/team doc)]
    (doc-png (:db/id doc) :subdomain (:team/subdomain team) :query query)
    (doc-png (:db/id doc) :query query)))

(defn svg-from-doc [doc & {:keys [query]}]
  (if-let [team (:document/team doc)]
    (doc-svg (:db/id doc) :subdomain (:team/subdomain team) :query query)
    (doc-svg (:db/id doc) :query query)))

(defn from-issue [issue & {:keys [query]}]
  (make-url (str "/issues/" (:frontend/issue-id issue))))

(defn twilio-status-callback []
  (make-url "/hooks/twilio"))

(defn team-plan [team]
  (let [team-doc (:team/intro-doc team)]
    (make-url (str "/document/" (:db/id team-doc) "/plan") :subdomain (:team/subdomain team))))
