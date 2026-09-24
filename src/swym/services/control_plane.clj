(ns swym.services.control-plane
  "HTTP + WebSocket API for the frontend: migration control (feature toggles
   per queue/merchant), the SLA dashboard feed, the trace explorer, incidents,
   chaos triggers and DLQ replay. This is the one process the UI talks to;
   everything else is backend plumbing it never sees directly."
  (:gen-class)
  (:require [org.httpkit.server :as http]
            [reitit.ring :as ring]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [swym.common.config :as cfg]
            [swym.common.redis :as redis]
            [swym.common.dlq :as dlq]
            [swym.common.merchants :as merchants]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- json-response [status body]
  {:status status :headers {"Content-Type" "application/json"} :body (json/generate-string body)})

(defn- read-json-body [req]
  (try
    (when-let [b (:body req)]
      (json/parse-string (slurp b) true))
    (catch Exception _ nil)))

(defonce ws-clients (atom #{}))

(defn- broadcast! [msg]
  (let [payload (json/generate-string msg)]
    (doseq [ch @ws-clients]
      (try (http/send! ch payload) (catch Exception _ (swap! ws-clients disj ch))))))

;; ---------------------------------------------------------------------------
;; trace buffer (background-ingested from swym:traces:stream)
;; ---------------------------------------------------------------------------

(def trace-buffer-cap 5000)
(defonce trace-buffer (atom []))

(defn- ingest-traces-loop []
  (let [last-id (atom "0")]
    (loop []
      (try
        (let [[new-last entries] (redis/read-traces-since @last-id)]
          (reset! last-id new-last)
          (when (seq entries)
            (let [parsed (keep (fn [[_id fields]]
                                  (try
                                    (let [m (apply hash-map fields)]
                                      (json/parse-string (get m "data") true))
                                    (catch Exception _ nil)))
                                entries)]
              (swap! trace-buffer
                     (fn [buf]
                       (let [combined (into buf parsed)]
                         (if (> (count combined) trace-buffer-cap)
                           (subvec combined (- (count combined) trace-buffer-cap))
                           combined)))))))
        (catch Exception e (log/error e "trace ingest failed")))
      (Thread/sleep 300)
      (recur))))

;; ---------------------------------------------------------------------------
;; handlers
;; ---------------------------------------------------------------------------

(defn health [_req] (json-response 200 {:status "ok"}))

(defn list-merchants [_req]
  (json-response 200 {:merchants (mapv #(select-keys % [:id :tier :queue-id]) merchants/all)}))

(defn list-core-queues [_req]
  (let [modes (redis/all-queue-modes)]
    (json-response 200
                   {:queues (mapv (fn [q] (assoc q :mode (name (get modes (:id q) :legacy)))) cfg/core-queues)})))

(defn set-queue-mode! [req]
  (let [queue-id (get-in req [:path-params :id])
        mode (keyword (:mode (read-json-body req)))]
    (redis/set-queue-mode! queue-id mode)
    (broadcast! {:type "queue-mode-changed" :queue-id queue-id :mode (name mode)})
    (json-response 200 {:queue-id queue-id :mode (name mode)})))

(defn set-merchant-mode! [req]
  (let [mid (get-in req [:path-params :id])
        mode (keyword (:mode (read-json-body req)))]
    (redis/set-merchant-override! mid mode)
    (broadcast! {:type "merchant-mode-changed" :merchant-id mid :mode (name mode)})
    (json-response 200 {:merchant-id mid :mode (name mode)})))

(defn clear-merchant-mode! [req]
  (let [mid (get-in req [:path-params :id])]
    (redis/clear-merchant-override! mid)
    (broadcast! {:type "merchant-mode-cleared" :merchant-id mid})
    (json-response 200 {:merchant-id mid :cleared true})))

(defn metrics-snapshot [_req]
  (if-let [s (redis/read-metrics-snapshot)]
    {:status 200 :headers {"Content-Type" "application/json"} :body s}
    (json-response 200 {})))

(defn incidents [_req]
  (json-response 200 {:incidents (mapv #(json/parse-string % true) (redis/recent-incidents))}))

(defn set-chaos! [req]
  (let [down? (boolean (:down (read-json-body req)))]
    (redis/set-legacy-down! down?)
    (broadcast! {:type "chaos" :legacy-down down?})
    (json-response 200 {:legacy-down down?})))

(defn replay-dlq! [_req]
  (let [n (dlq/replay-all!)]
    (broadcast! {:type "dlq-replay" :count n})
    (json-response 200 {:replayed n})))

(defn recent-traces [_req]
  (let [spans @trace-buffer
        by-trace (group-by :trace_id spans)
        summaries (->> by-trace
                       (map (fn [[tid spans]]
                              {:trace-id tid
                               :span-count (count spans)
                               :start-ns (apply min (map :start_ns spans))
                               :merchant-id (some #(get-in % [:attributes :merchant-id]) spans)
                               :names (mapv :name (sort-by :start_ns spans))}))
                       (sort-by :start-ns >)
                       (take 50))]
    (json-response 200 {:traces summaries})))

(defn trace-detail [req]
  (let [tid (get-in req [:path-params :id])
        spans (sort-by :start_ns (filter #(= (:trace_id %) tid) @trace-buffer))]
    (json-response 200 {:trace-id tid :spans spans})))

;; ---------------------------------------------------------------------------
;; routing
;; ---------------------------------------------------------------------------

(defn wrap-cors [handler]
  (fn [req]
    (let [cors-headers {"Access-Control-Allow-Origin" "*"
                         "Access-Control-Allow-Methods" "GET,POST,DELETE,OPTIONS"
                         "Access-Control-Allow-Headers" "Content-Type"}]
      (if (= :options (:request-method req))
        {:status 200 :headers cors-headers :body ""}
        (update (handler req) :headers merge cors-headers)))))

(def app
  (wrap-cors
   (ring/ring-handler
    (ring/router
     [["/api/health" {:get health}]
      ["/api/merchants" {:get list-merchants}]
      ["/api/queues" {:get list-core-queues}]
      ["/api/queues/:id/mode" {:post set-queue-mode!}]
      ["/api/merchants/:id/mode" {:post set-merchant-mode! :delete clear-merchant-mode!}]
      ["/api/metrics/snapshot" {:get metrics-snapshot}]
      ["/api/incidents" {:get incidents}]
      ["/api/chaos/legacy" {:post set-chaos!}]
      ["/api/dlq/replay" {:post replay-dlq!}]
      ["/api/traces" {:get recent-traces}]
      ["/api/traces/:id" {:get trace-detail}]])
    (ring/create-default-handler))))

(defn- ws-handler [req]
  (http/as-channel
   req
   {:on-open (fn [ch]
               (swap! ws-clients conj ch)
               (when-let [s (redis/read-metrics-snapshot)]
                 (http/send! ch (json/generate-string {:type "snapshot" :data (json/parse-string s true)}))))
    :on-close (fn [ch _status] (swap! ws-clients disj ch))
    :on-receive (fn [_ch _data] nil)}))

(defn handler [req]
  (if (= (:uri req) "/ws/live")
    (ws-handler req)
    (app req)))

;; ---------------------------------------------------------------------------
;; background loops
;; ---------------------------------------------------------------------------

(defn- broadcaster-loop []
  (loop []
    (try
      (when-let [s (redis/read-metrics-snapshot)]
        (broadcast! {:type "snapshot" :data (json/parse-string s true)}))
      (catch Exception e (log/error e "broadcast failed")))
    (Thread/sleep 1000)
    (recur)))

(defn- dlq-auto-replay-loop []
  (loop []
    (Thread/sleep 10000)
    (try
      (let [n (dlq/replay-all!)]
        (when (pos? n) (log/info "dlq auto-replay" {:count n})))
      (catch Exception e (log/error e "dlq auto-replay failed")))
    (recur)))

(defn -main [& _args]
  (log/info "control-plane starting" {:port cfg/control-plane-port})
  (future (ingest-traces-loop))
  (future (broadcaster-loop))
  (future (dlq-auto-replay-loop))
  (http/run-server handler {:port cfg/control-plane-port})
  @(promise))
