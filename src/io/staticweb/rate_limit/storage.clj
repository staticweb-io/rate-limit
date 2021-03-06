(ns io.staticweb.rate-limit.storage
  (:require [clj-time.core :as t]))

(set! *warn-on-reflection* true)


(defprotocol Storage
  "A protocol describing the interface for storage backends.

  `get-count` is used to read the current counter value for a given key.

  `increment-count` is used to increment the counter for a given
  key. This function is responsible also for creating the counter if
  it doesn't exist already, and for scheduling the counter to expire
  after the provided delay.

  `counter-expiry` is used to return a timestamp of when the counter
  will expire, ie when the rate limit is reset again."

  (get-count [self key])
  (increment-count [self key ttl])
  (counter-expiry [self key])
  (clear-counters [self]))

;;; LocalStorage implementation
(defn- expired-keys
  [m now]
  (->> (:timeouts m)
       (filter (fn [[k v]] (t/before? v now)))
       (map first)))

(defn- remove-key
  [state key]
  (-> state
      (update-in [:counters] dissoc key)
      (update-in [:timeouts] dissoc key)))

(defn- remove-expired-keys
  [state]
  (doseq [k (expired-keys @state (t/now))]
    (swap! state remove-key k)))

(defn- increment-key
  "Increment the counter in the state map.

  If the counter didn't exist already, we also record the time when
  the counter expires."
  [state key ttl]
  (if (get-in state [:counters key])
    (update-in state [:counters key] inc)
    (->
     state
     (assoc-in [:counters key] 1)
     (assoc-in [:timeouts key] (t/plus (t/now) ttl)))))

(defrecord LocalStorage [state]
  Storage
  (get-count [self key]
    (remove-expired-keys state)
    (get-in @state [:counters key] 0))

  (increment-count [self key ttl]
    (swap! state increment-key key ttl)
    nil)

  (counter-expiry [self key]
    (if-let [timeout (get-in @state [:timeouts key])]
      timeout
      (t/now)))

  (clear-counters [self]
    (reset! state {})))

(defn local-storage
  "Instantiate a new LocalStorage storage implementation.

  Accepts an optional atom wrapping a map. This allows the same atom
  to be shared by multiple LocalStorage instances.

  If an argument is not provided, a new atom will be created."
  [& [backing-atom]]
  {:pre [(or (nil? backing-atom) (instance? clojure.lang.Atom backing-atom))
         (or (nil? backing-atom) (map? @backing-atom))]}
  (->LocalStorage (or backing-atom (atom {}))))
