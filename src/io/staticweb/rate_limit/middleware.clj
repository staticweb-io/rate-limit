(ns io.staticweb.rate-limit.middleware
  (:require [io.staticweb.rate-limit.limits :as limits]
            [io.staticweb.rate-limit.quota-state :as quota-state]
            [io.staticweb.rate-limit.responses :as responses]))

(set! *warn-on-reflection* true)

(defn wrap-rate-limit
  "Apply a rate-limit before calling `handler`.

  Eg (wrap-rate-limit app {:storage ... :limit ... :response-builder ...})

  :storage is an instance of io.staticweb.rate-limit.storage/Storage and specifies
  the storage backend to use for storing rate-limit counter between
  requests (cf. io.staticweb.rate-limit.storage and io.staticweb.rate-limit.redis-storage).

  :limit is an instance of io.staticweb.rate-limit.limits/RateLimit and specifies
  the semantics of the limit that is being applied to `handler`.

  An optional :response-builder can be provided to override the
  default 429 response. The builder must be a (fn [quota retry-after]
  ...) function where `quota` is the number of requests allowed by the
  limit and `retry-after` is a clj-time/Joda DateTime specifying when
  the rate-limit will be reset. The too-many-requests-response fn can
  be used as a helper in forming a proper 429 response."
  [handler {:keys [storage limit response-builder]}]
  (fn [req]
    (let [quota-state (quota-state/read-quota-state storage limit req)]
      (if (quota-state/quota-exhausted? quota-state)
        (quota-state/build-error-response quota-state response-builder)
        (do
          (quota-state/increment-counter quota-state storage)
          (->> req
              handler
              (quota-state/rate-limit-response quota-state)))))))

(defn wrap-stacking-rate-limit
  "Apply a rate-limit before calling `handler`.

  This middleware is like wrap-rate-limit with the exception that
  multiple instances of this middleware can be stacked together to
  apply different rate-limis at different points in the ring request
  handling. Eg an IP-based limit, allowing 100req/h, can be applied
  before authentication, and a user-based limit, allowing 5000req/h,
  after authentication.

  The earlier middlewares in the ring middleware stack, eg the
  ip-based limit in the above example, will check whether a rate-limit
  has already been applied to the ring response and not increment the
  limit counters for those responses. In the above example the
  IP-based limit will check if the user-based limit has already been
  applied to the request.

  Note: the downside of incrementing the limit counter only after the
  request has been handled by `handler` is that there is a longer
  period during which concurrent requests would still be
  allowed. Whereas if the limit counter is incremented immediately,
  the period during which concurrent requests might pass through with
  the same counter value is smaller. So wrap-rate-limit should be
  preferred unless it's necessary to stack rate-limits."
  [handler {:keys [storage limit response-builder]}]
  (fn [req]
    (let [quota-state (quota-state/read-quota-state storage limit req)]
      (if (quota-state/quota-exhausted? quota-state)
        (quota-state/build-error-response quota-state response-builder)
        (let [rsp (handler req)]
          (if (responses/rate-limit-applied? rsp)
            rsp
            (do
              (quota-state/increment-counter quota-state storage)
              (quota-state/rate-limit-response quota-state rsp))))))))

;; Expose lib internals as delegates so that the user needs to import
;; only one namespace.

(defn ip-rate-limit
  "Instantiate an IP-based rate-limit where `quota` number of requests
  are allowed per IP-address within a timespan of `ttl`. The `id`
  argument is used to differentiate multiple instances of the same
  limit in the storage (eg two IP-based limits on two different routes
  that should have independent counters).

  Eg. (ip-rate-limit :my-limit 1000 (t/hours 1)) allows 1000 requests
  per hour per IP-address.

  Note: make sure that the incoming ring request has the
  correct :remote-addr field. Eg Heroku uses a reverse proxy infront
  of apps which means that by default the :remote-addr in a ring
  request is that off the reverse proxy. See:
  https://devcenter.heroku.com/articles/http-routing for more
  details."
  [id quota ttl]
  (limits/->IpRateLimit id quota ttl))

(defn too-many-requests-response
  "Generate a 429 (Too many requests) ring response.

  The response will include the X-RateLimit-Limit,
  X-RateLimit-Remaining and Retry-After headers and either a default
  response (cf. io.staticweb.rate-limit.responses/default-response) or the provided
  ring response.

  Note: if the provided response specifies a :status field, this will
  not be overwritten."
  ([retry-after]
     (responses/too-many-requests-response retry-after))
  ([rsp retry-after]
     (responses/too-many-requests-response rsp retry-after)))

(defn add-retry-after-header
  "Add a Retry-After header to the provided response. The
  `retry-after` argument is expected to be a clj-time/Joda DateTime."
  [rsp retry-after]
  (responses/add-retry-after-header rsp retry-after))
