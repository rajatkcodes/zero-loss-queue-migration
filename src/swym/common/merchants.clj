(ns swym.common.merchants
  "The simulated merchant catalog: deterministic tier assignment and which of
   the 5 core queues each merchant's traffic belongs to. Shared by the
   event-generator (needs it to produce traffic) and the control-plane (needs
   it to render the migration control panel)."
  (:require [swym.common.config :as cfg]
            [swym.common.events :as ev]))

(def all
  (vec (for [n (range cfg/merchant-count)]
         (let [tier (if (ev/enterprise? n cfg/enterprise-merchant-ratio cfg/merchant-count) :enterprise :standard)
               queues (filter #(= (:tier %) tier) cfg/core-queues)]
           {:n n
            :id (ev/merchant-id n)
            :tier tier
            :queue-id (:id (nth queues (mod n (max 1 (count queues)))))}))))

(def by-id (into {} (map (fn [m] [(:id m) m]) all)))
