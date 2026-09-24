(ns swym.common.routing
  "The single place that decides where a message goes: legacy Redis, the new
   Kafka path, or both. Shared by the event-generator (first dispatch) and the
   DLQ replay job (redispatch after a failure) so a merchant that has since
   been cut over doesn't get its replayed message routed back to the path it
   just left."
  (:require [swym.common.redis :as redis]
            [swym.common.kafka :as kafka]
            [swym.common.config :as cfg]))

(defn effective-mode [merchant-id queue-id]
  (or (redis/get-merchant-override merchant-id)
      (redis/get-queue-mode queue-id)))

(defn topic-for-tier [tier]
  (if (= (name tier) "enterprise") cfg/topic-enterprise cfg/topic-standard))

(defn dispatch! [mode tier merchant-id payload-json]
  (case mode
    :legacy     (redis/legacy-push payload-json)
    :new        (kafka/send! (topic-for-tier tier) merchant-id payload-json)
    :dual-write (do (redis/legacy-push payload-json)
                     (kafka/send! (topic-for-tier tier) merchant-id payload-json))))
