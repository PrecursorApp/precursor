(ns pc.less
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [pc.profile]
            [fs])
  (:import [java.nio.file
            Path Paths FileSystems WatchEvent$Kind StandardWatchEventKinds
            Files WatchService FileVisitor FileVisitResult LinkOption]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]
           [java.io Reader File BufferedReader InputStreamReader]
           [java.nio.charset StandardCharsets]
           [java.net URL URI]
           [com.sun.nio.file SensitivityWatchEventModifier]))

;; Compiles less file on every save. Most of this was extracted from
;; https://github.com/montoux/lein-less

(def less-dir "resources/assets/css")
(def less-file "resources/assets/css/app.css.less")
(def output-dir "resources/public/css/")
(def output-file (str output-dir "app.css"))

(def lessc-path "node_modules/.bin/lessc")
(defn lessc-options [output-file output-dir]
  (str "-x"
       " --source-map=" output-file ".map"
       " --source-map-url=" "/css/app.css.map"
       " --source-map-less-inline"))

(defn watch-opts-cdm []
  (into-array WatchEvent$Kind [StandardWatchEventKinds/ENTRY_CREATE
                               StandardWatchEventKinds/ENTRY_DELETE
                               StandardWatchEventKinds/ENTRY_MODIFY]))

(defn compile! [& {:keys [src dest]
                   :or {src less-file dest output-file}}]
  (let [cmd (format "%s %s %s" lessc-path (lessc-options dest output-dir) src dest)
        res (shell/sh "bash" "-c" cmd)]
    (if (not= 0 (:exit res))
      (throw (Exception. (format "Couldn't compile less with %s returned exit code %s: %s %s" cmd (:exit res) (:out res) (:err res))))
      (spit dest (:out res)))))

(defn ->path [path]
  (.getPath (FileSystems/getDefault) path (into-array String [])))

(defn all-dirs
  "Returns a list of all directories in a directory"
  [dir & {:keys [recursive? max-depth matcher-fn]
          :or {recursive? true max-depth Integer/MAX_VALUE matcher-fn (constantly true)}}]
  (when (and (fs/exists? dir)
             (fs/directory? dir))
    (let [result (transient [])
          visitor (reify FileVisitor
                    (preVisitDirectory [this dir attrs]
                      (conj! result (str dir))
                      FileVisitResult/CONTINUE)
                    (visitFileFailed [this file exc]
                      FileVisitResult/CONTINUE)
                    (postVisitDirectory [this file exc]
                      FileVisitResult/CONTINUE)
                    (visitFile [this file attrs]
                      FileVisitResult/CONTINUE))]
      (Files/walkFileTree (->path dir)
                          (java.util.HashSet.)
                          (if recursive? max-depth 1)
                          visitor)
      (persistent! result))))

(defn register-watcher [dir watcher watch-opts]
  (doseq [d (all-dirs dir)]
    (.register ^Path (->path d) watcher watch-opts (into-array [SensitivityWatchEventModifier/HIGH]))))

(defn watch-files [dir callback-fn]
  (let [^WatchService watcher (.newWatchService (FileSystems/getDefault))
        watch-opts (watch-opts-cdm)]
    (register-watcher dir watcher watch-opts)
    (future
      (try
        (loop []
          (let [key (.take watcher)
                unix-files (map #(.context %) (.pollEvents key))]
            (callback-fn unix-files)
            ;; Figuring out if a new directory has been added is annoying, so
            ;; we'll just walk the tree and re-register watchers again for now
            (register-watcher dir watcher watch-opts)
            (.pollEvents key)
            (.reset key)
            (recur)))
        (catch Exception e
          (log/infof "File watcher on %s running %s canceled" dir callback-fn))
        (finally
          (.close watcher))))
    watcher))

(defn compile-less-callback! [files]
  (when (some #(re-find #"less$" (str %)) files)
    (log/infof "Found changes in %s, compiling app.css.less..." (pr-str (map str files)))
    (try
      (let [start (. System (nanoTime))]
        (compile!)
        (log/infof "Finished compiling app.css.less in %.0fms" (/ (double (- (. System (nanoTime)) start)) 1000000.0)))
      (catch Exception e
        (log/errorf e "Error compiling app.css.less")))))

(def watcher-atom (atom nil))

(defn stop-watcher! []
  (when @watcher-atom
    (.close @watcher-atom)))

(defn start-watcher! []
  (reset! watcher-atom (watch-files less-dir #'compile-less-callback!)))

(defn restart-watcher! []
  (stop-watcher!)
  (compile!)
  (start-watcher!))

(defn init []
  (when (pc.profile/compile-less?)
    (compile!)
    (start-watcher!)))
