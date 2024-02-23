;; SPDX-License-Identifier: MIT OR 0BSD
(ns show-tracker-bot.core
  (:require
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
(def intents    #{:guilds :guild-messages})

(defmulti handle-event
  (fn [event-type _]
    event-type))

(defmethod handle-event :default 
  [_ _])

(defmethod handle-event :ready
  [_ _]
  (discord-conn/status-update! (:gateway @state) 
    :activity (discord-conn/create-activity :name "anime 😎" :type :watch)))

(defmethod handle-event :message-create
  [_ {{bot :bot} :author :keys [channel-id content mentions id] :as _data}]
  (when-not bot
    (let [reply-to-command #(discord-mess/create-message! (:rest @state) channel-id :content %)
          words (drop 1 (string/split content #" "))]
      (when (contains? (set (map #(:id %) mentions)) @bot-id)
        (case (first words)
          "disconnect"
          (do
            (reply-to-command "Goodbye!")
            (discord-conn/disconnect-bot! (:gateway @state)))
          "help"
            (reply-to-command (string/join "\n" (:help config)))
          "atomic"
            (do (discord-mess/delete-message! (:rest @state) channel-id id)
                (reply-to-command (rand-nth (:atomic config))))
          (when-not bot (reply-to-command "Command not recognised.")))))))

(defn start-bot! [token]
  (let [event-channel (async/chan 100)
        gateway-connection (discord-conn/connect-bot! token event-channel :intents (set intents))
        rest-connection (discord-mess/start-connection! token)]
    {:events  event-channel
     :gateway gateway-connection
     :rest    rest-connection}))

(defn stop-bot! [{:keys [rest gateway events] :as _state}]
  (discord-mess/stop-connection! rest)
  (discord-conn/disconnect-bot! gateway)
  (async/close! events))

(defn -main []
  (println "starting bot")
  (reset! state (start-bot! token))
  (reset! bot-id (:id @(discord-mess/get-current-user! (:rest @state))))
  (try (discord-events/message-pump! (:events @state) handle-event)
       (finally
         (stop-bot! @state))))
