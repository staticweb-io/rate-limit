(ns io.staticweb.rate-limit.storage-test
  (:use clojure.test
        io.staticweb.rate-limit.storage)
  (:import [java.time Duration Instant]))

;; LocalStorage tests
(deftest ^:unit test-local-storage-increments-counters
  (let [storage (local-storage)]
    (doseq [k [:increments-counters-b
               :increments-counters-a
               :increments-counters-a
               :increments-counters-b
               :increments-counters-b
               :increments-counters-c]]
      (increment-count storage k (Duration/ofSeconds 10)))

    (is (= (get-count storage :increments-counters-a) 2))
    (is (= (get-count storage :increments-counters-b) 3))
    (is (= (get-count storage :increments-counters-c) 1))
    (is (= (get-count storage :increments-counters-d) 0))))

(deftest ^:unit test-local-storage-clears-counters
  (let [storage (local-storage)]
    (increment-count storage :clears-counters-a (Duration/ofSeconds 10))
    (increment-count storage :clears-counters-b (Duration/ofMinutes 10))

    (clear-counters storage)
    (is (= (get-count storage :clears-counters-a) 0))
    (is (= (get-count storage :clears-counters-b) 0))))

(deftest ^:unit test-local-storage-expires-counters
  (let [storage (local-storage)]
    (increment-count storage :expiring-counters-a (Duration/ofMillis 100))
    (increment-count storage :expiring-counters-b (Duration/ofMinutes 10))

    (Thread/sleep 110)

    (is (= (get-count storage :expiring-counters-a) 0))
    (is (= (get-count storage :expiring-counters-b) 1))))

(deftest ^:unit test-local-storage-returns-expiration-time
  (let [storage (local-storage)]
    (let [now* (Instant/now)]
      (with-redefs [now (constantly now*)]
        (increment-count storage :expiration-time-a (Duration/ofSeconds 10))
        (increment-count storage :expiration-time-b (Duration/ofMinutes 10))

        (is (= (counter-expiry storage :expiration-time-a)
               (.plus now* (Duration/ofSeconds 10))))
        (is (= (counter-expiry storage :expiration-time-b)
               (.plus now* (Duration/ofMinutes 10))))

        ;; An expired key is the same as a non-existent key
        (is (= (counter-expiry storage :expired-key) now*))))))

(deftest ^:unit test-local-storage-independent-atoms
  (testing "with separate atoms"
    (let [storage-a (local-storage)
          storage-b (local-storage)]
      (increment-count storage-a :independent-atoms (Duration/ofSeconds 10))
      (is (= (get-count storage-b :independent-atoms) 0))))

  (testing "with shared atom"
    (let [backing-atom (atom {})
          storage-a (local-storage backing-atom)
          storage-b (local-storage backing-atom)]
      (increment-count storage-a :shared-atom (Duration/ofSeconds 10))
      (is (= (get-count storage-b :shared-atom) 1)))))
