(ns io.staticweb.rate-limit.responses-test
  (:use clojure.test)
  (:require [io.staticweb.rate-limit.responses :as r])
  (:import java.time.Duration))

(def response
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello, world!"})

(deftest ^:unit test-rate-limit-applied?
  (testing "rate-limit-applied?"
    (doseq [[rsp res] [[{} false]
                       [{::r/rate-limit-applied "limit-key"} true]
                       [{::r/rate-limit-applied nil} false]]]
      (testing (str "with " rsp)
        (is (= (r/rate-limit-applied? rsp) res))))))

(deftest ^:unit test-rate-limit-response
  (testing "rate-limit-response"
    (let [rsp (r/rate-limit-response response "limit-key")]
      (is (= (::r/rate-limit-applied rsp) "limit-key"))
      (is (= (:status rsp) 200))
      (is (= (:body rsp) "Hello, world!"))
      (is (= (get-in rsp [:headers "Content-Type"]) "text/plain")))))

(deftest ^:unit test-add-retry-after-header
  (testing "add-retry-after-header"
    (let [rsp (r/add-retry-after-header {} (Duration/ofMinutes 2))]
      (is (= (get-in rsp [:headers "Retry-After"]) "120")))))

(deftest ^:unit test-too-many-requests-response
  (testing "too-many-requests-response"
    (testing "with default response"
      (let [rsp (r/too-many-requests-response (Duration/ofSeconds 631))
            headers (:headers rsp)]
        (is (= (:status rsp) 429))
        (is (= (:body rsp) "{\"error\":\"rate-limit-exceeded\"}"))
        (is (= (headers "Content-Type") "application/json"))
        (is (= (headers "Retry-After") "631"))))

    (testing "with custom response"
      (let [custom-rsp {:headers {"Content-Type" "text/plain"}
                        :body "Hello, World!"
                        :status 418}
            rsp (r/too-many-requests-response custom-rsp (Duration/ofSeconds 5))
            headers (:headers rsp)]
        (is (= (:status rsp) 418))
        (is (= (:body rsp) "Hello, World!"))
        (is (= (headers "Content-Type") "text/plain"))
        (is (= (headers "Retry-After") "5"))))))
