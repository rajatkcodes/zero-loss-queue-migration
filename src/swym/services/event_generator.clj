(ns swym.services.event-generator
  "Simulates the real trigger: a restock/price-drop event on a merchant's store
   that has to fan out to every shopper who asked to be notified. Routes each
   event to legacy Redis, the new Kafka path, or both, based on the merchant's
   current migration flag - this IS the feature-toggled cutover mechanism."
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [swym.common.config :as cfg]
            [swym.common.events :as ev]
            [swym.common.routing :as routing]
            [swym.common.merchants :as merchants]
            [swym.common.tracing :as t])
  (:import [io.opentelemetry.api.trace Tracer]))

(defn- route! [^Tracer tracer merchant queue-id event]
  (let [mode (routing/effective-mode (:id merchant) queue-id)
        span (t/start-span tracer "enqueue" {:kind :producer
                                              :attrs {:merchant-id (:id merchant)
                                                      :tier (name (:tier merchant))
                                                      :queue-id queue-id
                                                      :mode (name mode)
                                                      :event-type (:event-type event)
                                                      :subscriber-count (:subscriber-count event)}})
        ids (t/span-ids span)
        payload (ev/->json (ev/envelope (assoc event :mode (name mode) :queue-id queue-id) ids))]
    (routing/dispatch! mode (:tier merchant) (:id merchant) payload)
    (t/finish! span :ok)))

(defn- rand-event [merchant]
  (ev/make-source-event
   {:merchant-id (:id merchant)
    :tier (:tier merchant)
    :event-type (rand-nth cfg/event-types)
    :product-id (str "sku-" (rand-int 9999))
    ;; models "thousands of alerts" per restock without literally sending thousands
    ;; of wire messages - the consumer fans this number out into simulated deliveries.
    :subscriber-count (+ 50 (rand-int (if (= (:tier merchant) :enterprise) 4000 1500)))
    :channels cfg/channels}))

(defn -main [& _args]
  (let [tracer (t/init-tracer! "event-generator")
        rate-per-sec (Long/parseLong (or (System/getenv "SWYM_EVENT_RATE") "6"))
        sleep-ms (max 20 (long (/ 1000 rate-per-sec)))]
    (log/info "event-generator starting" {:merchants (count merchants/all) :rate-per-sec rate-per-sec})
    (loop [i 0]
      (let [merchant (rand-nth merchants/all)
            event (rand-event merchant)]
        (try
          (route! tracer merchant (:queue-id merchant) event)
          (catch Exception e
            (log/error e "failed to route event" {:merchant (:id merchant)}))))
      (Thread/sleep sleep-ms)
      (recur (inc i)))))
