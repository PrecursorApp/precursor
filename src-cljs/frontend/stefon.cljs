(ns frontend.stefon
  "Hacks to replicate stefon's .ref helpers"
  (:require [frontend.utils :as utils :include-macros true]))

(defn data-uri
  "Returns the data-uri version of the image for the given url.
   Image must be specified in resources/assets/js/stefon-hack-for-om.coffee.ref!"
  [url]
  (let [uri-string (-> js/window
                       (aget "stefon_hack_for_om")
                       (aget "data_uris")
                       (aget url))]
    (if uri-string
      uri-string
      (utils/mwarn "Unable to find data-uri for" url
                   "Is it defined in resources/assets/js/stefon-hack-for-om.coffee.ref?"))))

(defn asset-path
  "Returns the assetified version of the path for the given url. Also returns
   the link to the cdn version if we're in the right environment.
   Path must be specified in resources/assets/js/stefon-hack-for-om.coffee.ref!"
  [path]
  (let [uri-string (-> js/window
                       (aget "stefon_hack_for_om")
                       (aget "asset_paths")
                       (aget path))]
    (if uri-string
      (utils/cdn-path uri-string)
      (utils/mwarn "Unable to find asset-path for" path
                   "Is it defined in resources/assets/js/stefon-hack-for-om.coffee.ref?"))))
