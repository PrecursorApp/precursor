(ns pc.views.test-blog
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [hiccup.core :as hiccup]
            [pc.views.blog :as blog]))

(defn check-non-empty-string [thing]
  (is (string? thing))
  (is (not (str/blank? thing))))

(deftest all-posts-have-all-fields
  (doseq [slug blog/slugs]
    (testing slug
      (let [{:keys [title blurb author body] :as content} ((blog/post-fn slug))]
        (check-non-empty-string title)
        (check-non-empty-string blurb)
        (check-non-empty-string author)
        (is (seq body))
        (check-non-empty-string (hiccup/html body))))))
