(ns frontend.utils.github
  (:require [clojure.string :as string]
            [goog.string :as gstring]
            [goog.string.format]
            [cemerick.url :refer [url]]))

;; TODO convert CI.github
(defn auth-url [& {:keys [scope]
                   :or {scope ["user:email" "repo"]}}]
  (js/CI.github.authUrl (clj->js scope)))

(defn make-avatar-url [{:keys [avatar_url gravatar_id login]} & {:keys [size] :or {size 200}}]
  "Takes a map of user/org data and returns a url for the desired size of the user's avatar

  Ideally the map contains an :avatar_url key with a github avatar url, but will fall back to the best that can be done with :gravatar_id and :login if not - this is intended for the the gap while the related backend is deploying"
  (if-not (string/blank? avatar_url)
    (-> (url avatar_url)
        (assoc-in [:query "s"] size)
        str)

    ;; default to gravatar defaulting to github identicon
    (-> (url "https://secure.gravatar.com/avatar/" gravatar_id)
        (assoc-in [:query "s"] size)
        (assoc-in [:query "d"] (str "https://identicons.github.com/" login ".png"))
        str)))
