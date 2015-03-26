(ns pc.views.blog.dribbble-card-test
  (:require [pc.views.blog.common :as common]
            [pc.views.common :refer (cdn-path)]))

(defn dribbble-card-test []
  {:title "Dribbble card test"
   :blurb "Just testing out Dribbble cards"
   :author "Daniel"
   :body
   (list
    [:article
     (common/dribbble-card "dannykingme")]
    )})
