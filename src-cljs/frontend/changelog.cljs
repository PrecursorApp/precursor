(ns frontend.changelog
  (:require [goog.dom.xml :as xml]))


(defn attr-text [item attr]
  (when-let [node (xml/selectSingleNode item attr)]
    (.-textContent node)))

(defn parse-item [item]
  (let [type (attr-text item "type")
        ;; "type" was illegal RSS, now uses first category.
        categories (map #(.-textContent %) (xml/selectNodes item "category"))
        [type & categories] (if type (cons type categories) categories)
        ;; author should be an email address, dc:creator can just be a name
        author (or (attr-text item "author")
                   (attr-text item "dc:creator"))]
    {:title (attr-text item "title")
     :description (attr-text item "description")
     :link (attr-text item "link")
     :author author
     :pubDate (attr-text item "pubDate")
     :guid (attr-text item "guid")
     :type type
     :categories categories}))

(defn parse-changelog-document [xml-document-object]
  (let [items (xml/selectNodes xml-document-object "//item")]
    (map parse-item items)))
