(ns io.staticweb.rate-limit.responses)

(set! *warn-on-reflection* true)

(def default-response
  "The default 429 response."
  {:status 429
   :headers {"Content-Type" "application/json"}
   :body "{\"error\":\"rate-limit-exceeded\"}"})

(defn rate-limit-applied?
  [rsp]
  (-> rsp
      ::rate-limit-applied
      some?))

(defn rate-limit-response
  [rsp quota-state]
  (assoc rsp ::rate-limit-applied quota-state))

(defn add-retry-after-header
  [rsp ^java.time.Duration retry-after]
  (assoc-in rsp
            [:headers "Retry-After"]
            (str (.getSeconds retry-after))))

(defn too-many-requests-response
  ([retry-after]
     (too-many-requests-response default-response retry-after))

  ([rsp retry-after]
     (let [rsp (add-retry-after-header rsp retry-after)]
       (merge {:status 429} rsp))))
