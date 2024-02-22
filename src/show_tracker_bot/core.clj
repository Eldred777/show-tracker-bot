;; SPDX-License-Identifier: MIT OR 0BSD
(ns show-tracker-bot.core
  (:require
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

(defmethod handle-event :message-create
  [_ {{bot :bot} :author :keys [channel-id content]}]
  (if (= content "!disconnect")
    (do
      (discord-mess/create-message! (:rest @state) channel-id :content "Goodbye!")
      (discord-conn/disconnect-bot! (:gateway @state)))
    (when-not bot
      (discord-mess/create-message! (:rest @state) channel-id :content content))))

(defmethod handle-event :ready
  [_ _]
  (discord-conn/status-update! (:gateway @state) 
                               :activity (discord-conn/create-activity :name "anime 😎" :type :watch)))

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
  ;; (reset! bot-id (:id @(discord-mess/get-current-user! (:rest @state))))
  (try (discord-events/message-pump! (:events @state) handle-event)
       (finally
         (stop-bot! @state))))
