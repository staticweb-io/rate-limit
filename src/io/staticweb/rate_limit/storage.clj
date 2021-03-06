(ns io.staticweb.rate-limit.storage
  (:require [clj-time.core :as t]
            [taoensso.carmine :as car]))

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


;;; RedisStorage implementation
(def ttl-incr-script
  (str
   "local current = redis.call(\"incr\", KEYS[1])"
   "if tonumber(current) == 1 then"
   "  redis.call(\"expire\", KEYS[1], ARGV[1])"
   "end"))

(def prefix "io.staticweb.rate-limit-")

(defn- generate-redis-key
  [key]
  (str prefix key))

(defrecord RedisStorage [conn-opts]
  Storage
  (get-count [self key]
    (let [redis-key (generate-redis-key key)]
      (if-let [counter (car/wcar
                        conn-opts
                        (car/get redis-key))]
        (Integer. counter)
        0)))

  (increment-count [self key ttl]
    (let [redis-key (generate-redis-key key)
          ttl-in-secs (-> ttl .toStandardDuration .getStandardSeconds)]
      (car/wcar
       conn-opts
       (car/eval* ttl-incr-script 1 redis-key ttl-in-secs))))

  (counter-expiry [self key]
    (let [now (t/now)
          redis-key (generate-redis-key key)
          ttl (car/wcar conn-opts (car/ttl redis-key))]
      (if (neg? ttl)
        now
        (t/plus now (t/seconds ttl)))))

  (clear-counters [self]
    (let [redis-key (generate-redis-key "*")]
      (loop [curr-pos "0"]
        (let [[next-pos keys] (car/wcar
                               conn-opts
                               (car/scan curr-pos "match" redis-key))]
                (when (seq keys)
                  (car/wcar conn-opts (apply car/del keys)))

                (when (not= next-pos "0")
                  (recur next-pos)))))))

(defn redis-storage
  [conn-opts]
  (->RedisStorage conn-opts))
