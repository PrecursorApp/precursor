(defproject pc "0.1.0-SNAPSHOT"
  :description "CircleCI's frontend app"
  :url "https://circleci.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [inflections "0.8.2"]

                 [org.clojars.dwwoelfel/stefon "0.5.0-3198d1b33637d6bd79c7415b01cff843891ebfd4"]
                 [compojure "1.1.8"]
                 [cheshire "5.2.0"]
                 [clj-time "0.6.0"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [javax.servlet/servlet-api "2.5"]
                 [slingshot "0.10.3"]
                 [hiccup "1.0.4"]
                 [org.clojure/tools.logging "0.2.6"]
                 [log4j "1.2.16"]
                 [log4j/apache-log4j-extras "1.1"]
                 [org.slf4j/slf4j-api "1.6.2"]
                 [org.slf4j/slf4j-log4j12 "1.6.2"]
                 [cider/cider-nrepl "0.7.0-SNAPSHOT"]
                 [clj-http "1.0.0"]

                 [ring/ring "1.2.2"]
                 [http-kit "2.1.18"]
                 [fs "0.11.1"]

                 [ankha "0.1.2"]
                 [org.clojure/clojurescript "0.0-2280"]
                 [org.clojure/google-closure-library "0.0-20140226-71326067"]
                 [com.google.javascript/closure-compiler "v20140625"]
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]
                 [cljs-ajax "0.2.6"]

                 ;; Use yaks/om for the pattern tag (it's in React,
                 ;; but not Om yet)
                 ;;[om "0.6.4"]

                 [com.facebook/react "0.11.2"] ;; include for externs
                 [prismatic/dommy "0.1.2"]
                 [sablono "0.2.16"]
                 [secretary "1.2.0"]
                 [com.andrewmcveigh/cljs-time "0.1.5"]
                 [com.cemerick/url "0.1.1"]
                 [weasel "0.3.0"] ;; repl
                 ;; Frontend tests
                 [com.cemerick/clojurescript.test "0.3.0"]]

  :plugins [[lein-cljsbuild "1.0.4-SNAPSHOT"]
            [com.cemerick/austin "0.1.4"]]

  :exclusions [[org.clojure/clojure]
               [org.clojure/clojurescript]]

  :main pc.init

  :jvm-opts ["-Djava.net.preferIPv4Stack=true"
             "-server"
             "-XX:MaxPermSize=256m"
             "-XX:+UseConcMarkSweepGC"
             "-Xss1m"
             "-Xmx1024m"
             "-XX:+CMSClassUnloadingEnabled"
             "-Djava.library.path=target/native/macosx/x86_64:target/native/linux/x86_64:target/native/linux/x86"
             "-Djna.library.path=target/native/macosx/x86_64:target/native/linux/x86_64:target/native/linux/x86"
             "-Dfile.encoding=UTF-8"]

  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src-cljs"
                                       "yaks/om/src"]
                        :compiler {:output-to "resources/public/cljs/out/frontend-dev.js"
                                   :output-dir "resources/public/cljs/out"
                                   :optimizations :none
                                   :source-map "resources/public/cljs/out/sourcemap-dev.js"}}
                       {:id "whitespace"
                        :source-paths ["src-cljs"
                                       "yaks/om/src"]
                        :compiler {:output-to "resources/public/cljs/whitespace/frontend.js"
                                   :output-dir "resources/public/cljs/whitespace"
                                   :optimizations :whitespace
                                   ;; :source-map "resources/public/cljs/whitespace/sourcemap.js"
                                   }}

                       {:id "test"
                        :source-paths ["src-cljs" "test-cljs" "yaks/om/src"]
                        :compiler {:pretty-print true
                                   :output-to "resources/public/cljs/test/frontend-dev.js"
                                   :output-dir "resources/public/cljs/test"
                                   :optimizations :advanced
                                   :externs ["test-js/externs.js"
                                             "src-cljs/js/react-externs.js"
                                             "src-cljs/js/pusher-externs.js"
                                             "src-cljs/js/ci-externs.js"
                                             "src-cljs/js/analytics-externs.js"
                                             "src-cljs/js/intercom-jquery-externs.js"]
                                   :source-map "resources/public/cljs/test/sourcemap-dev.js"}}
                       {:id "production"
                        :source-paths ["src-cljs" "yaks/om/src"]
                        :compiler {:pretty-print false
                                   :output-to "resources/public/cljs/production/frontend.js"
                                   :output-dir "resources/public/cljs/production"
                                   :optimizations :advanced
                                   :externs ["react/externs/react.js"
                                             "src-cljs/js/pusher-externs.js"
                                             "src-cljs/js/ci-externs.js"
                                             "src-cljs/js/analytics-externs.js"
                                             "src-cljs/js/intercom-jquery-externs.js"]
                                   ;; :source-map "resources/public/cljs/production/sourcemap-frontend.js"
                                   }}]
              :test-commands {"frontend-unit-tests"
                              ["node_modules/karma/bin/karma" "start" "karma.conf.js" "--single-run"]}})
