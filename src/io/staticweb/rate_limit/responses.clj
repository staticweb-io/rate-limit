(ns io.staticweb.rate-limit.responses
  (:require [clj-time.format :as f]))

(set! *warn-on-reflection* true)

(def default-response
  "The default 429 response."
  {:headers {"Content-Type" "application/json"}
   :body "{\"error\": \"Too Many Requests\"}"})

(def ^:private time-format (f/formatter "EEE, dd MMM yyyy HH:mm:ss"))

(defn- time->str
  [time]
  ;; All HTTP timestamps MUST be in GMT and UTC == GMT in this case.
  (str (f/unparse time-format time) " GMT"))

(defn rate-limit-applied?
  [rsp]
  (-> rsp
      ::rate-limit-applied
      some?))

(defn rate-limit-response
  [rsp quota-state]
  (assoc rsp ::rate-limit-applied quota-state))

(defn add-retry-after-header
  [rsp retry-after]
  (assoc-in rsp
            [:headers "Retry-After"]
            (time->str retry-after)))

(defn too-many-requests-response
  ([retry-after]
     (too-many-requests-response default-response retry-after))

  ([rsp retry-after]
     (let [rsp (add-retry-after-header rsp retry-after)]
       (merge {:status 429} rsp))))
