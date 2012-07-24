;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.stomp.router
  (:use
    [lamina core trace connections])
  (:require
    [lamina.cache :as c]))

(defprotocol Router
  (register-publisher [_ endpoint ch])
  (register-subscriber [_ ch]))

(defn destination [msg]
  (get-in msg [:headers "destination"]))

(defn id [msg]
  (get-in msg [:headers "id"]))

(defn subscription [destination id]
  {:command :subscribe
   :headers {"destination" destination
             "id" id
             "ack" "auto"}})

(defn unsubscription [id]
  {:command :unsubscribe
   :headers {"id" id}})

(defprotocol SubscriptionCache
  (get-or-create [_ destination])
  (release [_ destination])
  (subscriptions [_]))

;; a channel cache that correlates destination and ids
(defn subscription-cache
  [{:keys [generator
           on-subscribe
           on-unsubscribe]}]
  (let [n (atom 0)
        gen-id #(swap! n inc)
        active-subscriptions (atom {})
        cache (c/channel-cache generator)]
    (reify SubscriptionCache

      (get-or-create [_ destination]
        (c/get-or-create cache destination
          (fn [ch]
            (let [id (gen-id)]
              
              ;; add to active subscription list
              (swap! active-subscriptions
                assoc id destination)
              
              ;; broadcast subscription
              (on-subscribe destination id)

              ;; hook up unsubscription
              (on-closed ch 
                (fn []
                  (swap! active-subscriptions
                    dissoc id)
                  (on-unsubscribe id)))))))

      (release [_ destination]
        (c/release cache destination))

      (subscriptions [_]
        @active-subscriptions))))

(defn router
  [{:keys [name
           endpoint-transform
           aggregator]
    :or {name "stomp-router"
         endpoint-transform (fn [_ dest] [dest identity])
         aggregator (fn [_ ch] ch)}}]
  (let [subscription-broadcaster (permanent-channel)
        cache (subscription-cache
                {:generator
                 (fn [destination]
                   (let [in (channel)
                         out (channel* :description destination :grounded? true)]
                     (join
                       (aggregator destination in)
                       out)
                     (splice out in)))

                 :on-subscribe
                 (fn [destination id]
                   (enqueue subscription-broadcaster
                     (subscription destination id)))

                 :on-unsubscribe
                 (fn [id]
                   (enqueue subscription-broadcaster
                     (unsubscription id)))})

        generator #(get-or-create cache %)]
       
    (reify Router
      
      (register-publisher [_ endpoint ch]

        (let [local-broadcaster (channel)]
          
         ;; forward all subsequent subscriptions
         (siphon subscription-broadcaster local-broadcaster ch)

         ;; resend all active subscriptions
         (doseq [[id destination] (subscriptions cache)]
           (enqueue local-broadcaster (subscription destination id)))
         
         ;; handle all messages
         (receive-all
           (filter* #(= :send (:command %)) ch)
           (fn [msg]
             (enqueue (generator (destination msg)) msg)))))

      (register-subscriber [_ ch]
        
        (let [subs (c/channel-cache #(channel* :description %))]
          (receive-all ch
            (fn [msg]
              (case (:command msg)

                :subscribe
                (let [dest (destination msg)]
                  (siphon
                    (generator dest)
                    (c/get-or-create subs (id msg) nil)
                    ch))
                  
                :unsubscribe
                (c/release subs (id msg))
                  
                nil))))))))

;;;

(defprotocol Endpoint
  (subscribe [_ destination])
  (publish [_ msg]))

(defn endpoint [name connection-generator producer]
  (let [conn (atom nil)
        subscribers (subscription-cache
                      {:generator
                       #(channel* :description % :grounded? true)
                       :on-subscribe
                       (fn [destination id]
                         (when-let [conn @conn]
                           (enqueue conn (subscription destination id))))
                       :on-unsubscribe
                       (fn [id]
                         (when-let [conn @conn]
                           (enqueue conn (unsubscription id))))})

        subscriber #(get-or-create subscribers %)

        active-publishers (atom {})
        publishers (c/channel-cache #(channel* :description % :grounded true?))
        publisher #(c/get-or-create publishers % (producer %))

        connection-callback (fn [ch]

                              (reset! conn ch)
                              (reset! active-publishers {})
                                
                              (doseq [[id destination] (subscriptions subscribers)]
                                (enqueue ch (subscription destination id)))
                                
                              (receive-all ch
                                (fn [msg]

                                  (let [dest (destination msg)]
                                    (case (:command msg)
                                        
                                      :send
                                      (let [result (enqueue (subscriber dest) msg)]
                                        (when (= :lamina/grounded result)
                                          (release subscribers dest)))
                                        
                                      :subscribe
                                      (let [bridge (channel)]
                                        (swap! active-publishers assoc (id msg) bridge)
                                        (siphon (producer dest) bridge ch))
                                        
                                      :unsubscribe
                                      (let [id (id msg)]
                                        (close (@active-publishers id))
                                        (swap! active-publishers dissoc id))))))
                                
                              (closed-result ch))

        connection (persistent-connection connection-generator
                     {:name name
                      :on-connected connection-callback})]

    (with-meta
      (reify Endpoint
        (subscribe [_ destination]
          (let [_ (connection)
                ch (channel)]
            (join (subscriber destination) ch)
            ch))
        (publish [_ msg]
          (when-let [conn (connection)]
            (enqueue conn msg))))
      (meta connection))))