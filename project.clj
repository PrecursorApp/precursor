(defproject pc "0.1.0-SNAPSHOT"
  :description "Precursor"
  :url "https://precursorapp.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-RC1"]
                 [inflections "0.9.13"]

                 [defpage "0.1.3" :exclusions [ring
                                               clout
                                               compojure]]
                 [compojure "1.3.3"]
                 [cheshire "5.4.0"]
                 [clj-time "0.9.0"]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [javax.servlet/servlet-api "2.5"]
                 [slingshot "0.12.2"]
                 [hiccup "1.0.5"]
                 [clj-pdf "2.0.3"]

                 [org.clojure/tools.logging "0.3.1"]
                 [log4j "1.2.17"]
                 [log4j/apache-log4j-extras "1.1"]
                 [org.slf4j/slf4j-api "1.7.10"]
                 [org.slf4j/slf4j-log4j12 "1.7.10" :exclusions [log4j]]

                 [clj-statsd "0.3.11"]

                 [cider/cider-nrepl "0.8.2" :exclusions [org.clojure/tools.reader]]
                 [clj-http "1.1.1" :exclusions [commons-codec
                                                clj-tuple potemkin
                                                org.jsoup/jsoup]]
                 [com.datomic/datomic-pro "0.9.5130" :exclusions [org.slf4j/slf4j-nop
                                                                  org.slf4j/slf4j-api
                                                                  com.amazonaws/aws-java-sdk]]
                 [org.postgresql/postgresql "9.4-1200-jdbc41" :exclusions [org.slf4j/slf4j-simple]]
                 [clojurewerkz/spyglass "1.1.0"]

                 [amazonica "0.3.22"]
                 [com.draines/postal "1.11.3" :exclusions [commons-codec]]

                 [ring/ring "1.3.2" :exclusions [hiccup
                                                 org.clojure/java.classpath]]
                 ;; uses a url-safe token
                 [dwwoelfel/ring-anti-forgery "1.0.0-cbd219138abf4e9916a51caa7629c357b5d164af" :exclusions [hiccup]]
                 [http-kit "2.1.18-c9c0b155a4ab05630d332a7d2da0aaf433889772"]

                 ;; adds support for on-complete callback
                 [precursor/sente "1.4.1-a28061fff118ea3313f99ae6afb89f064c35c9b2"]
                 [clj-stacktrace "0.2.8"]

                 [org.immutant/web "2.x.incremental.586"
                  :exclusions [org.clojure/java.classpath
                               org.jboss.logging/jboss-logging
                               org.slf4j/slf4j-nop
                               org.slf4j/slf4j-api
                               org.slf4j/slf4j-simple
                               org.slf4j/slf4j-log4j12
                               ch.qos.logback/logback-classic]]

                 [org.clojure/tools.reader "0.9.2"]
                 [com.google.guava/guava "18.0"]

                 [schejulure "1.0.1"]

                 [org.clojars.pallix/batik "1.7.0"]

                 ;; needed to make lein pedantic happy
                 [org.codehaus.plexus/plexus-utils "2.0.6"]
                 [com.cemerick/pomegranate "0.3.0"  :exclusions [org.codehaus.plexus/plexus-utils
                                                                 org.jsoup/jsoup]]

                 [com.novemberain/pantomime "2.6.0"]

                 [crypto-equality "1.0.0"]

                 [me.raynes/fs "1.4.6" :exclusions [org.apache.commons/commons-compress]]

                 [datascript "0.11.4"]

                 [ankha "0.1.4"]
                 [org.clojure/clojurescript "0.0-3308"]
                 [precursor/core.async "0.1.361.0-d8047c-alpha"]

                 [cljs-http "0.1.35" :exclusions [noencore]]
                 [com.cognitect/transit-cljs "0.8.220"]

                 ;; Use yaks/om for the pattern tag (it's in React,
                 ;; but not Om yet)
                 ;;[om "0.6.4"]

                 [precursor/react "0.12.2-7-5-new-tags"]
                 [precursor/om-i "0.1.7"]

                 [sablono "0.3.4" :exclusions [cljsjs/react]]
                 [secretary "1.2.3"]
                 [com.andrewmcveigh/cljs-time "0.3.4"]
                 [com.cemerick/url "0.1.1"]
                 [hiccups "0.3.0"]

                 [dwwoelfel/weasel "0.6.1-09967ffe4a4a849a3d3988ffd241ea0770ab16c9"] ;; repl
                 [com.cemerick/piggieback "0.1.5"]

                 ;; needed to make lein pedantic happy
                 [commons-codec "1.10"]
                 [figwheel "0.2.9" :exclusions [org.codehaus.plexus/plexus-utils
                                                commons-codec]]

                 ;; Frontend tests
                 [com.cemerick/clojurescript.test "0.3.0"]]

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
                           ["clojars" {:url "https://clojars.org/repo/"}]
                           ["Immutant incremental builds" {:url "http://downloads.immutant.org/incremental/"}]]

  :figwheel {:http-server-root "public"
             :server-port 3448
             :css-dirs ["resources/public/css"]}

  :plugins [[lein-cljsbuild "1.0.5"]
            [lein-figwheel "0.2.9" :exclusions [org.codehaus.plexus/plexus-utils
                                                org.clojure/clojure
                                                commons-codec]]
            [circle/lein-deploy-deps "0.1.3"]
            [circle/s3-wagon-private "1.2.2" :exclusions [commons-codec
                                                          org.apache.httpcomponents/httpclient]]]

  :exclusions [[org.clojure/clojure]
               [org.clojure/clojurescript]
               [org.slf4j/log4j-over-slf4j]
               [org.clojure/tools.nrepl]]

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
                        :compiler { ;; Datascript https://github.com/tonsky/datascript/issues/57
                                   :warnings {:single-segment-namespace false}
                                   :output-to "resources/public/cljs/out/frontend-dev.js"
                                   :output-dir "resources/public/cljs/out"
                                   :optimizations :none
                                   :source-map "resources/public/cljs/out/sourcemap-frontend.map"}}
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
