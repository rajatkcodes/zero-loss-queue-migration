(ns swym.common.redis
  (:require [taoensso.carmine :as car]
            [swym.common.config :as cfg]))

(def conn {:pool {} :spec {:uri cfg/redis-uri}})

(defmacro wcar* [& body] `(car/wcar conn ~@body))

;; -- legacy queue (single shared list => no tenant isolation, the pain point) --

(defn legacy-push [payload-json]
  (wcar* (car/lpush cfg/legacy-queue-key payload-json)))

(defn legacy-blocking-pop
  "Blocks up to `timeout-s` seconds for the next item. Returns the payload string or nil."
  [timeout-s]
  (when-let [[_ v] (wcar* (car/brpop cfg/legacy-queue-key timeout-s))]
    v))

(defn legacy-queue-depth []
  (wcar* (car/llen cfg/legacy-queue-key)))

;; -- legacy DLQ --

(defn dlq-push [payload-json]
  (wcar* (car/lpush cfg/dlq-list-key payload-json)))

(defn dlq-pop-all []
  (let [items (wcar* (car/lrange cfg/dlq-list-key 0 -1))]
    (when (seq items)
      (wcar* (car/del cfg/dlq-list-key)))
    items))

(defn dlq-depth []
  (wcar* (car/llen cfg/dlq-list-key)))

;; -- chaos toggle --

(defn legacy-down? []
  (= "1" (wcar* (car/get cfg/chaos-legacy-down-key))))

(defn set-legacy-down! [down?]
  (wcar* (car/set cfg/chaos-legacy-down-key (if down? "1" "0"))))

;; -- migration feature flags --
;; merchant-level: legacy | dual-write | new
;; queue-level (the "5 core queues"): same modes, merchant flags are seeded from
;; whichever queue the merchant's traffic belongs to, but can be overridden per-merchant.

(defn get-queue-mode [queue-id]
  (keyword (or (wcar* (car/hget cfg/queue-flags-hash-key queue-id)) "legacy")))

(defn set-queue-mode! [queue-id mode]
  (wcar* (car/hset cfg/queue-flags-hash-key queue-id (name mode))))

(defn all-queue-modes []
  (let [m (wcar* (car/hgetall cfg/queue-flags-hash-key))]
    (into {} (map (fn [[k v]] [k (keyword v)]) (partition 2 m)))))

(defn get-merchant-override [merchant-id]
  (when-let [v (wcar* (car/hget cfg/flags-hash-key merchant-id))]
    (keyword v)))

(defn set-merchant-override! [merchant-id mode]
  (wcar* (car/hset cfg/flags-hash-key merchant-id (name mode))))

(defn clear-merchant-override! [merchant-id]
  (wcar* (car/hdel cfg/flags-hash-key merchant-id)))

;; -- results stream (delivery outcomes -> metrics-aggregator) --

(defn publish-result! [result-map]
  (wcar* (apply car/xadd cfg/results-stream-key "*"
                (mapcat (fn [[k v]] [(name k) (str v)]) result-map))))

(defn read-results-since
  "Reads entries after `last-id` (or from the start with \"0\"), returns [last-id entries]."
  [last-id]
  (let [resp (wcar* (car/xread "COUNT" 500 "STREAMS" cfg/results-stream-key last-id))]
    (if (seq resp)
      (let [[_stream entries] (first resp)]
        [(first (last entries)) entries])
      [last-id []])))

;; -- incidents --

(defn push-incident! [incident-json]
  (wcar* (car/lpush cfg/incidents-list-key incident-json))
  (wcar* (car/ltrim cfg/incidents-list-key 0 199)))

(defn recent-incidents []
  (wcar* (car/lrange cfg/incidents-list-key 0 199)))

;; -- metrics snapshot --

(defn write-metrics-snapshot! [json-str]
  (wcar* (car/set cfg/metrics-snapshot-key json-str)))

(defn read-metrics-snapshot []
  (wcar* (car/get cfg/metrics-snapshot-key)))

;; -- traces --

(defn read-traces-since [last-id]
  (let [resp (wcar* (car/xread "COUNT" 1000 "STREAMS" cfg/traces-stream-key last-id))]
    (if (seq resp)
      (let [[_stream entries] (first resp)]
        [(first (last entries)) entries])
      [last-id []])))
