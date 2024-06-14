(ns template
  (:require [hiccup2.core :as h]
            [config :refer [debug?]]
            [clojure.pprint :as pprint]
            [clojure.string :as s]
            [clojure.data.json :as json]))

(def login (slurp "html/login.html"))

(def task-js (slurp "html/tasks.js"))
(def task-style (slurp "html/tasks.css"))

; Not escaping strings so I can use cute text emojis lol
; Do not do this in prod !
(defn user-tasks [{user :username}]
  [:html
   [:head
    [:meta {:charset "UTF=8"}]
    [:meta {:name "viewport"
            :content "width=device-wdth,initial-scale=1.0"}]
    [:title "Task Management"]
    [:style task-style]]
   [:body 
    [:h1 (str user "'s tasks: ")]

    [:form 
     [:label {:for "task-desc"} "Description: "]
     [:input {:type "text" 
              :id "task-desc"
              :name "descrption"
              :required true}]
     [:label [:for "task-status"] "Status: "]
     [:select {:id "task-status" 
               :name "status"
               :required true}
      [:option {:value "Pending"} "Pending"]
      [:option {:value "InProgress"} "InProgress"]
      [:option {:value "Finished"} "Finished"]]
     [:button "Add Task"]]
    [:table 
     [:thead
      [:tr 
       [:th "Descrption"]
       [:th "Status"]
       [:th "Actions"]]]
     [:tbody {:id "tasks-table-body"}]
     [:script task-js]]]])

(defn render-task [{:keys [id description status] :as task}]
  [:tr {:id (str id)}
   [:td 
    [:input {:type "text"
             :value description
             :on-change (format "update(action.Update, { id: %d, description: this.value, status: %s })" id status)}]]
   [:td 
    [:select {:on-change (format "update(action.Update, { id: %d, description: %s, status: this.value })" id description)}
     [:option {:value "pending" :selected (= status "pending") } "Pending"]
     [:option {:value "in-progress" :selected (= status "in-progress")} "In-progress"]
     [:option {:value "finished" :selected (= status "finished")} "Finished"]]
    ]
   [:td [:button {:on-click (format "update(action.Delete, %d)" id)
                  :style {"text" "red"}} 
         "x"]]])

(defn render-table [tasks]
  (->> (mapv render-task tasks)
       (into [:div])
       h/html
       str))

(def mock-tasks 
  [{:id 0 :description "do stuff" :status "finished"}
   {:id 1 :description "do more stuff" :status "pending"}
   {:id 2 :description "this time I'm really gonna do it" :status "in-progress"}])

(render-table mock-tasks)