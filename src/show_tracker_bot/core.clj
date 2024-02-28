;; SPDX-License-Identifier: MIT OR 0BSD
(ns show-tracker-bot.core
  (:require
   [clojure.java.io :as io]
   [show-tracker-bot.database :as db]
   [clojure.string :as string]
   [clojure.edn :as edn]
   [clojure.core.async :as async]
   [discljord.messaging :as discord-mess]
   [discljord.connections :as discord-conn]
   [discljord.formatting :as discord-form]
   [discljord.events :as discord-events]))

(def state (atom nil))
(def bot-id (atom nil))
(def config (edn/read-string (slurp "config.edn")))
(def token (edn/read-string (slurp "token")))
(def intents #{:guilds :guild-messages})

;;;; DATABASE CALLS

(defn register-channel [{:keys [channel-id] :as _event-data}]
  ;; TODO: name, MAL when creating channel
  (db/register-show channel-id)
  (discord-mess/create-message! (:rest @state) channel-id :content "Channel registered."))

(defn watched-episode [words {:keys [channel-id] :as _event-data}]
  (if (empty? words)
    (db/update-watched channel-id) ; increment by one 
    (let [number (Integer/parseInt (last words))] ;; TODO: error handling 
      (if (= (first words) "to")
        (db/update-watched channel-id (- number (db/current-episode channel-id)))
        (db/update-watched channel-id number))))
  (let [entry (db/get-entry channel-id)]
    (discord-mess/create-message! (:rest @state) channel-id
                                  :content (str "Marking " (:name entry) " as watched to episode " (:episodes-watched entry)))))

(defn update-name [words {:keys [channel-id] :as _event-data}]
  (db/update-name channel-id (string/join " " words)))

(defn dump-database [{:keys [channel-id] :as _event-data}]
  (discord-mess/create-message! (:rest @state) channel-id 
                                :content (discord-form/code (pr-str @db/database))))

;;;; EVENT HANDLING

(defmulti handle-event
  (fn [event-type _]
    event-type))

(defmethod handle-event :default 
  [_ _])

(defmethod handle-event :ready
  [_ _]
  (discord-conn/status-update! (:gateway @state) 
    :activity (discord-conn/create-activity :name "anime 😎" :type :watch)))

; This method will dispatch to the database calls based on the command given. 
(defmethod handle-event :message-create
  [_ {{bot :bot} :author
      :keys      [channel-id content mentions id]
      :as        event-data}]
  (when-not bot
    (let [reply-to-command #(discord-mess/create-message! (:rest @state) channel-id :content %)
          words            (rest (string/split content #" ")) ; drop the mention
          ]
      (when (contains? (set (map #(:id %) mentions)) @bot-id)
        (case (first words)
          "help" (reply-to-command (string/join "\n" (:help config)))
          ;; Database commands 
          "register" (register-channel event-data)
          "watched" (watched-episode (rest words) event-data)
          "rename" (update-name (rest words) event-data)
          ;; Fun commands
          "atomic" (do (discord-mess/delete-message! (:rest @state) channel-id id)
                       (reply-to-command (rand-nth (:atomic config))))
          ;; Dev commands
          "disconnect" (do
                         (reply-to-command "Goodbye!")
                         (discord-conn/disconnect-bot! (:gateway @state)))
          "dump" (dump-database event-data)
          ;; Otherwise
          (when-not bot (reply-to-command "Command not recognised.")))))))

;;;; BOT START/STOP 

(defn start-bot! [token]
  (let [event-channel      (async/chan 100)
        gateway-connection (discord-conn/connect-bot! token event-channel :intents (set intents))
        rest-connection    (discord-mess/start-connection! token)]
    {:events  event-channel
     :gateway gateway-connection
     :rest    rest-connection}))

(defn stop-bot! [{:keys [rest gateway events] :as _state}]
  (discord-mess/stop-connection! rest)
  (discord-conn/disconnect-bot! gateway)
  (async/close! events))

(defn -main []
  (println "starting bot")
  (db/load-database)
  (reset! state (start-bot! token))
  (reset! bot-id (:id @(discord-mess/get-current-user! (:rest @state))))
  (try (discord-events/message-pump! (:events @state) handle-event)
       (finally
         (db/save-database)
         (stop-bot! @state))))
