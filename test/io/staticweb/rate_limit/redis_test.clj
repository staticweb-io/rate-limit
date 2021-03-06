(ns io.staticweb.rate-limit.redis-test
  (:use clojure.test
        io.staticweb.rate-limit.redis
        io.staticweb.rate-limit.storage)
  (:import [java.time Duration Instant]))

(def ^:dynamic *storage* nil)

(defmacro with-redis-storage
  [& body]
  `(binding [*storage* (->RedisStorage {:spec {:host "localhost" :port 6379}})]
     (clear-counters *storage*)
     ~@body
     (clear-counters *storage*)))

(deftest ^:redis test-redis-storage-increments-counters
  (with-redis-storage
    (doseq [k [:increments-counters-b
               :increments-counters-a
               :increments-counters-a
               :increments-counters-b
               :increments-counters-b
               :increments-counters-c]]
      (increment-count *storage* k (Duration/ofSeconds 10)))

    (is (= (get-count *storage* :increments-counters-a) 2))
    (is (= (get-count *storage* :increments-counters-b) 3))
    (is (= (get-count *storage* :increments-counters-c) 1))
    (is (= (get-count *storage* :increments-counters-d) 0))))

(deftest ^:redis test-redis-storage-clears-counters
  (with-redis-storage
    (increment-count *storage* :clears-counters-a (Duration/ofSeconds 10))
    (increment-count *storage* :clears-counters-b (Duration/ofMinutes 10))

    (clear-counters *storage*)
    (is (= (get-count *storage* :clears-counters-a) 0))
    (is (= (get-count *storage* :clears-counters-b) 0))))

(deftest ^:redis test-redis-storage-expires-counters
  (with-redis-storage
    (increment-count *storage* :expiring-counters-a (Duration/ofSeconds 1))
    (increment-count *storage* :expiring-counters-b (Duration/ofMinutes 10))

    (Thread/sleep 1100)

    (is (= (get-count *storage* :expiring-counters-a) 0))
    (is (= (get-count *storage* :expiring-counters-b) 1))))

(deftest ^:redis test-redis-storage-returns-expiration-time
  (with-redis-storage
    (let [now* (Instant/now)]
      (with-redefs [now (constantly now*)]
        (increment-count *storage* :expiration-time-a (Duration/ofSeconds 10))
        (increment-count *storage* :expiration-time-b (Duration/ofMinutes 10))

        (is (not (.isBefore (counter-expiry *storage* :expiration-time-a)
                            (.plus now* (Duration/ofSeconds 10)))))
        (is (not (.isBefore (counter-expiry *storage* :expiration-time-b)
                            (.plus now* (Duration/ofMinutes 10)))))

        ;; An expired key is the same as a non-existent key
        (is (= (counter-expiry *storage* :expired-key) now*))))))
