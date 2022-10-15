(ns io.staticweb.rate-limit.quota-state-test
  (:require [clojure.test :refer (deftest is)]
            [io.staticweb.rate-limit.middleware :as middleware]
            [io.staticweb.rate-limit.quota-state :as quota-state]
            [io.staticweb.rate-limit.storage :as storage])
  (:import [java.time Duration]))

(deftest ^:unit test-local-storage-increments-counters
  (let [limit (middleware/ip-rate-limit :limit-id 2 (Duration/ofSeconds 10))
        storage (storage/local-storage)
        req {:remote-addr "127.0.0.1"}
        qs (quota-state/read-quota-state storage limit req)]
    (is (= (quota-state/map->AvailableQuota
            {:key "io.staticweb.rate_limit.limits.IpRateLimit:limit-id-127.0.0.1"
             :ttl (Duration/ofSeconds 10)
             :quota 2
             :remaining 1})
           qs))
    (quota-state/increment-counter qs storage)
    (is (= (quota-state/map->AvailableQuota
            {:key "io.staticweb.rate_limit.limits.IpRateLimit:limit-id-127.0.0.1"
             :ttl (Duration/ofSeconds 10)
             :quota 2
             :remaining 0})
           (quota-state/read-quota-state storage limit req)))
    (quota-state/increment-counter qs storage)
    (let [{:keys [key retry-after quota]} (quota-state/read-quota-state storage limit req)]
      (is (= "io.staticweb.rate_limit.limits.IpRateLimit:limit-id-127.0.0.1" key))
      (is (= 2 quota))
      (is (<= 9900 (.toMillis retry-after) 10000)))))
