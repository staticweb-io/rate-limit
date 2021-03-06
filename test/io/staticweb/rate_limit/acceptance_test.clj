(ns io.staticweb.rate-limit.acceptance-test
  (:use clojure.test
        compojure.core
        io.staticweb.rate-limit.middleware
        io.staticweb.rate-limit.test-utils)
  (:require [io.staticweb.rate-limit.redis :as redis]
            [io.staticweb.rate-limit.storage :as s]
            [ring.mock.request :as mock])
  (:import [java.time Duration Instant]))

(def default-response-handler
  (constantly default-response))

(def storage-factory-fns
  "A collection of functions used to instantiate the various storage
  backends."
  [s/local-storage
   #(redis/redis-storage {:spec {:host "localhost" :port 6379}})])

(defn create-storage
  [factory-fn]
  (doto (factory-fn)
    s/clear-counters))

(deftest ^:redis test-unstacked-rate-limit
  (testing "wrap-rate-limit"
    (doseq [storage-factory-fn storage-factory-fns]
      (let [storage (create-storage storage-factory-fn)
            limit (ip-rate-limit :test 1 (Duration/ofSeconds 1))
            response-builder (fn [& args]
                               (apply too-many-requests-response
                                      {:headers
                                       {"Content-Type" "text/plain"}
                                       :body "custom-error"}
                                      args))
            rate-limit-config {:storage storage
                               :limit limit
                               :response-builder response-builder}
            app (routes
                 (GET "/no-limit" [] "no-limit")
                 (wrap-rate-limit
                  (GET "/limit" [] "limit")
                  rate-limit-config))]

        (testing "unlimited route"
          (let [rsp (app (mock/request :get "/no-limit"))]
            (is (= (:status rsp) 200))
            (is (nil? (retry-after rsp)))
            (is (= (:body rsp) "no-limit"))
            (is (nil? (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)))))

        (testing "rate-limited route"
          (let [rsp (app (mock/request :get "/limit"))]
            (is (= (:status rsp) 200))
            (is (nil? (retry-after rsp)))
            (is (= (:body rsp) "limit"))
            (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                   {:key "io.staticweb.rate-limit.limits.IpRateLimit:test-localhost"
                    :quota 1
                    :remaining 0})))

          (testing "exhausted quota"
            (let [rsp (app (mock/request :get "/limit"))]
              (is (= (:status rsp) 429))
              (is (some? (retry-after rsp)))
              (is (= (:body rsp) "custom-error"))
              (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                     {:key "io.staticweb.rate-limit.limits.IpRateLimit:test-localhost"
                      :quota 1
                      :remaining 0}))))

          (testing "reset quota"
            (Thread/sleep 1000)
            (let [rsp (app (mock/request :get "/limit"))]
              (is (= (:status rsp) 200))
              (is (nil? (retry-after rsp)))
              (is (= (:body rsp) "limit"))
              (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                     {:key "io.staticweb.rate-limit.limits.IpRateLimit:test-localhost"
                      :quota 1
                      :remaining 0})))))))))

(deftest ^:redis test-single-stacked-rate-limit
  (testing "single wrap-stacking-rate-limit instance"
    (doseq [storage-factory-fn storage-factory-fns]
      (let [storage (create-storage storage-factory-fn)
            limit (ip-rate-limit :test 1 (Duration/ofSeconds 1))
            response-builder (partial too-many-requests-response
                                      {:headers
                                       {"Content-Type" "text/plain"}
                                       :body "custom-error"})
            rate-limit-config {:storage storage
                               :limit limit
                               :response-builder response-builder}
            app (routes
                 (GET "/no-limit" [] "no-limit")
                 (wrap-stacking-rate-limit
                  (GET "/limit" [] "limit")
                  rate-limit-config))]

        (testing "unlimited route"
          (let [rsp (app (mock/request :get "/no-limit"))]
            (is (= (:status rsp) 200))
            (is (nil? (retry-after rsp)))
            (is (= (:body rsp) "no-limit"))
            (is (nil? (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)))))

        (testing "rate-limited route"
          (let [rsp (app (mock/request :get "/limit"))]
            (is (= (:status rsp) 200))
            (is (nil? (retry-after rsp)))
            (is (= (:body rsp) "limit"))
            (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                   {:key "io.staticweb.rate-limit.limits.IpRateLimit:test-localhost"
                    :quota 1
                    :remaining 0})))

          (testing "exhausted quota"
            (let [rsp (app (mock/request :get "/limit"))]
              (is (= (:status rsp) 429))
              (is (some? (retry-after rsp)))
              (is (= (:body rsp) "custom-error"))
              (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                     {:key "io.staticweb.rate-limit.limits.IpRateLimit:test-localhost"
                      :quota 1
                      :remaining 0}))))

          (testing "reset quota"
            (Thread/sleep 1000)
            (let [rsp (app (mock/request :get "/limit"))]
              (is (= (:status rsp) 200))
              (is (nil? (retry-after rsp)))
              (is (= (:body rsp) "limit"))
              (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                     {:key "io.staticweb.rate-limit.limits.IpRateLimit:test-localhost"
                      :quota 1
                      :remaining 0})))))))))

