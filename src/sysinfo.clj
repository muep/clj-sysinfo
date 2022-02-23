(ns sysinfo
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as server]
            [reitit.ring :as ring]
            [muuntaja.middleware :as muuntaja]
            [hikari-cp.core :as hikari]
            [clojure.java.jdbc :as jdbc])
  (:import java.sql.SQLException
           (java.lang.management GarbageCollectorMXBean
                                 ManagementFactory
                                 MemoryUsage))
  (:gen-class))

(defn str->cpu-time [s]
  (/ (Integer/parseInt s) 100))

(defn str->ram-kib [s]
  (* (Integer/parseInt s) 4))

;; Stock slurp gets confused by something in the behavior of procfs,
;; but opening a FileInputStream and slurping that seems to work ok.
(defn slurp-proc [path]
  (with-open [f (java.io.FileInputStream. path)]
    (slurp f)))

(defn pick-keys [m key-map]
  (into {} (map (fn [[kout kin]] [kout (get m kin)]) key-map)))


(defn proctbl->map [txt]
  (as-> txt v
    (str/split v #"\n")
    (map (fn [row] (str/split row #":\s*")) v)
    (into {} v)))

(def pool-names
  {"CodeHeap 'non-profiled nmethods'" :codeheap-non-profiled-nmethods
   "CodeHeap 'profiled nmethods'"     :codeheap-profiled-nmethods
   "CodeHeap 'non-nmethods'"          :codeheap-non-nmethods
   "Metaspace"                        :metaspace
   "Compressed Class Space"           :compressed-class-space
   "G1 Eden Space"                    :g1-eden-space
   "G1 Old Gen"                       :g1-old-gen
   "G1 Survivor Space"                :g1-survivor-space
   "G1 Old Generation"                :g1-old-generation
   "G1 Young Generation"              :g1-young-generation})

(defn pool-name [p]
  (get pool-names p p))

(defn memory-usage->map [^MemoryUsage x]
  {:init (.getInit x)
   :used (.getUsed x)
   :committed (.getCommitted x)
   :max (.getMax x)})

(defn heap-stat []
  (let [mxb (ManagementFactory/getMemoryMXBean)
        heap-usage (-> mxb .getHeapMemoryUsage memory-usage->map)
        nonheap-usage (-> mxb .getNonHeapMemoryUsage memory-usage->map)
        pool (into {}
                   (map (fn [b] [(-> b .getName  pool-name)
                                 (-> b .getUsage memory-usage->map)])
                        (ManagementFactory/getMemoryPoolMXBeans)))
        gc (into {} (map (fn [^GarbageCollectorMXBean b]
                           [(-> b .getName pool-name)
                            {:count (.getCollectionCount b)
                             :time (.getCollectionTime b)}])
                         (ManagementFactory/getGarbageCollectorMXBeans)))]
    {:gc gc
     :heap-usage heap-usage
     :nonheap-usage nonheap-usage
     :pool pool}))

(def meminfo-fields {:mem-total "MemTotal"
                     :mem-free "MemFree"
                     :mem-available "MemAvailable"
                     :buffers "Buffers"
                     :cached "Cached"
                     :swap-total "SwapTotal"
                     :swap-free "SwapFree"})

(defn meminfo []
  (-> (slurp-proc "/proc/meminfo")
      proctbl->map
      (pick-keys meminfo-fields)))

(defn proc-stat [proc]
  (let [pieces (-> (slurp-proc (str "/proc/" proc "/stat"))
                   (str/split #"\s+"))
        user (-> pieces (get 13) str->cpu-time)
        sys (-> pieces (get 14) str->cpu-time)]
    {:pid (-> pieces (get 0) Integer/parseInt)
     :rss (-> pieces (get 23) str->ram-kib)
     :user user
     :sys sys
     :time (+ user sys)}))

(defn runtime-stat []
  (let [rt (Runtime/getRuntime)]
    {:cpu-count (.availableProcessors rt)
     :free-memory (.freeMemory rt)
     :max-memory (.maxMemory rt)
     :total-memory (.totalMemory rt)
     :version (.toString (Runtime/version))}))

(defn sys-stat []
  {:heap (heap-stat)
   :meminfo (meminfo)
   :process (proc-stat "self")
   :runtime (runtime-stat)})

(defn get-sys-stat [req]
  {:status 200
   :body (sys-stat)})

(defn get-sys-stat-id [{{:keys [id]} :path-params :keys [db]}]
  (let [res (-> (jdbc/query db ["select id, stat_time::text, stat from stat where id = ?::integer;" id])
                first
                )]
    {:status 200
     :body (update res :stat (fn [s] (json/read-str s :key-fn keyword)))}))

(defn put-sys-stat [{:keys [db]}]
  (let [st (sys-stat)
        res (jdbc/insert! db "stat" {:stat (json/write-str st)})]
    {:status 200
     :body res}))

(defn wrap-db [db]
  (fn [handler]
    (fn [req]
      (handler (assoc req :db db)))))

(defn app [db file-path]
  (ring/ring-handler
   (ring/router
    [["/sys-stat" {:get get-sys-stat
                   :put put-sys-stat}]
     ["/sys-stat/:id" {:get get-sys-stat-id}]
     ["/files/*" (ring/create-file-handler {:root file-path})]]
    {:data
     {:middleware
      [(wrap-db db)
       muuntaja/wrap-format-response]}})))

;; Note: no support for all options. Only those that have been needed
;; so far, and even these are processed in a pretty haphazard way.
(defn libpq->jdbc [uri]
  (let [[match username password hostname port dbname]
        (re-find #"postgres://(?<username>\w+):(?<password>\w+)@(?<host>[\w.-]+):(?<port>\w+)/(?<database>\w+)"
                 uri)]
    (assert (not (nil? match)))
    (str "jdbc:postgresql://"
         hostname ":" port "/" dbname
         "?user=" username "&password=" password)))

(defn init-db [db]
  (try
    (jdbc/query db "select version from layout_version;")
    (catch SQLException e
      (jdbc/db-do-commands db
                           ["create table stat(id serial unique not null, stat_time timestamp with time zone not null default now(), stat text not null);"
                            "create table layout_version(version integer not null);"
                            "insert into layout_version(version) values (1);"]))))

(defn run [{:keys [db-url file-path port thread]}]
  (let [db {:datasource (hikari/make-datasource {:jdbc-url (libpq->jdbc db-url)})}
        opts (cond-> {:port port}
               thread (assoc :thread thread))]
    (init-db db)
    (log/info "(org.httpkit.server/run-server (app db" file-path ")" opts ")")
    (server/run-server (app db file-path) opts)))

(defn env-int [name fback]
  (if-let [v (System/getenv name)]
    (Integer/parseInt v)
    fback))

(defn -main []
  (run {:db-url (System/getenv "DATABASE_URL")
        :file-path (System/getenv "FILE_PATH")
        :port (env-int "LISTEN_PORT" 8080)
        :thread (env-int "THREAD_COUNT" nil)}))
