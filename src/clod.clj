(ns clod
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clojure.string :as string]
            [config :refer [debug? TTL]]
            [clojure.pprint :as pprint])
  (:import (java.net ServerSocket Socket)
           (java.io PrintWriter BufferedReader InputStreamReader)
           (java.time Instant Duration)))


(def command?
  "Valid commands"
  #{"get"
    "set"
    "del"
    "incr"
    "save"
    "restore"
    "expires"})

(defn inc!
  "No `update!` for transients?"
  [m k]
  (try (assoc! m k (inc (or (get m k) 0)))
       (catch Exception _
         (let [err (str "ERR: Cannot increment "
                        (print key)
                        " - "
                        (type (get m k)))]
           (println err)
           m))))

(defn parse-or-id [val]
  (if-some [parsed (parse-long val)]
    parsed
    val))

(defprotocol HostMethods 
  (create [this])
  (start [this])
  (handle-client [this client])
  (handle-command [this command])
  (snapshot [this])
  (restore [this path]))

(defn timestamp [seconds]
  (.plus (Instant/now)
         (Duration/ofSeconds (or (parse-long seconds) TTL))))
         

(defn expired? [timestamp]
  (.isBefore timestamp (Instant/now)))

(defn get-entry [db k]
  (when debug? (pprint/pprint @db))
  (if-let [entry (get @db k)]
    (if (some-> entry
                :expires
                expired?)
      (do (swap! db dissoc! k) 
          {:failure "Expired"})
      (or (:inner entry) entry))
    {:failure "Not found"})) 

(defn set-entry! [db k v] (swap! db assoc! k v) nil)

(defn set-entry-expires!
  ([db k v seconds]
   (swap! db assoc! k {:inner v :expires (timestamp seconds)})
   nil)
  ([db k seconds]
   (if-let [entry (get @db k)]
     (do (swap! db assoc! k (assoc entry :expires (timestamp seconds)))
         nil)
     {:failure "Not found"})))

(def pattern #"\[[^\]]*\]|\{[^\}]*\}|\([^\)]*\)|\S+")

;; Tokenizer that handles complex structures
(defn tokenize [input]
  (let [tokens (re-seq pattern input)]
    (mapv #(if (or (string/starts-with? % "[")
                   (string/starts-with? % "(")
                   (string/starts-with? % "{"))
             (read-string %)
             %)
          tokens)))

(defn parse [expression]
  (when expression
    (let [[action k v expires seconds] (tokenize expression)]
      (when debug?
        (println (str "TOKENS: "
                      action \space
                      k \space
                      v \space
                      expires \space
                      seconds)))
      (cond
        (and (= action "set") (= expires "expires")) [:set-expires k v seconds]
        (= action "set") [:set k v]
        :else [(keyword action) k]))))

(defn cleanup [db]
  (async/go-loop [now (Instant/now)]
    (async/<! (async/timeout 10000))
    (swap! db (fn [map]
                (->> map
                     persistent!
                     (remove (fn [[_ v]]
                               (and (:expires v)
                                    (.isBefore (:expires v) now))))
                     (into {})
                     transient)))
    (recur (Instant/now))))

(defrecord Host [db port conn]
  HostMethods
  (create [this]
    (println "Creating db for Clod host")
    (merge this
           {:db (-> {} transient atom)}))
  (start [{:keys [port] :as this}]
    (println "Starting Clod host server")
    #_(async/go (cleanup db))
    (with-open [conn (ServerSocket. port)]
      (let [this (assoc this :conn conn)]
        (loop [client (.accept conn)]
          (println (str "Got client: " (.hashCode client)))
          (.handle-client this client)
          (recur (.accept conn))))))
  (handle-client [this client]
    (let [writer (io/writer client)
          reader (io/reader client)
          get-req (fn [] (try (.readLine reader) (catch Exception _ nil)))]
      (async/go-loop [request (get-req)
                      command (parse request)]
        (when debug? 
          (println (str "COMMAND IN handle-client: " command)) )
        (if (or (nil? request)
                (empty? request)
                (empty? command))
          (do (try (.close client) (catch Exception _ nil))
              (println (str "Closed client: " (.hashCode client))))
          (let [result (.handle-command this command)]
            (.write writer (str result
                                \newline))
            (.flush writer)
            (let [next (get-req)
                  command (parse next)]
              (recur next command)))))))
  (handle-command [{:keys [db] :as this} [command key val seconds]]
    (when debug? 
      (println "COMMAND in handle-command: " (str command \space 
                                                  key \space 
                                                  val \space 
                                                  seconds)))
    (or (case command
          :get (get-entry db key)
          :set (set-entry! db key val)
          :set-expires (set-entry-expires! db key val seconds)
          :expires (set-entry-expires! db key seconds)
          :del (swap! db dissoc! key)
          :incr (swap! db inc! key)
          :save (.snapshot this db)
          :restore (.restore this db key))
        "OK"))
  (snapshot [db]
    (let [contents (str db)
          hash (hash db)
          name (str ".clod-" hash)]
      (if-not (.exists (io/file name))
        (do
          (spit name contents)
          hash)
        {:failure "Snapshot already exists"})))
  (restore [this hash]
    (try (or (some->> hash
                      (str ".clod-")
                      slurp
                      read-string
                      transient
                      (reset! (:db this)))
             {:failure (str "Malformed or non-existent backup " hash)})
         (catch Exception e
           (let [err (str "Cannot restore DB from snapshot: " e)]
             (println err)
             {:failure err})))))

(defn -send-msg [host port msg]
  (with-open [conn (Socket. host port)
              out (-> conn .getOutputStream PrintWriter.)
              in (-> conn .getInputStream InputStreamReader. BufferedReader.)]
    (.println out msg)
    (.flush out)
    (let [response (some-> in
                           .readLine
                           read-string)]
      (.close conn)
      response)))

(defn -with-failure [f]
  (try (f)
       (catch Exception e
         {:failure (.getMessage e)})))

(defprotocol ClientMethods
  (with-uri [this host-uri])
  (get-val [this k])
  (set-val! [this k v])
  (set-expires!
    [this k v expires]
    [this k expires])
  (delete-val [this k])
  (incr-val [this k])
  (transaction [this block]))

(defrecord Client [host port]
  ClientMethods
  (with-uri [_ host-uri]
    (let [[host port] (string/split host-uri ":")]
      (Client. host port)))
  (get-val [{:keys [host port]} k]
    (-with-failure (-send-msg host
                              port
                              (str "get " k))))
  (set-val! [{:keys [host port]} k v]
    (-with-failure (-send-msg host port (str "set " k \space v))))
  (set-expires! [{:keys [host port]} k expires]
    (-with-failure (-send-msg host port (str "expires " k \space expires))))
  (set-expires! [{:keys [host port]} k v expires]
    (let [response (-with-failure
                    (-send-msg host port (str "set "
                                              k \space
                                              v \space
                                              "expires " expires)))]
      (println "`set-expires!` completed")
      response))
  (delete-val [{:keys [host port]} k]
    (-with-failure (-send-msg host port (str "del " k))))
  (incr-val [{:keys [host port]} k]
    (-with-failure (-send-msg host port (str "incr " k))))
  (transaction [{:keys [host port]} block]
    (-with-failure (block host port))))