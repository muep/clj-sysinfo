(ns sysinfo
  (:require [org.httpkit.server :as server]))

(defn get-info []
  (let [rt (Runtime/getRuntime)]
    {:cpu-count (.availableProcessors rt)
     :free-memory (.freeMemory rt)
     :max-memory (.maxMemory rt)
     :total-memory (.totalMemory rt)
     :version (.toString (Runtime/version))}))

(defn app [req]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (get-info)})

(defn -main [& args]
  (println "Entering org.httpkit.server/run-server")
  (server/run-server app {:port 8081}))
