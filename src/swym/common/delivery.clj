(ns swym.common.delivery
  "The processing pipeline shared by the legacy Redis consumer and the new
   Kafka consumer. Both hops through the same 3 steps - continue the trace,
   simulate the fan-out work, record the outcome - and differ only in their
   reliability parameters (failure rates) and where the DLQ/backlog pressure
   shows up. That symmetry is what makes the before/after comparison honest:
   the isolation and stability gains come from the *topology* (shared list +
   fixed pool vs partitioned topics + per-tier consumer groups), not from
   hand-tuned numbers."
  (:require [swym.common.config :as cfg]
            [swym.common.events :as ev]
            [swym.common.redis :as redis]
            [swym.common.tracing :as t]
            [clojure.tools.logging :as log]))

(defn- processing-ms [subscriber-count]
  (+ cfg/base-processing-overhead-ms
     (/ subscriber-count cfg/sends-per-ms-per-worker)
     (rand-int 20)))

(defn- per-recipient-outcome [subscriber-count failure-rate]
  (let [failed (min subscriber-count
                     (long (Math/round (* subscriber-count failure-rate (+ 0.5 (rand))))))]
    {:delivered (- subscriber-count failed) :failed failed}))

(defn- sla-target-ms [tier]
  (if (= tier "enterprise") cfg/sla-target-ms-enterprise cfg/sla-target-ms-standard))

(defn process-envelope!
  "opts: {:tracer :path (:legacy|:new) :failure-rate :hard-failure-rate}
   Returns the result map that was published (handy for logging/tests)."
  [{:keys [tracer path failure-rate hard-failure-rate]} envelope-json]
  (let [env (ev/<-json envelope-json)
        remote-parent {:trace-id (:parent-trace-id env) :parent-span-id (:parent-span-id env)}
        span (t/start-span tracer (str (name path) "-dequeue")
                            {:kind :consumer :remote-parent remote-parent
                             :attrs {:merchant-id (:merchant-id env) :tier (:tier env)
                                     :queue-id (:queue-id env) :path (name path)}})
        subscriber-count (long (:subscriber-count env))
        proc-ms (max 1 (long (processing-ms subscriber-count)))]
    (Thread/sleep proc-ms) ;; occupies this worker - this is where a shared, unisolated
                           ;; pool lets one merchant's burst delay everyone else's alerts.
    (let [result
          (if (< (rand) hard-failure-rate)
            (do
              (redis/dlq-push envelope-json)
              (t/finish! span :error {:reason "processing-failure"})
              {:path (name path) :merchant-id (:merchant-id env) :tier (:tier env)
               :queue-id (:queue-id env) :event-type (:event-type env) :mode (:mode env)
               :delivered 0 :failed subscriber-count :dlq 1
               :latency-ms (- (ev/now-ms) (:created-at env))
               :sla-target-ms (sla-target-ms (:tier env)) :breach 1
               :trace-id (:trace-id env)})
            (let [{:keys [delivered failed]} (per-recipient-outcome subscriber-count failure-rate)
                  latency (- (ev/now-ms) (:created-at env))
                  target (sla-target-ms (:tier env))]
              (t/finish! span :ok {:delivered delivered :failed failed :latency-ms latency})
              {:path (name path) :merchant-id (:merchant-id env) :tier (:tier env)
               :queue-id (:queue-id env) :event-type (:event-type env) :mode (:mode env)
               :delivered delivered :failed failed :dlq 0
               :latency-ms latency :sla-target-ms target
               :breach (if (> latency target) 1 0)
               :trace-id (:trace-id env)}))]
      (try
        (redis/publish-result! result)
        (catch Exception e (log/error e "failed to publish result")))
      result)))
