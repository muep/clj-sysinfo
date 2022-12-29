(ns sysinfo.lambda-handler
  (:require [sysinfo :refer [sys-stat]]
            [clojure.data.json :as json])
  (:gen-class
   :methods [[handleStream [java.io.InputStream java.io.OutputStream] void]]))

(defn -handleStream [this in out]
  (spit out (json/write-str {:statusCode 200
                             :body (sys-stat)})))
