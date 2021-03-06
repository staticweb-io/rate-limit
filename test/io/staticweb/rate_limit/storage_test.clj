(ns io.staticweb.rate-limit.storage-test
  (:require [clj-time.core :as t]
            [clojure.test :refer :all]
            [io.staticweb.rate-limit.storage :refer :all]))

;; LocalStorage tests
(deftest ^:unit test-local-storage-increments-counters
  (let [storage (local-storage)]
    (doseq [k [:increments-counters-b
               :increments-counters-a
               :increments-counters-a
               :increments-counters-b
               :increments-counters-b
               :increments-counters-c]]
      (increment-count storage k (t/seconds 10)))

    (is (= (get-count storage :increments-counters-a) 2))
    (is (= (get-count storage :increments-counters-b) 3))
    (is (= (get-count storage :increments-counters-c) 1))
    (is (= (get-count storage :increments-counters-d) 0))))

(deftest ^:unit test-local-storage-clears-counters
  (let [storage (local-storage)]
    (increment-count storage :clears-counters-a (t/seconds 10))
    (increment-count storage :clears-counters-b (t/minutes 10))

    (clear-counters storage)
    (is (= (get-count storage :clears-counters-a) 0))
    (is (= (get-count storage :clears-counters-b) 0))))

(deftest ^:unit test-local-storage-expires-counters
  (let [storage (local-storage)]
    (increment-count storage :expiring-counters-a (t/millis 100))
    (increment-count storage :expiring-counters-b (t/minutes 10))

    (Thread/sleep 110)

    (is (= (get-count storage :expiring-counters-a) 0))
    (is (= (get-count storage :expiring-counters-b) 1))))

(deftest ^:unit test-local-storage-returns-expiration-time
  (let [storage (local-storage)]
    (let [now (t/now)]
      (with-redefs [t/now (fn [] now)]
        (increment-count storage :expiration-time-a (t/seconds 10))
        (increment-count storage :expiration-time-b (t/minutes 10))

        (is (= (counter-expiry storage :expiration-time-a)
               (t/plus now (t/seconds 10))))
        (is (= (counter-expiry storage :expiration-time-b)
               (t/plus now (t/minutes 10))))

        ;; An expired key is the same as a non-existent key
        (is (= (counter-expiry storage :expired-key) now))))))

(deftest ^:unit test-local-storage-independent-atoms
  (testing "with separate atoms"
    (let [storage-a (local-storage)
          storage-b (local-storage)]
      (increment-count storage-a :independent-atoms (t/seconds 10))
      (is (= (get-count storage-b :independent-atoms) 0))))

  (testing "with shared atom"
    (let [backing-atom (atom {})
          storage-a (local-storage backing-atom)
          storage-b (local-storage backing-atom)]
      (increment-count storage-a :independent-atoms (t/seconds 10))
      (is (= (get-count storage-b :independent-atoms) 1)))))
