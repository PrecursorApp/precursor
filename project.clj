(defproject pc "0.1.0-SNAPSHOT"
  :description "Precursor"
  :url "https://precursorapp.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [inflections "0.12.2"]

                 [defpage "0.1.4" :exclusions [ring
                                               clout
                                               compojure]]
                 [compojure "1.5.0"]
                 [cheshire "5.6.1"]
                 [clj-time "0.12.0"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [javax.servlet/servlet-api "2.5"]
                 [slingshot "0.12.2"]
                 [hiccup "1.0.5"]
                 ;; pdfs
                 [clj-pdf "2.1.6" :exclusions [xml-apis]]

                 [org.clojure/tools.logging "0.3.1"]
                 [log4j "1.2.17"]
                 [log4j/apache-log4j-extras "1.1"]
                 [org.slf4j/slf4j-api "1.7.10"]
                 [org.slf4j/slf4j-log4j12 "1.7.10" :exclusions [log4j]]

                 [clj-statsd "0.3.11"]

                 [cider/cider-nrepl "0.12.0"]
                 [clj-http "2.2.0"]
                 ;; TODO: upgrade
                 [com.datomic/datomic-pro "0.9.5130" :exclusions [org.slf4j/slf4j-nop
                                                                  org.slf4j/slf4j-api
                                                                  com.amazonaws/aws-java-sdk
                                                                  com.google.guava/guava]]

                 [clojurewerkz/spyglass "1.1.0"]

                 [amazonica "0.3.58" :exclusions [com.google.guava/guava]]
                 [com.draines/postal "1.11.3" :exclusions [commons-codec]]

                 [ring/ring "1.3.2" :exclusions [hiccup
                                                 org.clojure/java.classpath]]

                 ;; uses a url-safe token
                 [dwwoelfel/ring-anti-forgery "1.0.0-cbd219138abf4e9916a51caa7629c357b5d164af" :exclusions [hiccup]]

                 ;; adds support for on-complete callback
                 [precursor/sente "1.4.1-a28061fff118ea3313f99ae6afb89f064c35c9b2"]
                 [clj-stacktrace "0.2.8"]
                 [com.cognitect/transit-clj "0.8.285"]

                 [org.immutant/web "2.1.4"
                  :exclusions [org.jboss.logging/jboss-logging
                               org.slf4j/slf4j-nop
                               org.slf4j/slf4j-api
                               org.slf4j/slf4j-simple
                               org.slf4j/slf4j-log4j12
                               ch.qos.logback/logback-classic]]

                 [org.clojure/tools.reader "1.0.0-beta1"]
                 ;;[com.google.guava/guava "18.0"]

                 [schejulure "1.0.1"]

                 [prismatic/schema "1.1.1"]

                 [org.clojars.pallix/batik "1.7.0"]

                 [com.cemerick/pomegranate "0.3.1" :exclusions [org.codehaus.plexus/plexus-utils
                                                                org.jsoup/jsoup]]

                 [com.novemberain/pantomime "2.8.0"]

                 [crypto-equality "1.0.0"]
                 [mvxcvi/clj-pgp "0.8.3" :exclusions [riddley]]

                 [me.raynes/fs "1.4.6" :exclusions [org.apache.commons/commons-compress]]

                 [datascript "0.15.0"]

                 [ankha "0.1.5.1-479897"]
                 [org.clojure/clojurescript "1.9.36"]
                 [org.clojure/core.async "0.2.374"]

                 [cljs-http "0.1.41" :exclusions [noencore]]
                 [com.cognitect/transit-cljs "0.8.237"]

                 ;; Use yaks/om for the pattern tag (it's in React,
                 ;; but not Om yet)
                 ;;[om "0.6.4"]

                 [precursor/react "0.12.2-7-5-new-tags"]
                 [precursor/om-i "0.1.8"]

                 [sablono "0.3.4" :exclusions [cljsjs/react]]
                 [secretary "1.2.3"]
                 [com.andrewmcveigh/cljs-time "0.4.0"]
                 [com.cemerick/url "0.1.1"]
                 [hiccups "0.3.0"]

                 [dwwoelfel/weasel "0.6.1-09967ffe4a4a849a3d3988ffd241ea0770ab16c9"] ;; repl
                 [com.cemerick/piggieback "0.2.1"]

                 [figwheel "0.5.3-2"]

                 ;; Frontend tests
                 [com.cemerick/clojurescript.test "0.3.3"]

                 ;; dependencies of dependencies (make lein pedantic happy)
                 [commons-logging "1.2"]
                 ]

  :repositories ^:replace [["prcrsr-s3-releases" {:url "s3p://prcrsr-jars/releases"
                                                  :sign-releases false ;; TODO put a gpg key on CI
                                                  :username [:gpg
                                                             :env/prcrsr_jars_username
                                                             "AKIAIQXSSVZAOLOC5KZA"]
                                                  :passphrase [:gpg
                                                               :env/prcrsr_jars_password
                                                               "VgIfEWLuHOIMtSvOKm5q00t/XjGgsok8AvNIcNhq"]
                                                  :snapshots false}]

                           ["prcrsr-s3-snapshots" {:url "s3p://prcrsr-jars/snapshots"
                                                   :sign-releases false ;; TODO put a gpg key on CI
                                                   :username [:gpg
                                                              :env/prcrsr_jars_username
                                                              "AKIAIQXSSVZAOLOC5KZA"]
                                                   :passphrase [:gpg
                                                                :env/prcrsr_jars_password
                                                                "VgIfEWLuHOIMtSvOKm5q00t/XjGgsok8AvNIcNhq"]
                                                   :snapshots true}]
                           ["central" {:url "https://repo1.maven.org/maven2/" :snapshots false}]
                           ["clojars" {:url "https://clojars.org/repo/"}]]

  :figwheel {:http-server-root "public"
             :server-port 3448
             :css-dirs ["resources/public/css"]}

  :plugins [[lein-cljsbuild "1.1.3"]
            [lein-figwheel "0.5.3-2" :exclusions [org.codehaus.plexus/plexus-utils
                                                  org.clojure/clojure
                                                  commons-codec]]
            [circle/lein-deploy-deps "0.1.3"]
            [circle/s3-wagon-private "1.2.2" :exclusions [commons-codec
                                                          org.apache.httpcomponents/httpclient]]]

  :exclusions [[org.clojure/clojure]
               [org.clojure/clojurescript]
               [org.slf4j/log4j-over-slf4j]
               [org.clojure/tools.nrepl]
               [org.clojure/tools.reader]
               [commons-logging]]

  ;; prevent leiningen from loading its version of nrepl (!)
  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.10"
                                   :exclusions [org.clojure/clojure]]]}}

  :pedantic? :abort

  :main ^:skip-aot pc.init

  :jar-name "pc.jar"
  :uberjar-name "pc-standalone.jar"

  :jvm-opts ["-Djava.net.preferIPv4Stack=true"
             "-server"
             "-XX:MaxPermSize=256m"
             "-XX:+UseConcMarkSweepGC"
             "-Xss1m"
             "-Xmx1024m"
             "-XX:+CMSClassUnloadingEnabled"
             "-Dfile.encoding=UTF-8"]


  :repl-options {:init-ns pc.repl}

  :clean-targets ^{:protect false} [:target-path "resources/public/cljs/"]

  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src-cljs"
                                       "dev-cljs"
                                       "yaks/om/src"]
                        :figwheel {:websocket-host "localhost"
                                   :on-jsload "frontend.dev/jsload"}
                        :compiler {:output-to "resources/public/cljs/out/frontend-dev.js"
                                   :output-dir "resources/public/cljs/out"
                                   :optimizations :none
                                   :source-map true}}
                       {:id "production"
                        :source-paths ["src-cljs" "yaks/om/src"]
                        :compiler {:pretty-print false
                                   ;; Datascript https://github.com/tonsky/datascript/issues/57
                                   :warnings {:single-segment-namespace false}
                                   :output-to "resources/public/cljs/production/frontend.js"
                                   :output-dir "resources/public/cljs/production"
                                   :optimizations :advanced
                                   :externs ["src-cljs/js/analytics-externs.js"
                                             "src-cljs/js/w3c_rtc-externs.js"
                                             "src-cljs/js/w3c_audio-externs.js"]
                                   :source-map "resources/public/cljs/production/sourcemap-frontend.map"}}]})
