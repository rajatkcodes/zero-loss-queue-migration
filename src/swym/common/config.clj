(ns swym.common.config
  "Central place for every knob the showcase pipeline needs.
   Everything is overridable via env var so each process/container can be tuned
   independently, mirroring how the real swym-integration services are configured.")

(defn env
  ([k] (System/getenv k))
  ([k default] (or (System/getenv k) default)))

(defn env-int [k default]
  (if-let [v (System/getenv k)] (Long/parseLong v) default))

;; ---------------------------------------------------------------------------
;; Infra endpoints
;; ---------------------------------------------------------------------------

(def redis-uri (env "SWYM_REDIS_URI" "redis://127.0.0.1:6390"))
(def kafka-bootstrap (env "SWYM_KAFKA_BOOTSTRAP" "127.0.0.1:19092"))

;; ---------------------------------------------------------------------------
;; HTTP / WS
;; ---------------------------------------------------------------------------

(def control-plane-port (env-int "SWYM_CONTROL_PLANE_PORT" 8901))

;; ---------------------------------------------------------------------------
;; Redis key / stream names (legacy path + shared bookkeeping)
;; ---------------------------------------------------------------------------

(def legacy-queue-key "swym:legacy:queue")          ;; single shared List -> no tenant isolation (the pain point)
(def legacy-inflight-key "swym:legacy:inflight")     ;; hash of event-id -> worker, for crash visibility
(def dlq-list-key "swym:dlq:legacy")                 ;; legacy path DLQ (kafka has its own topic DLQ)
(def flags-hash-key "swym:migration:flags")          ;; merchant-id -> mode (legacy|dual-write|new)
(def queue-flags-hash-key "swym:migration:queues")   ;; queue-name -> mode, the "5 core queues" toggle
(def results-stream-key "swym:results:stream")        ;; delivery outcomes, consumed by metrics-aggregator
(def traces-stream-key "swym:traces:stream")          ;; OTel spans exported here (see swym.common.tracing)
(def incidents-list-key "swym:incidents:log")         ;; P1-P4 incident feed
(def metrics-snapshot-key "swym:metrics:snapshot")    ;; latest computed per-merchant SLA snapshot (JSON)
(def chaos-legacy-down-key "swym:chaos:legacy-down")  ;; boolean flag the legacy consumer polls

;; ---------------------------------------------------------------------------
;; Kafka topics (the "new" path)
;; ---------------------------------------------------------------------------

(def topic-standard "swym.alerts.standard")           ;; partitioned by merchant-id hash
(def topic-enterprise "swym.alerts.enterprise")       ;; dedicated priority lane, separate consumer group
(def topic-dlq "swym.alerts.dlq")

(def standard-partitions 6)
(def enterprise-partitions 3)

;; ---------------------------------------------------------------------------
;; Domain constants
;; ---------------------------------------------------------------------------

;; The "5 core queues" the JD's 6-month milestone calls for. Each is independently
;; feature-toggled between :legacy / :dual-write / :new so the frontend can show
;; "N/5 core queues migrated" ticking up exactly like the real roadmap.
(def core-queues
  [{:id "wishlist-plus"              :label "Wishlist Plus"                 :tier :standard}
   {:id "back-in-stock"              :label "Back in Stock"                 :tier :standard}
   {:id "price-drop"                 :label "Price Drop"                    :tier :standard}
   {:id "back-in-stock-enterprise"   :label "Back in Stock (Enterprise)"    :tier :enterprise}
   {:id "price-drop-enterprise"      :label "Price Drop (Enterprise)"       :tier :enterprise}])

(def event-types [:restock :price-drop])
(def channels [:email :sms :push])

(def merchant-count (env-int "SWYM_MERCHANT_COUNT" 24))
(def enterprise-merchant-ratio 0.2) ;; ~20% of simulated merchants are "enterprise" tier

;; SLA targets used by metrics-aggregator to flag breaches (ms, end-to-end enqueue->delivered)
(def sla-target-ms-standard 5000)
(def sla-target-ms-enterprise 1500)

;; Simulated failure rates (per delivery attempt) - deliberately asymmetric:
;; the legacy path has no retry/isolation so it fails more under load.
(def legacy-failure-rate 0.03)
(def new-failure-rate 0.008)

;; Whole-event processing failures (worker crash, malformed payload, provider
;; outage) - these are what actually go to the DLQ, as opposed to the
;; per-recipient soft-bounce rates above which just move the error-rate metric.
(def legacy-hard-failure-rate 0.02)
(def new-hard-failure-rate 0.004)

;; Simulated fan-out throughput: sends/ms per worker, drives processing latency.
(def sends-per-ms-per-worker 3.0)
(def base-processing-overhead-ms 15)

(def legacy-worker-count (env-int "SWYM_LEGACY_WORKERS" 4))
(def new-standard-worker-count (env-int "SWYM_NEW_STANDARD_WORKERS" 6))
(def new-enterprise-worker-count (env-int "SWYM_NEW_ENTERPRISE_WORKERS" 4))
