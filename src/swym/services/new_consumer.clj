(ns swym.services.new-consumer
  "The 'after' architecture: per-tier Kafka topics, each with its own consumer
   group and worker pool. Standard and enterprise traffic can never contend for
   the same workers - that's the 'noisy-neighbor isolation' and 'dedicated
   priority lane for enterprise merchants' from the JD, achieved structurally
   (separate topic + separate group) rather than by throttling logic.
   Partitioning by merchant-id also keeps a given merchant's events in order."
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [swym.common.config :as cfg]
            [swym.common.kafka :as kafka]
            [swym.common.delivery :as delivery]
            [swym.common.tracing :as t]))

(defn- worker-loop [tracer topic group-id worker-n failure-rate hard-failure-rate]
  (log/info "new-consumer worker starting" {:topic topic :group group-id :worker worker-n})
  (let [consumer (kafka/make-consumer group-id [topic])]
    (loop []
      (doseq [{:keys [value]} (kafka/poll-records consumer 500)]
        (try
          (delivery/process-envelope! {:tracer tracer :path :new
                                        :failure-rate failure-rate
                                        :hard-failure-rate hard-failure-rate}
                                       value)
          (catch Exception e
            (log/error e "new-consumer worker failed processing record" {:topic topic :worker worker-n}))))
      (recur))))

(defn -main [& _args]
  (let [tracer (t/init-tracer! "new-consumer")]
    (kafka/ensure-topics! {cfg/topic-standard cfg/standard-partitions
                            cfg/topic-enterprise cfg/enterprise-partitions
                            cfg/topic-dlq 1})
    (log/info "new-consumer starting"
              {:standard-workers cfg/new-standard-worker-count
               :enterprise-workers cfg/new-enterprise-worker-count})
    (doall (map deref
                (concat
                 (mapv (fn [n] (future (worker-loop tracer cfg/topic-standard "new-consumer-standard" n
                                                     cfg/new-failure-rate cfg/new-hard-failure-rate)))
                       (range cfg/new-standard-worker-count))
                 (mapv (fn [n] (future (worker-loop tracer cfg/topic-enterprise "new-consumer-enterprise" n
                                                     cfg/new-failure-rate cfg/new-hard-failure-rate)))
                       (range cfg/new-enterprise-worker-count)))))))
