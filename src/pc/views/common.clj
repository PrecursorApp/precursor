(ns pc.views.common
  (:require [pc.assets]
            [pc.profile :refer (prod-assets?)]))

(defn cdn-base-url []
  (if (prod-assets?)
    (pc.profile/cdn-base-url)
    ""))

(defn cdn-path [path]
  (str (cdn-base-url) path))

(defn external-cdn-path [path]
  (str "https://precursor.storage.googleapis.com" path))

(defn head-style [] "
#om-app {
  background-image:
    linear-gradient(to bottom, rgba(85, 85, 85, .25) 10%, rgba(85, 85, 85, 0) 10%),
    linear-gradient(to right, rgba(85, 85, 85, .25) 10%, rgba(85, 85, 85, 0) 10%),
    linear-gradient(to bottom, rgba(85, 85, 85, .57) 1%, rgba(85, 85, 85, 0) 1%),
    linear-gradient(to right, rgba(85, 85, 85, .57) 1%, rgba(85, 85, 85, 0) 1%);
  background-size: 10px 10px, 10px 10px, 100px 100px, 100px 100px;
  background-color: rgba(51, 51, 51, 1);
  min-height: 100vh;
}
#om-app:active {
  cursor: wait;
}
")
