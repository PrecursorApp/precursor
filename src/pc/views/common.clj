(ns pc.views.common
  (:require [pc.assets]
            [pc.profile :refer (prod-assets?)]))

(defn cdn-path [path]
  (if (prod-assets?)
    (str pc.assets/cdn-base-url path)
    path))
