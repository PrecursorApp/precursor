(ns pc.logging
  (:require [pc.profile :as profile])
  (:import org.apache.commons.logging.LogFactory
           (org.apache.log4j Logger
                             BasicConfigurator
                             EnhancedPatternLayout
                             Level
                             ConsoleAppender
                             FileAppender
                             SimpleLayout)
           org.apache.log4j.spi.RootLogger
           (org.apache.log4j.rolling TimeBasedRollingPolicy
                                     RollingFileAppender)))

(defn layout []
  (EnhancedPatternLayout. "%d{MMM d HH:mm:ssZ} %p %c %m%n"))

(defn init []
  (let [rolling-policy (doto (TimeBasedRollingPolicy.)
                         (.setActiveFileName  "pc.log" )
                         (.setFileNamePattern "log/pc-%d{yyyy-MM-dd}.log.gz")
                         (.activateOptions))
        log-appender (doto (RollingFileAppender.)
                       (.setRollingPolicy rolling-policy)
                       (.setLayout (layout))
                       (.activateOptions))
        root-logger (Logger/getRootLogger)]

    (.removeAllAppenders root-logger)
    (.addAppender root-logger log-appender)
    ;; no use in logging to console when we're in production
    (when (profile/log-to-console?)
      (.addAppender root-logger (ConsoleAppender. (layout))))
    (.setLevel root-logger Level/INFO)
    (.setLevel (Logger/getLogger "com.amazonaws") Level/WARN)))
