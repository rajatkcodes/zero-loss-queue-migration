(ns swym.common.tracing
  "Real OpenTelemetry SDK usage (not a homegrown stand-in): each service builds
   its own SdkTracerProvider and exports finished spans through a custom
   SpanExporter straight into a Redis Stream, which the control-plane's trace
   explorer and the metrics-aggregator both read.

   Cross-process propagation is manual (no HTTP headers here - the transport is
   a Redis list / Kafka record), so instead of a W3C traceparent header we carry
   {trace-id, parent-span-id} inside the message envelope itself and rebuild a
   remote parent SpanContext with it on the next hop. That's the same idea the
   W3C header encodes, just carried in our own payload."
  (:require [swym.common.config :as cfg]
            [swym.common.redis :as redis]
            [taoensso.carmine :as car]
            [cheshire.core :as json])
  (:import [io.opentelemetry.sdk OpenTelemetrySdk]
           [io.opentelemetry.sdk.trace SdkTracerProvider]
           [io.opentelemetry.sdk.trace.export SpanExporter SimpleSpanProcessor]
           [io.opentelemetry.sdk.trace.data SpanData]
           [io.opentelemetry.sdk.common CompletableResultCode]
           [io.opentelemetry.sdk.resources Resource]
           [io.opentelemetry.api.common Attributes AttributeKey]
           [io.opentelemetry.api.trace Tracer Span SpanKind StatusCode
            SpanContext TraceFlags TraceState]
           [io.opentelemetry.context Context]))

(defn- span-data->map [^SpanData s]
  (let [ctx (.getSpanContext s)
        parent (.getParentSpanContext s)]
    {:trace_id (.getTraceId ctx)
     :span_id (.getSpanId ctx)
     :parent_span_id (when (.isValid parent) (.getSpanId parent))
     :name (.getName s)
     :kind (str (.getKind s))
     :start_ns (.getStartEpochNanos s)
     :end_ns (.getEndEpochNanos s)
     :duration_ms (/ (- (.getEndEpochNanos s) (.getStartEpochNanos s)) 1000000.0)
     :status (str (.. s getStatus getStatusCode))
     :attributes (into {} (map (fn [[k v]] [(.getKey k) (str v)])
                                (.asMap (.getAttributes s))))}))

(defn- redis-span-exporter ^SpanExporter []
  (reify SpanExporter
    (export [_ spans]
      (try
        (doseq [^SpanData s spans]
          (redis/wcar* (car/xadd cfg/traces-stream-key "*"
                                  "data" (json/generate-string (span-data->map s)))))
        (CompletableResultCode/ofSuccess)
        (catch Exception _
          (CompletableResultCode/ofFailure))))
    (flush [_] (CompletableResultCode/ofSuccess))
    (shutdown [_] (CompletableResultCode/ofSuccess))))

(defn init-tracer!
  "Call once per process. Returns a Tracer scoped to `service-name`."
  ^Tracer [service-name]
  (let [resource (.build (doto (Resource/builder)
                            (.put (AttributeKey/stringKey "service.name") ^String service-name)))
        provider (-> (SdkTracerProvider/builder)
                     (.addSpanProcessor (SimpleSpanProcessor/create (redis-span-exporter)))
                     (.setResource resource)
                     (.build))
        sdk (-> (OpenTelemetrySdk/builder)
                (.setTracerProvider provider)
                (.buildAndRegisterGlobal))]
    (.getTracer sdk service-name)))

(defn- attrs->otel ^Attributes [m]
  (let [b (Attributes/builder)]
    (doseq [[k v] m] (.put b ^String (name k) ^String (str v)))
    (.build b)))

(defn- remote-parent-context ^Context [trace-id parent-span-id]
  (let [span-ctx (SpanContext/createFromRemoteParent
                   trace-id parent-span-id (TraceFlags/getSampled) (TraceState/getDefault))]
    (.storeInContext (Span/wrap span-ctx) (Context/root))))

(defn start-span
  "opts: {:kind :server|:client|:internal|:producer|:consumer
          :attrs {..}
          :remote-parent {:trace-id .. :parent-span-id ..}}  ; from an incoming envelope, or nil for a new trace root"
  ^Span [^Tracer tracer span-name {:keys [kind attrs remote-parent]}]
  (let [builder (.spanBuilder tracer span-name)
        kind-enum (case (or kind :internal)
                    :server SpanKind/SERVER :client SpanKind/CLIENT
                    :producer SpanKind/PRODUCER :consumer SpanKind/CONSUMER
                    SpanKind/INTERNAL)]
    (.setSpanKind builder kind-enum)
    (when (seq attrs) (.setAllAttributes builder (attrs->otel attrs)))
    (when remote-parent
      (.setParent builder (remote-parent-context (:trace-id remote-parent) (:parent-span-id remote-parent))))
    (.startSpan builder)))

(defn span-ids [^Span span]
  (let [ctx (.getSpanContext span)]
    {:trace-id (.getTraceId ctx) :span-id (.getSpanId ctx)}))

(defn finish!
  ([^Span span] (finish! span :ok nil))
  ([^Span span status] (finish! span status nil))
  ([^Span span status extra-attrs]
   (when (seq extra-attrs)
     (doseq [[k v] extra-attrs] (.setAttribute span ^String (name k) ^String (str v))))
   (.setStatus span (if (= status :error) StatusCode/ERROR StatusCode/OK))
   (.end span)))
