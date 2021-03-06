(ns io.staticweb.rate-limit.limits-test
  (:use clojure.test
        io.staticweb.rate-limit.limits)
  (:import java.time.Duration))

(deftest ^:unit test-ip-rate-limit
  (testing "IpRateLimit"
    (let [limit (->IpRateLimit :test 100 (Duration/ofSeconds 10))
          req {:remote-addr "127.0.0.1"}]
      (is (= (get-key limit req)
             "io.staticweb.rate_limit.limits.IpRateLimit:test-127.0.0.1"))
      (is (= (get-quota limit req) 100))
      (is (= (get-ttl limit req) (Duration/ofSeconds 10))))))

(deftest ^:unit test-nil-rate-limit
  (testing "nil as a RateLimit"
    (let [limit nil
          req {}]
      (is (nil? (get-key limit req)))
      (is (thrown? AssertionError (get-quota limit req)))
      (is (thrown? AssertionError (get-ttl limit req))))))