(defn wrap-allowed-methods
  "A trivial middleware that returns 405 for those HTTP method that
  are no explicitly allowed. This middleware is used to demonstrate
  how one might want to stack rate limiting middleware around other
  middleware in order to apply different rate limits, eg before and
  after authenticate."
  [handler methods]
  (fn [req]
    (let [method (-> req :request-method)]
      (if (contains? methods method)
        (handler req)
        {:status 405
         :headers {"Content-Type" "text/plain"}
         :body "Method not allowed"}))))

(deftest ^:redis test-multiple-stacked-rate-limits
  (testing "multiple wrap-stacking-rate-limit instance"
    (doseq [storage-factory-fn storage-factory-fns]
      (let [storage (create-storage storage-factory-fn)
            first-limit (ip-rate-limit :test 1 (Duration/ofSeconds 1))
            first-config {:storage storage
                          :limit first-limit}
            second-limit (->MethodRateLimit #{:get} 1 (Duration/ofSeconds 1))
            second-response-builder (partial too-many-requests-response
                                             {:headers
                                              {"Content-Type" "text/plain"}
                                              :body "custom-error"})
            second-config {:storage storage
                           :limit second-limit
                           :response-builder second-response-builder}
            wrap-middleware #(-> %
                                 (wrap-stacking-rate-limit second-config)
                                 (wrap-allowed-methods #{:get :post})
                                 (wrap-stacking-rate-limit first-config))

            app (routes
                 (GET "/no-limit" [] "no-limit")
                 (wrap-middleware
                  (ANY "/limit" [] "limit")))]

        (testing "unlimited route"
          (dotimes [_ 3] ;; go over both limits to show we're not limited
            (let [rsp (app (mock/request :get "/no-limit"))]
              (is (= (:status rsp) 200))
              (is (nil? (retry-after rsp)))
              (is (= (:body rsp) "no-limit"))
              (is (nil? (:io.staticweb.rate-limit.responses/rate-limit-applied rsp))))))

        (testing "route limited by second limit (HTTP method)"
          (testing "applies second limit (HTTP method)"
            (let [rsp (app (mock/request :get "/limit"))]
              (is (= (:status rsp) 200))
              (is (nil? (retry-after rsp)))
              (is (= (:body rsp) "limit"))
              (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                     {:key ":io.staticweb.rate-limit.test-utils/method-get"
                      :quota 1
                      :remaining 0}))))

          (testing "exhausted quota"
            (let [rsp (app (mock/request :get "/limit"))]
              (is (= (:status rsp) 429))
              (is (some? (retry-after rsp)))
              (is (= (:body rsp) "custom-error"))
              (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                     {:key ":io.staticweb.rate-limit.test-utils/method-get"
                      :quota 1
                      :remaining 0}))))

          (testing "reset quota"
            (Thread/sleep 1100)
            (let [rsp (app (mock/request :get "/limit"))]
              (is (= (:status rsp) 200))
              (is (nil? (retry-after rsp)))
              (is (= (:body rsp) "limit"))
              (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                     {:key ":io.staticweb.rate-limit.test-utils/method-get"
                      :quota 1
                      :remaining 0})))))

        (testing "route limited by first limit (IP)"
          (testing "available quota"
            (let [rsp (app (mock/request :post "/limit"))]
              (is (= (:status rsp) 200))
              (is (nil? (retry-after rsp)))
              (is (= (:body rsp) "limit"))
              (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                     {:key "io.staticweb.rate-limit.limits.IpRateLimit:test-localhost"
                      :quota 1
                      :remaining 0}))))

          (testing "exhausted quota"
            (let [rsp (app (mock/request :post "/limit"))]
              (is (= (:status rsp) 429))
              (is (some? (retry-after rsp)))
              (is (= (:body rsp) "{\"error\": \"Too Many Requests\"}"))
              (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                     {:key "io.staticweb.rate-limit.limits.IpRateLimit:test-localhost"
                      :quota 1
                      :remaining 0}))))

          (testing "reset quota"
            (Thread/sleep 1000)
            (let [rsp (app (mock/request :post "/limit"))]
              (is (= (:status rsp) 200))
              (is (nil? (retry-after rsp)))
              (is (= (:body rsp) "limit"))
              (is (= (:io.staticweb.rate-limit.responses/rate-limit-applied rsp)
                     {:key "io.staticweb.rate-limit.limits.IpRateLimit:test-localhost"
                      :quota 1
                      :remaining 0})))))))))
