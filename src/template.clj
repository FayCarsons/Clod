(ns template
  (:require [hiccup2.core :as h]
            [config :refer [debug?]]
            [clojure.pprint :as pprint]
            [clojure.string :as s]
            [clojure.data.json :as json]))

(def ^:const login (slurp "html/login.html"))
(def ^:const user-tasks (slurp "html/tasks.html"))

(defn render-task [{:keys [id description status]}]
  [:tr {:id (str id)}
   [:td
    [:input {:type "text"
             :value description
             :on-change (format "update(action.Update, { id: %d, description: this.value, status: %s })" id status)}]]
   [:td
    [:select {:on-change (format "update(action.Update, { id: %d, description: %s, status: this.value })" id description)}
     [:option {:value "pending" :selected (= status "pending")} "Pending"]
     [:option {:value "in-progress" :selected (= status "in-progress")} "In-progress"]
     [:option {:value "finished" :selected (= status "finished")} "Finished"]]]
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