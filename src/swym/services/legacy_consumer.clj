(ns swym.services.legacy-consumer
  "The 'before' architecture: a single shared Redis list, drained by a fixed
   pool of workers with no per-merchant or per-tier isolation. Under load this
   is exactly what the JD describes - one merchant's burst can delay every
   other merchant's alerts, because there is only one FIFO line and it doesn't
   know whose message is whose. Also respects the chaos toggle: when 'legacy
   is down', workers stop popping (but the list keeps growing - Redis Lists
   are durable, so nothing is lost, it's just delayed) to model an outage."
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [swym.common.config :as cfg]
            [swym.common.redis :as redis]
            [swym.common.delivery :as delivery]
            [swym.common.tracing :as t]))

(defn- worker-loop [tracer worker-n]
  (log/info "legacy worker starting" {:worker worker-n})
  (loop []
    (if (redis/legacy-down?)
      (Thread/sleep 500)
      (when-let [payload (redis/legacy-blocking-pop 1)]
        (try
          (delivery/process-envelope! {:tracer tracer :path :legacy
                                        :failure-rate cfg/legacy-failure-rate
                                        :hard-failure-rate cfg/legacy-hard-failure-rate}
                                       payload)
          (catch Exception e
            (log/error e "legacy worker failed processing envelope" {:worker worker-n})))))
    (recur)))

(defn -main [& _args]
  (let [tracer (t/init-tracer! "legacy-consumer")]
    (log/info "legacy-consumer starting" {:workers cfg/legacy-worker-count})
    (doall (map deref
                (mapv (fn [n] (future (worker-loop tracer n)))
                      (range cfg/legacy-worker-count))))))
