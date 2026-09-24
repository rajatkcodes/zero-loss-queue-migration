(ns swym.common.dlq
  (:require [swym.common.redis :as redis]
            [swym.common.routing :as routing]
            [swym.common.events :as ev]
            [clojure.tools.logging :as log]))

(defn replay-all!
  "Drains the DLQ and redispatches each envelope using the *current* routing
   decision for its merchant - if the merchant has been cut over since the
   original failure, the replay goes out the new path instead of back into the
   queue that just failed it."
  []
  (let [items (redis/dlq-pop-all)]
    (doseq [payload items]
      (try
        (let [env (ev/<-json payload)
              mode (routing/effective-mode (:merchant-id env) (:queue-id env))]
          (routing/dispatch! mode (:tier env) (:merchant-id env) payload))
        (catch Exception e
          (log/error e "dlq replay failed for item"))))
    (count items)))
