(ns swym.services.metrics-aggregator
  "Turns the raw delivery-outcome stream into the thing the JD actually asks
   for: 'high-signal, actionable observability' - per-merchant SLA dashboards,
   a P1-P4 incident feed, and a single number for 'how much of the migration
   is done'. Nothing here is a trace viewer (that's the control-plane reading
   swym:traces:stream directly); this is purely the SLA/incident rollup."
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [swym.common.config :as cfg]
            [swym.common.redis :as redis]
            [swym.common.events :as ev]
            [cheshire.core :as json]))

(def window-ms 60000)
(def incident-cooldown-ms 30000)

(defonce state (atom {}))              ;; merchant-id -> [entries]
(defonce last-incident-at (atom {}))   ;; incident-key -> ts, for debouncing
(defonce prev-queue-modes (atom {}))

(defn- parse-entry [fields]
  (let [m (apply hash-map fields)]
    {:ts (ev/now-ms)
     :merchant-id (get m "merchant-id")
     :tier (get m "tier")
     :queue-id (get m "queue-id")
     :event-type (get m "event-type")
     :mode (get m "mode")
     :path (get m "path")
     :delivered (Long/parseLong (get m "delivered" "0"))
     :failed (Long/parseLong (get m "failed" "0"))
     :dlq (Long/parseLong (get m "dlq" "0"))
     :latency-ms (Double/parseDouble (get m "latency-ms" "0"))
     :breach (Long/parseLong (get m "breach" "0"))}))

(defn- ingest! [entries]
  (doseq [[_id fields] entries]
    (try
      (let [parsed (parse-entry fields)]
        (swap! state update (:merchant-id parsed) (fnil conj []) parsed))
      (catch Exception e (log/error e "failed to parse result entry")))))

(defn- prune! []
  (let [cutoff (- (ev/now-ms) window-ms)]
    (swap! state
           (fn [s] (into {} (keep (fn [[k vs]]
                                     (let [kept (filterv #(>= (:ts %) cutoff) vs)]
                                       (when (seq kept) [k kept])))
                                   s))))))

(defn- percentile [sorted-vals p]
  (if (empty? sorted-vals)
    0.0
    (nth sorted-vals (min (dec (count sorted-vals)) (long (* p (count sorted-vals)))))))

(defn- summarize [entries]
  (let [latencies (sort (map :latency-ms entries))
        delivered (reduce + (map :delivered entries))
        failed (reduce + (map :failed entries))
        dlq (reduce + (map :dlq entries))
        breaches (reduce + (map :breach entries))
        total-attempts (+ delivered failed)]
    {:sample-count (count entries)
     :delivered delivered
     :failed failed
     :dlq dlq
     :error-rate (if (pos? total-attempts) (/ (double failed) total-attempts) 0.0)
     :breach-rate (if (pos? (count entries)) (/ (double breaches) (count entries)) 0.0)
     :p50-latency-ms (percentile latencies 0.50)
     :p95-latency-ms (percentile latencies 0.95)
     :p99-latency-ms (percentile latencies 0.99)
     :tier (:tier (first entries))
     :queue-id (:queue-id (first entries))
     :mode (:mode (last entries))}))

(defn- build-snapshot []
  (let [s @state
        merchants (into {} (map (fn [[mid entries]] [mid (summarize entries)]) s))
        all-entries (mapcat val s)
        by-queue (group-by :queue-id all-entries)
        queues (into {} (map (fn [[qid entries]] [qid (summarize entries)]) by-queue))
        queue-modes (redis/all-queue-modes)
        migrated-count (count (filter #(= % :new) (vals queue-modes)))]
    {:generated-at (ev/now-ms)
     :legacy-queue-depth (redis/legacy-queue-depth)
     :dlq-depth (redis/dlq-depth)
     :legacy-down (redis/legacy-down?)
     :core-queues-migrated migrated-count
     :core-queues-total (count cfg/core-queues)
     :queue-modes (into {} (map (fn [[k v]] [k (name v)]) queue-modes))
     :merchants merchants
     :queues queues}))

(defn- maybe-incident! [key severity title detail]
  (let [now (ev/now-ms)
        last (get @last-incident-at key 0)]
    (when (> (- now last) incident-cooldown-ms)
      (swap! last-incident-at assoc key now)
      (redis/push-incident!
       (json/generate-string {:id (ev/new-id) :ts now :severity severity :title title :detail detail}))
      (log/warn "incident" {:severity severity :title title :detail detail}))))

(defn- detect-incidents! [snapshot]
  (doseq [[mid stats] (:merchants snapshot)
          :when (>= (:sample-count stats) 5)]
    (when (> (:breach-rate stats) 0.05)
      (maybe-incident! (str "breach:" mid)
                        (if (= (:tier stats) "enterprise") "P1" "P2")
                        (str "SLA breach rate elevated for " mid)
                        (assoc (select-keys stats [:breach-rate :p95-latency-ms :tier :queue-id]) :merchant-id mid)))
    (when (pos? (:dlq stats))
      (maybe-incident! (str "dlq:" mid) "P2" (str "Messages hitting DLQ for " mid)
                        (assoc (select-keys stats [:dlq :queue-id]) :merchant-id mid))))
  (when (> (:legacy-queue-depth snapshot) 500)
    (maybe-incident! "backlog:legacy" "P3" "Legacy queue backlog building up"
                      {:depth (:legacy-queue-depth snapshot)}))
  (when (:legacy-down snapshot)
    (maybe-incident! "outage:legacy" "P1" "Legacy path is down (chaos test or real outage)" {}))
  (let [current (:queue-modes snapshot)
        prev @prev-queue-modes]
    (doseq [[qid mode] current]
      (when (and (contains? prev qid) (not= (get prev qid) mode))
        (maybe-incident! (str "cutover:" qid) "P4"
                          (str "Queue " qid " migration mode changed to " mode) {:queue-id qid :mode mode})))
    (reset! prev-queue-modes current)))

(defn -main [& _args]
  (log/info "metrics-aggregator starting")
  (let [last-id (atom "0")]
    (loop []
      (let [[new-last-id entries] (redis/read-results-since @last-id)]
        (reset! last-id new-last-id)
        (ingest! entries))
      (prune!)
      (let [snapshot (build-snapshot)]
        (redis/write-metrics-snapshot! (json/generate-string snapshot))
        (detect-incidents! snapshot))
      (Thread/sleep 1000)
      (recur))))
