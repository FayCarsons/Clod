(ns core
  (:require [clojure.core.async :as async]
            [clod :as clod]
            [session :refer [start-server]]
            [config :refer [KV-PORT JETTY-PORT]]))

(defn start-kv []
  (-> {:port KV-PORT}
      clod/map->Host
      .create
      .start))

(defn run [& _]
  (let [kv (async/thread (start-kv))
          _server (async/thread (start-server JETTY-PORT))]
      (println "KV returned: "
               (async/<!! kv))))