(ns ^{:doc "Definition of the StatusMessage protocol"}
 status-im.transport.message.core
  (:require [status-im.chat.models.message :as models.message]
            [status-im.chat.models :as models.chat]
            [status-im.contact.core :as models.contact]
            [status-im.pairing.core :as models.pairing]
            [status-im.data-store.messages :as data-store.messages]
            [status-im.data-store.contacts :as data-store.contacts]
            [status-im.data-store.chats :as data-store.chats]
            [status-im.utils.handlers :as handlers]
            [status-im.utils.fx :as fx]
            [status-im.utils.types :as types]))

(defn- js-obj->seq [obj]
  ;; Sometimes the filter will return a single object instead of a collection
  (if (array? obj)
    (for [i (range (.-length obj))]
      (aget obj i))
    [obj]))

(fx/defn handle-chats [cofx chats]
  (models.chat/ensure-chats cofx chats))

(fx/defn handle-contacts [cofx contacts]
  (models.contact/ensure-contacts cofx contacts))

(fx/defn handle-message [cofx message]
  (models.message/receive-one cofx message))

(fx/defn process-response [cofx response-js]
  (let [chats (.-chats response-js)
        contacts (.-contacts response-js)
        installations (.-installations response-js)
        messages (.-messages response-js)]
    (cond
      (seq installations)
      (let [installations-clj (types/js->clj installations)]
        (js-delete response-js "installations")
        (fx/merge cofx
                  {:utils/dispatch-later [{:ms 20 :dispatch [::process response-js]}]}
                  (models.pairing/handle-installations installations-clj)))

      (seq contacts)
      (let [contacts-clj (types/js->clj contacts)]
        (js-delete response-js "contacts")
        (fx/merge cofx
                  {:utils/dispatch-later [{:ms 20 :dispatch [::process response-js]}]}
                  (handle-contacts (map data-store.contacts/<-rpc contacts-clj))))

      (seq chats)
      (let [chats-clj (types/js->clj chats)]
        (js-delete response-js "chats")
        (fx/merge cofx
                  {:utils/dispatch-later [{:ms 20 :dispatch [::process response-js]}]}
                  (handle-chats (map #(-> %
                                          (data-store.chats/<-rpc)
                                          (dissoc :unviewed-messages-count))
                                     chats-clj))))

      (seq messages)
      (let [message (.pop messages)]
        (fx/merge cofx
                  {:utils/dispatch-later [{:ms 20 :dispatch [::process response-js]}]}
                  (handle-message (-> message (types/js->clj) (data-store.messages/<-rpc))))))))

(handlers/register-handler-fx
 ::process
 (fn [cofx [_ response-js]]
   (process-response cofx response-js)))

(fx/defn remove-hash
  [{:keys [db] :as cofx} envelope-hash]
  {:db (update db :transport/message-envelopes dissoc envelope-hash)})

(fx/defn check-confirmations
  [{:keys [db] :as cofx} status chat-id message-id]
  (when-let [{:keys [pending-confirmations not-sent]}
             (get-in db [:transport/message-ids->confirmations message-id])]
    (if (zero? (dec pending-confirmations))
      (fx/merge cofx
                {:db (update db
                             :transport/message-ids->confirmations
                             dissoc message-id)}
                (models.message/update-message-status chat-id
                                                      message-id
                                                      (if not-sent
                                                        :not-sent
                                                        status))
                (remove-hash message-id))
      (let [confirmations {:pending-confirmations (dec pending-confirmations)
                           :not-sent  (or not-sent
                                          (= :not-sent status))}]
        {:db (assoc-in db
                       [:transport/message-ids->confirmations message-id]
                       confirmations)}))))

(fx/defn update-envelope-status
  [{:keys [db] :as cofx} envelope-hash status]
  (let [{:keys [chat-id message-type message-id]}
        (get-in db [:transport/message-envelopes envelope-hash])]
    (case message-type
      :contact-message
      (when (= :sent status)
        (remove-hash cofx envelope-hash))

      (when-let [{:keys [from]} (get-in db [:chats chat-id :messages message-id])]
        (check-confirmations cofx status chat-id message-id)))))

(fx/defn update-envelopes-status
  [{:keys [db] :as cofx} envelope-hashes status]
  (apply fx/merge cofx (map #(update-envelope-status % status) envelope-hashes)))

(fx/defn set-contact-message-envelope-hash
  [{:keys [db] :as cofx} chat-id envelope-hash]
  {:db (assoc-in db [:transport/message-envelopes envelope-hash]
                 {:chat-id      chat-id
                  :message-type :contact-message})})

(fx/defn set-message-envelope-hash
  "message-type is used for tracking"
  [{:keys [db] :as cofx} chat-id message-id message-type messages-count]
  {:db (-> db
           (assoc-in [:transport/message-envelopes message-id]
                     {:chat-id      chat-id
                      :message-id   message-id
                      :message-type message-type})
           (update-in [:transport/message-ids->confirmations message-id]
                      #(or % {:pending-confirmations messages-count})))})

(defn- own-info [db]
  (let [{:keys [name photo-path address]} (:multiaccount db)]
    {:name          name
     :profile-image photo-path
     :address       address}))
