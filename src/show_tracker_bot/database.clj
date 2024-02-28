(ns show-tracker-bot.database
  (:require [clojure.java.io :as io]))

(def blank-show {:name "UNSPECIFIED" :episodes-watched 0})

(def database (atom {})) ;; STRUCTURE: {:channel-id {& data}}

;;;; SAVING AND LOADING
(defn save-database
  ([db-path] (spit (io/file db-path) @database))
  ([] (let [db-path "db/default.db"] (save-database db-path))))

(defn load-database
  ([db-path]
   (let [file (io/file db-path)]
     (when (.exists file)
       (reset! database (read-string (slurp file))))
       ;; TODO: error if DNE
     ))
  ([] (let [db-path "db/default.db"] (load-database db-path))))

(defn- _reset-database []
  (reset! database {}))

;;;; CREATING NEW ENTRIES 
(defn register-show [channel-id]
  (when-not (find @database channel-id)
    (reset! database (assoc @database channel-id blank-show))))

(defn- _deregister-show [channel-id]
  (reset! database (dissoc @database channel-id)))

;;;; GETTING DATA

(defn get-entry [channel-id]
  (@database channel-id))

(defn current-episode [channel-id]
  (:episodes-watched (get-entry channel-id)))

;;;; UPDATING EXISTING ENTRIES

(defn- update-show
  "General function to update shows."
  [channel-id & params]
  (reset! database (merge @database
                          {channel-id (merge (get-entry channel-id)
                                             (apply hash-map params))})))

(defn update-watched
  ([channel-id num-watched] (update-show channel-id :episodes-watched (+ num-watched (current-episode channel-id))))
  ([channel-id] (update-watched channel-id 1)))

(defn update-name
  ([channel-id name] (update-show channel-id :name name)))
