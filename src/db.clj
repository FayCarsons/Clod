(ns db
  (:require [clojure.java.jdbc :as jdbc]
            [config :refer [debug?]]
            [clojure.pprint :as pprint])
  (:import java.util.UUID))

(def db
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname "data.sqlite"})

(defn user? [{:keys [username password id]}]
  (and (string? username)
       (string? password)
       (integer? id)))

(def status? #{"pending" "in-progess" "finished"})
(defn task? [{:keys [id description status user]}]
  (and (integer? id)
       (string? description)
       (status? status)
       (integer? user)))

(defprotocol UserData
  (store! [this])
  (alter! [this])
  (with-id [this] [this])
  (fetch [this])
  (fetch-tasks [this])
  (delete [this]))

(defrecord Task [id description status user]
  UserData
  (store! [{:keys [id] :as this}]
    (let [this (if (not id)
                 (assoc this :id (UUID/randomUUID))
                 this)]
      (if (task? this)
        (try (jdbc/insert! db :tasks this)
             this
             (catch Exception e
               (let [err (.getMessage e)]
                 (println err)
                 {:failure err})))
        {:failure (str "Invalid task: " this)})))
  (alter! [{:keys [id] :as this}]
    (try (jdbc/update! db :tasks this ["id = ?" id])
         (catch Exception e 
           (let [err (.getMessage e)]
             (println err)
             {:failure err}))))
  (fetch [{id :id}]
    (jdbc/query db ["select * from tasks where (id = ?)" id]))
  (delete [{id :id}]
    (try (jdbc/execute! db ["delete from task where (id = ?)" id])
         (catch Exception e
           (let [err (.getMessage e)]
             (println err)
             {:failure err})))))

(defrecord User [id username password]
  UserData
  (store!
    [this]
    (when debug?
      (println "User in `store!`:")
      (pprint/pprint this))
    (when (user? this)
      (try (jdbc/insert! db
                         :users
                         this)
           this
           (catch Exception e
             (let [err (.getMessage e)]
               (println err)
               {:failure err})))))
  (alter! [{:keys [id] :as this}]
    (try (jdbc/update! db :users this ["id = ?" id])
         (catch Exception e
           (let [err (.getMessage e)]
             (println err)
             {:failure err}))))
  (with-id [{:keys [^String username] :as this}]
    (when debug?
      (println "User in `with-id`: ")
      (pprint/pprint (pr-str this)))
    (let [id (hash username)]
      (assoc this :id id)))
  (fetch [{:keys [id username] :as this}]
    (when debug?
      (println "User in `fetch`: ")
      (pprint/pprint (pr-str this)))
    (let [id (or id
                 (hash username))]
      (try (jdbc/query db ["select * from users where id = ?" id])
           (catch Exception e
             (println (.getMessage e))
             {:failure (str "User " username "not found")}))))
  (fetch-tasks [this]
    (jdbc/query db
                ["select * 
                            from tasks 
                            join users on tasks.user = users.id 
                            where users.id = ?"
                 (:id this)]))
  (delete [{id :id}]
    (try (jdbc/execute! db ["delete from users where (id = ?)" id])
         (catch Exception e
           (let [err (.getMessage e)]
             (println err)
             {:failure err})))))

(jdbc/insert! db :users {:id 0 :username "kiggy" :password "testing"})

(defn create-db []
  (try (jdbc/db-do-commands db
                            [(jdbc/create-table-ddl :users
                                                    [[:id :integer :primary :key :not :null]
                                                     [:username :text :not :null]
                                                     [:password :text :not :null]]
                                                    {:conditional? true})
                             (jdbc/create-table-ddl :tasks
                                                    [[:id :integer :primary :key :not :null]
                                                     [:description :text :not :null]
                                                     [:status :text :not :null]
                                                     [:user :integer "references users (id)"]]
                                                    {:conditional? true})])
       (catch Exception e
         (println (.getMessage e)))))
(create-db)
