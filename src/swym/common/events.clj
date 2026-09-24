(ns swym.common.events
  "Domain model shared by every service: the source event that fans out into
   thousands of per-shopper alerts, and the envelope used to carry it (plus
   trace context) across the Redis / Kafka boundary."
  (:require [cheshire.core :as json])
  (:import [java.util UUID]))

(defn new-id [] (str (UUID/randomUUID)))

(defn now-ms [] (System/currentTimeMillis))

(defn merchant-id [n] (format "merchant-%03d" n))

(defn enterprise? [merchant-n enterprise-ratio total]
  ;; deterministic split so the same merchant is always the same tier across processes
  (< merchant-n (int (* enterprise-ratio total))))

(defn make-source-event
  "One restock/price-drop event on a merchant's store. `subscriber-count` models
   the fan-out: how many shoppers (across email/sms/push) must be notified."
  [{:keys [merchant-id tier event-type product-id subscriber-count channels]}]
  {:event-id          (new-id)
   :trace-id          (new-id)
   :merchant-id       merchant-id
   :tier              (name tier)
   :event-type        (name event-type)
   :product-id        product-id
   :subscriber-count  subscriber-count
   :channels          (mapv name channels)
   :created-at        (now-ms)})

(defn envelope
  "What actually goes on the wire (Redis list / Kafka record value). Carries the
   event plus the current span's trace context so the next hop can continue the
   same distributed trace instead of starting a new, disconnected one."
  [event {:keys [trace-id span-id]}]
  (assoc event :parent-trace-id trace-id :parent-span-id span-id))

(defn ->json [m] (json/generate-string m))
(defn <-json [s] (json/parse-string s true))
