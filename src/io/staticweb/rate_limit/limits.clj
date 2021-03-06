(ns io.staticweb.rate-limit.limits)

(defprotocol RateLimit
  "A RateLimit has to define a key, a quota, and a time-to-live.

  The key is used to store the rate limit counter in storage. The
  returned key needs to be unique for each counter, eg an IP-based
  rate limit would include the IP-address of the client in the
  returned key so that each IP would have their own counter in
  storage.

  The quota represents the number of requests that can be made withint
  the provided ttl.

  The ttl (ie time-to-live) describes the timespan after which the
  rate limit is reset. Eg a rate limit with a ttl of 1s would be reset
  every second.

  Note: all three fns take the incoming request as an argument. This
  allows the return values to be generated dynamically from the
  request. Eg an IP-based rate limit can lookup the :remote-addr from
  the request to generate the key. The main utility of this is in
  procuducing the key from the request, but it is also possible to use
  the request to dynamically generate quotas or ttls."
  (get-key [self req])
  (get-quota [self req])
  (get-ttl [self req]))

(defrecord IpRateLimit [id quota ttl]
  RateLimit
  (get-key [self req]
    (str (.getName (type self)) id "-" (:remote-addr req)))

  (get-quota [self req]
    quota)

  (get-ttl [self req]
    ttl))

;; Allow `nil` to be used in place of a rate limit to represent an
;; unlimited rate limit.
(extend-type nil
  RateLimit
  (get-key [self req]
    nil)

  (get-quota [self req]
    (assert false))

  (get-ttl [self req]
    (assert false)))
