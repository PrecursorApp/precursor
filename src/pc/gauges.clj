(ns pc.gauges
  "Periodically publishes metrics about this box to statsd."
  (:require [clojure.tools.logging :refer (debugf)]
            [clojure.string :as str]
            [clj-time.core :as time]
            [clj-statsd :as statsd]
            [pc.utils])
  (:import java.lang.management.ManagementFactory))

(defn gauges []
  (let [memory (ManagementFactory/getMemoryMXBean)
        gcs (ManagementFactory/getGarbageCollectorMXBeans)
        thread (ManagementFactory/getThreadMXBean)
        solo-executor (clojure.lang.Agent/soloExecutor)
        pooled-executor (clojure.lang.Agent/pooledExecutor)
        metrics (flatten
                 [
                   {:path "jvm.memory.total.init"
                    :value (+ (-> memory .getHeapMemoryUsage .getInit)
                              (-> memory .getNonHeapMemoryUsage .getInit))}
                   {:path "jvm.memory.total.used"
                    :value (+ (-> memory .getHeapMemoryUsage .getUsed)
                              (-> memory .getNonHeapMemoryUsage .getUsed))}
                   {:path "jvm.memory.total.max"
                    :value (+ (-> memory .getHeapMemoryUsage .getMax)
                              (-> memory .getNonHeapMemoryUsage .getMax))}
                   {:path "jvm.memory.total.committed"
                    :value (+ (-> memory .getHeapMemoryUsage .getCommitted)
                              (-> memory .getNonHeapMemoryUsage .getCommitted))}
                   {:path "jvm.memory.heap.init"
                    :value (-> memory .getHeapMemoryUsage .getInit)}
                   {:path "jvm.memory.heap.used"
                    :value (-> memory .getHeapMemoryUsage .getUsed)}
                   {:path "jvm.memory.heap.max"
                    :value (-> memory .getHeapMemoryUsage .getMax)}
                   {:path "jvm.memory.heap.committed"
                    :value (-> memory .getHeapMemoryUsage .getCommitted)}
                   {:path "jvm.memory.non-heap.init"
                    :value (-> memory .getNonHeapMemoryUsage .getInit)}
                   {:path "jvm.memory.non-heap.used"
                    :value (-> memory .getNonHeapMemoryUsage .getUsed)}
                   {:path "jvm.memory.non-heap.max"
                    :value (-> memory .getNonHeapMemoryUsage .getMax)}
                   {:path "jvm.memory.non-heap.committed"
                    :value (-> memory .getNonHeapMemoryUsage .getCommitted)}
                   (for [gc gcs]
                     [{:path (str "jvm.gc." (-> gc .getName str/lower-case) ".count")
                       :value (-> gc .getCollectionCount)}
                      {:path (str "jvm.gc." (-> gc .getName str/lower-case) ".time")
                       :value (-> gc .getCollectionTime)}])
                   {:path "jvm.thread.count"
                    :value (-> thread .getThreadCount)}
                   {:path "jvm.thread.daemon.count"
                    :value (-> thread .getDaemonThreadCount)}
                   (for [thread-state (Thread$State/values)]
                     {:path (str "jvm.thread." (-> thread-state str str/lower-case) ".count")
                      :value (count
                               (filter #(and % (= thread-state (.getThreadState %)))
                                       (.getThreadInfo thread
                                                       (-> thread .getAllThreadIds))))})
                   (for [[executor description]
                         [[solo-executor "agent-pool.send-off"]
                          [pooled-executor "agent-pool.send"]]]
                     [{:path (str "jvm." description ".queue-depth")
                       :value (-> executor .getQueue .size)}
                      {:path (str "jvm." description ".active")
                       :value (.getActiveCount executor)}
                      {:path (str "jvm." description ".tasks")
                       :value (.getTaskCount executor)}
                      {:path (str "jvm." description ".completed-tasks")
                       :value (.getCompletedTaskCount executor)}
                      {:path (str "jvm." description ".size")
                       :value (.getPoolSize executor)}
                      {:path (str "jvm." description ".core-size")
                       :value (.getCorePoolSize executor)}
                      {:path (str "jvm." description ".largest-size")
                       :value (.getLargestPoolSize executor)}
                      {:path (str "jvm." description ".maximum-size")
                       :value (.getMaximumPoolSize executor)}])])]
    metrics))

(defn publish-gauges []
  (doseq [{:keys [path value]} (gauges)]
    (statsd/gauge path (long value))))

(defn init []
  (pc.utils/safe-schedule {:minute (range (rand-int 5) 60 5)} #'publish-gauges))
