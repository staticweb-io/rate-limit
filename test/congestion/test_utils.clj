(ns congestion.test-utils
  (:require [clojure.test :refer :all]
            [congestion.limits :as l]))

(def default-response
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello, world!"})

(defn retry-after [rsp] (get-in rsp [:headers "Retry-After"]))

(defrecord MethodRateLimit [methods quota ttl]
  l/RateLimit
  (get-quota [self req]
    quota)
  (get-key [self req]
    (let [req-method (:request-method req)]
      (if (contains? methods req-method)
        (str ::method "-" (-> req :request-method name)))))
  (get-ttl [self req]
    ttl))
