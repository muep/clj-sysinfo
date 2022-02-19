(ns sysinfo
  (:require [clojure.string :as str]
            [org.httpkit.server :as server]
            [reitit.ring :as ring]
            [muuntaja.middleware :as muuntaja])
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

(def app
  (ring/ring-handler
   (ring/router
    [["/sys-stat" {:get get-sys-stat}]]
    {:data
     {:middleware
      [muuntaja/wrap-format-response]}})))

(defn run [{:keys [port thread]}]
  (let [opts (cond-> {:port port}
               thread (assoc :thread thread))]
    (println "(org.httpkit.server/run-server app" opts ")")
    (server/run-server app opts)))

(defn env-int [name fback]
  (if-let [v (System/getenv name)]
    (Integer/parseInt v)
    fback))

(defn -main []
  (run {:port (env-int "LISTEN_PORT" 8080)
        :thread (env-int "THREAD_COUNT" nil)}))
