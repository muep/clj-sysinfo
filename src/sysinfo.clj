(ns sysinfo
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [org.httpkit.server :as server]
            [reitit.ring :as ring]
            [muuntaja.middleware :as muuntaja]
            [hikari-cp.core :as hikari]
            [clojure.java.jdbc :as jdbc])
  (:import java.sql.SQLException)
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
                   (str/split #"\s+"))]
    {:pid (-> pieces (get 0) Integer/parseInt)
     :rss (-> pieces (get 23) str->ram-kib)
     :user (-> pieces (get 13) str->cpu-time)
     :sys (-> pieces (get 14) str->cpu-time)}))

(defn runtime-stat []
  (let [rt (Runtime/getRuntime)]
    {:cpu-count (.availableProcessors rt)
     :free-memory (.freeMemory rt)
     :max-memory (.maxMemory rt)
     :total-memory (.totalMemory rt)
     :version (.toString (Runtime/version))}))

(defn sys-stat []
  {:meminfo (meminfo)
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

(defn app [db]
  (ring/ring-handler
   (ring/router
    [["/sys-stat" {:get get-sys-stat
                   :put put-sys-stat}]
     ["/sys-stat/:id" {:get get-sys-stat-id}]]
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
    (str "jdbc:postgresql://" hostname "/" dbname "?user=" username "&password=" password)))

(defn init-db [db]
  (try
    (jdbc/query db "select version from layout_version;")
    (catch SQLException e
      (jdbc/db-do-commands db
                           ["create table stat(id serial unique not null, stat_time timestamp with time zone not null default now(), stat text not null);"
                            "create table layout_version(version integer not null);"
                            "insert into layout_version(version) values (1);"]))))

(defn run [{:keys [db-url port thread]}]
  (let [db {:datasource (hikari/make-datasource {:jdbc-url (libpq->jdbc db-url)})}
        opts (cond-> {:port port}
               thread (assoc :thread thread))]
    (init-db db)
    (println "(org.httpkit.server/run-server app" opts ")")
    (server/run-server (app db) opts)))

(defn env-int [name fback]
  (if-let [v (System/getenv name)]
    (Integer/parseInt v)
    fback))

(defn -main []
  (run {:db-url (System/getenv "DATABASE_URL")
        :port (env-int "LISTEN_PORT" 8080)
        :thread (env-int "THREAD_COUNT" nil)}))
