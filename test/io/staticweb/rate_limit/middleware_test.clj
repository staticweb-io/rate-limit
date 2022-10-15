(ns io.staticweb.rate-limit.middleware-test
  (:use clojure.test
        io.staticweb.rate-limit.middleware
        io.staticweb.rate-limit.test-utils)
  (:require [io.staticweb.rate-limit.limits :as l]
            [io.staticweb.rate-limit.responses :as r]
            [io.staticweb.rate-limit.storage :as s])
  (:import (java.time Duration Instant)))

(defrecord MockRateLimit [quota key ttl]
  l/RateLimit
  (get-quota [self req]
    quota)
  (get-key [self req]
    key)
  (get-ttl [self req]
    ttl))

(def ^:dynamic *storage* nil)

(defn set-counter
  [key new-value new-timeout]
  (swap! (:state *storage*)
         #(-> (assoc-in % [:counters key] new-value)
              (assoc-in [:timeouts key] new-timeout))))

(defmacro with-counters
  [counters & body]
  `(binding [*storage* (s/local-storage)]
     (doseq [[k# v# e#] ~counters]
       (set-counter k# v# e#))
     ~@body))

(defn make-request
  "Wrap `default-response` in the provided a middleware and make a
  `:mock-request`."
  [middleware limit & [response-builder]]
  (let [handler (-> default-response
                    constantly
                    (middleware {:storage *storage*
                                 :limit limit
                                 :response-builder response-builder}))]
    (handler :mock-request)))

(deftest ^:unit test-single-wrap-stacking-rate-limit-instance
  (testing "with unexhausted quota"
    (with-counters []
      (let [limit (->MockRateLimit 10 :mock-limit-key (Duration/ofSeconds 7))
            rsp (make-request wrap-stacking-rate-limit limit)
            start (Instant/now)]
        (is (= (:status rsp) 200))
        (is (= (:body rsp )"Hello, world!"))
        (is (= (get-in rsp [:headers "Content-Type"]) "text/plain"))
        (is (= (::r/rate-limit-applied rsp) {:key :mock-limit-key
                                             :quota 10
                                             :remaining 9}))
        (is (= (s/get-count *storage* :mock-limit-key) 1))
        (is (<= 6900
                (->> (s/counter-expiry *storage* :mock-limit-key)
                     (Duration/between start)
                     .toMillis)
                7000)))))

  (testing "with exhausted limit"
    (with-counters [[:mock-limit-key 10 (.plus (Instant/now) (Duration/ofMinutes 5))]]
      (let [limit (->MockRateLimit 10 :mock-limit-key (Duration/ofSeconds 7))
            rsp (make-request wrap-stacking-rate-limit limit)]
        (is (= (:status rsp) 429))
        (is (= (::r/rate-limit-applied rsp) {:key :mock-limit-key
                                             :quota 10
                                             :remaining 0}))
        (is (<= 299 (Long/parseLong (retry-after rsp)) 300)))))

  (testing "with custom 429 reponse"
    (with-counters [[:mock-limit-key 10 (.plus (Instant/now) (Duration/ofSeconds 42))]]
      (let [limit (->MockRateLimit 10 :mock-limit-key (Duration/ofSeconds 7))
            custom-response-handler (fn [key retry-after]
                                      {:status 418
                                       :headers {"Content-Type" "text/plain"}
                                       :body "I'm a teapot"})
            rsp (make-request wrap-stacking-rate-limit
                              limit custom-response-handler)]
        (is (= (:status rsp) 418))
        (is (= (get-in rsp [:headers "Content-Type"]) "text/plain"))
        (is (= (:body rsp) "I'm a teapot"))
        (is (= (::r/rate-limit-applied rsp) {:key :mock-limit-key
                                             :quota 10
                                             :remaining 0}))))))

(deftest ^:unit test-multiple-wrap-stacking-rate-limit-instances
  (testing "with second limit applied"
    (with-counters []
      (let [first-limit (->MockRateLimit 1000 :first-limit-key (Duration/ofSeconds 17))
            second-limit (->MockRateLimit 10 :second-limit-key (Duration/ofSeconds 19))
            handler (-> default-response
                        constantly
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit first-limit})
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit second-limit}))
            start (Instant/now)
            rsp (handler :mock-req)]
        (is (= (:status rsp) 200))
        (is (= (::r/rate-limit-applied rsp) {:key :first-limit-key
                                             :quota 1000
                                             :remaining 999}))
        (is (= (s/get-count *storage* :first-limit-key) 1))
        (is (<= 16900
                (->> (s/counter-expiry *storage* :first-limit-key)
                     (Duration/between start)
                     .toMillis)
                17000)))))

  (testing "with exhausted first rate limit"
    (with-counters [[:first-limit-key 1000 (.plus (Instant/now) (Duration/ofMillis 15000))]]
      (let [first-limit (->MockRateLimit 1000 :first-limit-key (Duration/ofSeconds 17))
            second-limit (->MockRateLimit 10 :second-limit-key (Duration/ofSeconds 19))
            handler (-> default-response
                        constantly
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit second-limit})
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit first-limit}))]
        (let [rsp (handler :mock-req)]
          (is (= (:status rsp) 429))
          (is (= (::r/rate-limit-applied rsp) {:key :first-limit-key
                                               :quota 1000
                                               :remaining 0}))
          (is (<= 14 (Long/parseLong (retry-after rsp)) 15))
          (is (= (s/get-count *storage* :first-limit-key) 1000))
          (is (= (s/get-count *storage* :second-limit-key) 0))))))

  (testing "with exhausted second rate limit"
    (with-counters [[:second-limit-key 10 (.plus (Instant/now) (Duration/ofHours 3))]]
      (let [first-limit (->MockRateLimit 1000 :first-limit-key (Duration/ofSeconds 17))
            second-limit (->MockRateLimit 10 :second-limit-key (Duration/ofSeconds 19))
            handler (-> default-response
                        constantly
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit second-limit})
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit first-limit}))]
        (let [rsp (handler :mock-req)]
          (is (= (:status rsp) 429))
          (is (= (::r/rate-limit-applied rsp) {:key :second-limit-key
                                               :quota 10
                                               :remaining 0}))
          (is (<= 10799 (Long/parseLong (retry-after rsp)) 10800))
          (is (= (s/get-count *storage* :first-limit-key) 0))
          (is (= (s/get-count *storage* :second-limit-key) 10)))))))
