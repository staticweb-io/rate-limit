(ns io.staticweb.rate-limit.limits-test
  (:require [clj-time.core :as t]
            [clojure.test :refer :all]
            [io.staticweb.rate-limit.limits :refer :all]))

(deftest ^:unit test-ip-rate-limit
  (testing "IpRateLimit"
    (let [limit (->IpRateLimit :test 100 (t/seconds 10))
          req {:remote-addr "127.0.0.1"}]
      (is (= (get-key limit req)
             "io.staticweb.rate-limit.limits.IpRateLimit:test-127.0.0.1"))
      (is (= (get-quota limit req) 100))
      (is (= (get-ttl limit req) (t/seconds 10))))))

(deftest ^:unit test-nil-rate-limit
  (testing "nil as a RateLimit"
    (let [limit nil
          req {}]
      (is (nil? (get-key limit req)))
      (is (thrown? AssertionError (get-quota limit req)))
      (is (thrown? AssertionError (get-ttl limit req))))))
