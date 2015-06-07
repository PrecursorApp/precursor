(ns pc.admin.icons)

(defn hex-str->char [hex-str]
  (char (read-string (str "0x" hex-str))))

(defn parse-icon-map [css-string]
  (reduce (fn [acc [m classes hex-str]]
            (merge acc
                   (into {} (for [[_ class] (re-seq #"\.(fa-[^:]+)" classes)]
                              [class (hex-str->char hex-str)]))))
          {} (re-seq #"}[^}]?+(\.fa-[^{]+) \{\s+content: \"\\(.+)\"" css-string)))

;; Get the file from https://github.com/FortAwesome/Font-Awesome/blob/master/css/font-awesome.css
(defn refresh-icon-map-cljs [fa-css-file]
  (spit "src-cljs/frontend/utils/font_map.cljs" (str "(ns frontend.utils.font-map)\n\n(def class->unicode\n  "
                                                     (clojure.string/replace (pr-str (parse-icon-map (slurp fa-css-file)))
                                                                             ","
                                                                             "\n  ")
                                                     ")\n"))
  (spit "src/pc/util/font_map.clj" (str "(ns pc.util.font-map)\n\n(def class->unicode\n  "
                                        (clojure.string/replace (pr-str (parse-icon-map (slurp fa-css-file)))
                                                                ","
                                                                "\n  ")
                                        ")\n")))
