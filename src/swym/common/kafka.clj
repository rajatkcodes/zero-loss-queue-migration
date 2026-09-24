(ns swym.common.kafka
  (:require [swym.common.config :as cfg])
  (:import [java.util Properties Collections]
           [java.time Duration]
           [org.apache.kafka.clients.producer KafkaProducer ProducerRecord Callback RecordMetadata]
           [org.apache.kafka.clients.consumer KafkaConsumer ConsumerRecord]
           [org.apache.kafka.common.serialization StringSerializer StringDeserializer]))

(defonce ^:private producer-instance (atom nil))

(defn producer ^KafkaProducer []
  (or @producer-instance
      (reset! producer-instance
              (KafkaProducer.
               (doto (Properties.)
                 (.put "bootstrap.servers" cfg/kafka-bootstrap)
                 (.put "key.serializer" (.getName StringSerializer))
                 (.put "value.serializer" (.getName StringSerializer))
                 (.put "acks" "all")
                 (.put "linger.ms" "5"))))))

(defn send!
  "Async produce. `key` (usually merchant-id) controls partitioning so a given
   merchant's events always land on the same partition -> ordering per tenant."
  [topic key value & [on-complete]]
  (.send (producer)
         (ProducerRecord. ^String topic ^String key ^String value)
         (reify Callback
           (onCompletion [_ metadata ex]
             (when on-complete (on-complete metadata ex))))))

(defn make-consumer ^KafkaConsumer [group-id topics]
  (let [c (KafkaConsumer.
           (doto (Properties.)
             (.put "bootstrap.servers" cfg/kafka-bootstrap)
             (.put "group.id" group-id)
             (.put "key.deserializer" (.getName StringDeserializer))
             (.put "value.deserializer" (.getName StringDeserializer))
             (.put "auto.offset.reset" "earliest")
             (.put "enable.auto.commit" "true")
             (.put "max.poll.records" "50")))]
    (.subscribe c (java.util.ArrayList. topics))
    c))

(defn poll-records
  "Returns a seq of {:topic :partition :offset :key :value} for records polled
   within `timeout-ms`."
  [^KafkaConsumer consumer timeout-ms]
  (let [records (.poll consumer (Duration/ofMillis timeout-ms))]
    (map (fn [^ConsumerRecord r]
           {:topic (.topic r) :partition (.partition r) :offset (.offset r)
            :key (.key r) :value (.value r)})
         (iterator-seq (.iterator records)))))

(defn ensure-topics!
  "Creates topics if they don't already exist (idempotent). Used at startup so a
   fresh docker-compose stack is immediately usable."
  [topic->partitions]
  (with-open [admin (org.apache.kafka.clients.admin.Admin/create
                     (doto (Properties.) (.put "bootstrap.servers" cfg/kafka-bootstrap)))]
    (let [existing (into #{} (.get (.names (.listTopics admin))))
          to-create (for [[topic partitions] topic->partitions
                          :when (not (existing topic))]
                      (org.apache.kafka.clients.admin.NewTopic. ^String topic (int partitions) (short 1)))]
      (when (seq to-create)
        (.get (.all (.createTopics admin to-create)))))))
