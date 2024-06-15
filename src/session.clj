(ns session
  (:require [template :refer [login user-tasks render-table]]
            [clojure.string :as string]
            [clod :as clod]
            [db :refer [map->User map->Task]]
            [config :refer [KV-PORT KV-HOST debug? TTL]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.util.response :refer [redirect bad-request]]
            [ring.util.request :refer [body-string]]
            [compojure.core :refer [GET PUT POST DELETE defroutes routes]]
            [compojure.route :as route]
            [clojure.pprint :as pprint]
            [clojure.data.json :as json]))

(defn content-type [type]
  {"Content-Type" type})
 
(defn default-client [] (clod/map->Client {:host KV-HOST :port KV-PORT}))

(def hashkeys (atom (transient {})))
(defn hash-pass [x]
  (when debug?
    (println "Input to `hash-pass`:")
    (println x))
  (when x
    (letfn [(xor [k v]
              (mapv (fn [k v] (bit-xor (int k)
                                       (int v)))
                    k
                    v))]
      (reduce str
              (if-let [k (get @hashkeys x)]
                (xor k (.getBytes x))
                (let [k (-> x
                            count
                            (repeatedly #(rand-int 256))
                            byte-array)]
                  (swap! hashkeys assoc! x k)
                  (xor k (.getBytes x))))))))

(defn -validate [id]
  (let [user (some-> {:id id}
                     map->User
                     .fetch)
        client (default-client)]
    (try (when (and user
                    (= "active"
                       (.get-val client (:username user))))
           user)
         (catch Exception e
           (println (.getMessage e))
           nil))))

(defn -handle-root [request]
  (println "HANDLE-ROOT")
  (let [cookies (:cookies request)]
    (if-some [user (some-> cookies
                           (get-in ["authentication" :value])
                           -validate)]
      (redirect (str "/profile/" (:id user)))
      {:status 200
       :headers (content-type "text/html")
       :body login})))

(defn some-if [x pred]
  (when (pred x)
    x))

(defn -tasks [id]
  (when debug?
    (println "Rendering task-manager page!\n Responding with static HTML: \n" (take 64 user-tasks)))
  (if-some [user
            (-> {:id id}
                map->User
                .fetch
                (some-if (comp not :failure)))]
    {:status 200 
     :headers (content-type "text/html")
     :body user-tasks}
    {:status 200
     :headers (content-type "application/html")
     :body (str "<h1>User: " id " not found</h1>")}))

(defn -create-user [request]
  (when debug?
    (println "CREATE-USER")
    (pprint/pprint request))
  (let [body (:body request)
        _ (pprint/pprint body)
        user (some-> body
                     map->User
                     (update :password hash-pass)
                     .with-id
                     .store!)]
    (if-let [err (:failure user)]
      (do (when debug?
            (pprint/pprint err))
          {:status 500
           :body (pr-str (or err
                             {:failure "Invalid user map"}))})
      (let [{:keys [id] :as user} user
            client (default-client)
            url (str "/profile/" id)]
        (when debug?
          (pprint/pprint {"User map" user
                          "Client" client}))
        (.set-expires! client id "active" TTL)
        {:body (json/write-str {:redirect url})
         :headers (content-type "application/json")
         :cookies {"authentication"
                   {:value id}}}))))

(defn id->tasks [id]
  (str id "-tasks"))

(defn -fetch-tasks [id]
  (or (some->> {:id id}
               map->User
               .fetch
               .fetch-tasks
               render-table
               (assoc {:status 200
                       :headers (content-type "text/html")}
                      :body))
      (bad-request (str "No tasks for user " id))))

(defn log [label x]
  (println label)
  (pprint/pprint x)
  x)

(defn -create-task [req]
  (let [task (-> req
                 body-string
                 json/read-str
                 map->Task
                 ( (partial log "Task: ") ))
        user-id (log "User-id" (get-in req [:params :user-id]) )
        task (assoc task :user user-id)
        client (default-client)
        tasks-id (log "KV tasks entry ID: " (id->tasks user-id) )
        other-tasks (->> tasks-id
                         (.get-val client)
                         read-string)]
    (if-let [err (:failure other-tasks)]
      (if (= nil err)
        (.set-val client tasks-id [task])
        (bad-request err))
      (if (map? other-tasks)
        (let [new-tasks (assoc other-tasks (:id task) task)
              template (render-table new-tasks)
              response (.set-val client tasks-id new-tasks)]
          (if (= "OK" response)
            {:status 200
             :headers (content-type "text/html")
             :body template}
            {:status 500
             :headers (content-type "application/json")
             :body (json/write-str (read-string response))}))
        {:status 500
         :body "Unknown error: invalid task map"}))))

(defn -update-task [req]
  (let [task-id (get-in req [:params :task-id])
        task (-> req
                 body-string
                 json/read-str
                 map->Task)
        user-id (get-in req [:cookies "authentication"])
        tasks-id (id->tasks user-id)
        client (default-client)
        old-tasks (->  tasks-id
                       (.get-val client)
                       read-string)]
    (if (map? old-tasks)
      (let [updated (assoc old-tasks task-id task)
            response (.set-val client tasks-id updated)
            template (render-table (vals updated))]
        (if (= "OK" response)
          {:status 200
           :headers (content-type "text/html")
           :body template}
          {:status 500
           :body (-> response
                     read-string
                     json/write-str)}))
      (bad-request "User has no tasks"))))

(defn -remove-task [req]
  (let [task-id (get-in req [:params :task-id])
        user-id (get-in req [:cookies "authentication"])
        tasks-id (id->tasks user-id)
        client (default-client)
        old-tasks (.get-val client tasks-id)]
    (if (map? old-tasks)
      (let [new-tasks (dissoc old-tasks task-id)
            response (.set-val client tasks-id new-tasks)]
        (if (= response "OK")
          {:status 200
           :headers (content-type "text/html")
           :body (render-table new-tasks)}
          (bad-request response)))
      (bad-request "User has no tasks"))))

(defn normalize-headers [handler]
  (fn [request]
    (handler (update request
                     :headers
                     (fn [req]
                       (into {}
                             (mapv (fn [[k v]] [(string/lower-case k) v])
                                   req)))))))

(defn parse-edn [handler]
  (fn [request]
    (if (= "application/edn"
           (get-in request [:headers "content-type"]))
      (handler (assoc request :body (-> request
                                        body-string
                                        read-string)))
      (handler request))))


(defroutes app
  (-> (routes
       (GET "/" req (-handle-root req))
       (POST "/users" req (-create-user req))
       (GET "/profile/:id" [id]  (-tasks id))
       (GET "/tasks/:user-id" [user-id] (-fetch-tasks user-id))
       (POST "/tasks/:user-id" req (-create-task req))
       (PUT "/tasks/:task-id" req (-update-task req))
       (DELETE "/tasks/:id" req (-remove-task req))
       (route/not-found {:status 404
                         :headers (content-type "text/html")}))
      wrap-cookies
      normalize-headers
      parse-edn))

(defn start-server [port]
  (when debug?
    (println "STARTING RING SERVER"))
  (run-jetty app
             {:port port}))