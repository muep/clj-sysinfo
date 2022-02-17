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

(defn proc-stat [proc]
  (let [pieces (-> (slurp-proc (str "/proc/" proc "/stat"))
                   (str/split #"\s+"))]
    {:pid (-> pieces (get 0) Integer/parseInt)
     :rss (-> pieces (get 23) str->ram-kib)
     :user (-> pieces (get 13) str->cpu-time)
     :sys (-> pieces (get 14) str->cpu-time)}))

(defn sys-stat []
  (let [rt (Runtime/getRuntime)]
    {:runtime
     {:cpu-count (.availableProcessors rt)
      :free-memory (.freeMemory rt)
      :max-memory (.maxMemory rt)
      :total-memory (.totalMemory rt)
      :version (.toString (Runtime/version))}
     :process (proc-stat "self")}))

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
