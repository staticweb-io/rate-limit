# rate-limit

[![Clojars Project](https://img.shields.io/clojars/v/io.staticweb/rate-limit.svg)](https://clojars.org/io.staticweb/rate-limit)[![test](https://github.com/staticweb-io/rate-limit/actions/workflows/test.yml/badge.svg)](https://github.com/staticweb-io/rate-limit/actions/workflows/test.yml)

A ring middleware for applying rate limiting policies to HTTP requests.

The middleware is used to implement request rate limits on HTTP
endpoints. A key feature is the ability to stack rate limits: multiple
instances of the middleware can be wrapped around the same route, e.g.
before and after authentication.

The library provides only IP address -based limiting out of the box,
i.e. it's up to the library user to implement other types of rate limits
by implementing the `RateLimit` protocol. The obvious rate limit to
implement is a user-specific limit.

A storage implementation is used for storing rate limit counters. The
library provides storage implementations for an in-process atom and
Redis, but new storage implementations can be provided easily by
implementing the `Storage` protocol.


## Usage

The middleware is used by wrapping a ring request handler with either
`wrap-rate-limit` or `wrap-stacking-rate-limit`. For both functions
the first argument is the ring request handler to wrap, and the second
argument is the configuration for the rate limiting middleware. The
configuration is used to specify the storage backend, the rate limit
being applied by this instance of the middleware, and a response
builder used when the rate limit has been exhausted.


### wrap-rate-limit

Let's start with the simplest possible use case: limiting requests to
1 req/s per IP address, returning the default `429 - Too Many
Requests` response when the rate limit is exhausted.

```clj
(use 'compojure.core)
(require '[io.staticweb.rate-limit.middleware :refer [wrap-rate-limit]])
(require '[io.staticweb.rate-limit.storage :as storage])

;; Instantiate a storage backend
(def storage (storage/local-storage))

;; Define the rate limit: 1 req/s per IP address
(def limit (ip-rate-limit :limit-id 1 (java.time.Duration/ofSeconds 1)))

;; Define the middleware configuration
(def rate-limit-config {:storage storage :limit limit})

;; Wrap the /limit route in the rate limiting middleware
(def app (routes
          (GET "/no-limit" [] "no-limit")
          (wrap-rate-limit
           (GET "/limit" [] "limit")
           rate-limit-config)))
```

Note that the rate limit, `ip-rate-limit`, takes an identifier, a
number of requests and a time-to-live as arguments. The identifier is
used when referring to the limit counter in the storage backend and
therefore should be unique for each limit. The request count and TTL
together describe how many request can be made within a certain
time-span.

The `wrap-rate-limit` middleware checks and updates the rate limit
counter before calling the wrapped request handler. This means that an
earlier rate limit is applied before a later rate limit is
checked. For example, an 'unauthenticated' rate limit would be applied
before a 'user-specific' rate limit. This is often not what is wanted,
i.e. an authenticated user would usually have a greater rate limit
than unauthenticated users, but with `wrap-rate-limit` the
'unauthenticated' limit would get exhausted and further requests would
be denied. The `wrap-stacking-rate-limit` is provided to address this
issue.

The reason for using `wrap-rate-limit` rather than the more flexible
`wrap-stacking-rate-limit` is that since `wrap-rate-limit` increments
the counter before calling the request handler, there is less of a
chance of concurrent requests being allowed to execute when the rate
limit is already exhausted.


### wrap-stacking-rate-limit

An application's ring middleware stack can have multiple instances of
the `wrap-stacking-rate-limit` middleware and the rate limits will get
updated in reverse order. That is, each middleware checks if its rate
limit has been exhausted and, if so, denies the request. But if the
limit has not been exhausted the request is delegated to the wrapped
handler allowing subsequent rate limiting middlewares to be applied to
the request. When the wrapped handler returns a response, the rate
limiting middleware checks if a rate limit has been applied, and if
not so, the middleware will increment its own rate limit counter.

Performing the counter update after the request has been handled means
that a subsequent rate limiting middleware can be applied instead of
the current middleware. For example, in the below example code when
the request is authenticated we want the greater `user-limit` to be
applied rather than the lower `unauthenticated-limit`. Were the
`unauthenticated-limit` applied regardless of whether the request
authenticates or not would mean that an authenticated user could only
perform `100 req/h` rather than the intended `5000 req/h`.

```clj
(use 'compojure.core)
(require '[io.staticweb.rate-limit.middleware :refer [wrap-rate-limit]])
(require '[io.staticweb.rate-limit.storage :as storage])

;; A custom per-user rate limit
(defrecord UserRateLimit [id quota ttl]
  RateLimit
  (get-key [self req]
    (str (.getName (type self)) id "-" (:user-name req)))

  (get-quota [self req]
    quota)

  (get-ttl [self req]
    ttl))

;; Instantiate a storage backend
(def storage (storage/local-storage))

;; Define a limit and config for unauthenticated requests: 100 req/h
;; per IP address
(def unauthenticated-limit (ip-rate-limit :unauthenticated-limit 100 (java.time.Duration/ofHours 1)))
(def unauthenticated-config {:storage storage :limit unauthenticated-limit})

;; Define a limit and config for authenticated requests: 5000 req/h
;; per user
(def user-limit (->UserRateLimit :user-limit 5000 (java.time.Duration/ofHours 1)))
(def user-config {:storage storage :limit user-limit})

(defn wrap-authentication
  [handler]
  (fn [req]
    ;; TODO: magically authenticate users and attach user name to request
    (let [user-name "Bob"
          req (assoc req :user-name user-name)]
      (handler req))))

(def app (routes
          (GET "/no-limit" [] "no-limit")
          (->
           (ANY "/limit" [] "limit")
           (wrap-stacking-rate-limit user-config)
           (wrap-authentication)
           (wrap-stacking-rate-limit unauthenticated-config))))
```

Note: we implement a `UserRateLimit` to be able to perform rate
limiting based on the `:user-name` field in the request. The key
points are: 1) attaching some data, `:user-name` in this case, to the
request, and 2) looking that data up in the rate limit
implementation. The `get-key` function returns a key that is used to
identify the rate limit counter. For a user-specific rate limit that
key should be unique to each user. For an IP address -specific rate
limit the key should be unique to each IP address.


### Custom response builders

When the rate limit is exhausted, the middleware needs to produce a
ring response to this effect. The library provides a default response
builder, which returns a JSON `429` response:

```clj
{:body "{\"error\":\"rate-limit-exceeded\"}"
 :headers {"Content-Type" "application/json"
           "Retry-After" "30"}
 :status 429}
```

Most likely the default response is not suitable for your
application. For example, you want to specify a custom `Content-Type`,
or the response body isn't in the correct format.

The middleware configuration accepts a custom response builder as the
`:response-builder` key:

```clj
(defn custom-response-builder
  [quota retry-after]
  ...)

(wrap-rate-limit app {... :response-builder custom-response-builder})
```

The response builder takes two arguments: `quota` and `retry-after`,
where `quota` is the number of requests allowed by the limit that has
been exhausted and `retry-after` is the number of seconds until the rate
limit counter will be reset.

The simplest way to build an appropriate `429 - Too Many Requests`
response is to call the `too-many-requests-response` function with a
custom ring response map. The `too-many-requests-response` function
will add a `Retry-After` header to the response and set `:status` to
`429` unless it was already set.

But if you want to, you can return whatever response you desire from
your custom response builder.


### Debugging rate limits

When a rate limit is applied to a request, the library `assoc`s the
quota state to the ring response with the key
`:io.staticweb.rate-limit.responses/rate-limit-applied`. The quota state is either
`AvailableQuota` or `ExhaustedQuota` as defined in
`io.staticweb.rate-limit.quota-state`.

This serves two purposes: 1) it allows stacked rate limiting
middleware to work out if a rate limit has already been applied, and
2) it is helpful in tests and in debugging since we can inspect what
rate limit was applied, what the total quota is and how many requests
are remaining until the rate limit resets.

But the data in the response is available to any other ring middleware
so you can also use it to add custom HTTP headers to responses
reporting the total and available requests, if you want to!


### Custom rate limits

The library provides only an IP address rate limit. Other rate limits
have to be implemented by the library user. This might change in the
future, but at the moment it seemed like there was little benefit in
trying to guess what authentication system people use
etc. Contributions are welcome!

The `RateLimit` protocol describes the interface for a rate limit:

```clj
(defprotocol RateLimit
  (get-key [self req])
  (get-quota [self req])
  (get-ttl [self req]))
```

The `IpRateLimit` is an example of a simple static limit:

```clj
(defrecord IpRateLimit [id quota ttl]
  RateLimit
  (get-key [self req]
    (str (.getName (type self)) id "-" (:remote-addr req)))

  (get-quota [self req]
    quota)

  (get-ttl [self req]
    ttl))
```

The key part is returning a key from `get-key` that is unique within
the context defined by the limit. E.g. for `IpRateLimit` we want each
unique request IP address to have its own rate limit counter. The
return value is used to identify the rate limit counter in the storage
backend.

For a static limit, like `IpRateLimit` above, the `get-quota` and
`get-ttl` functions both just return the value passed to the limit
during construction time.

A dynamic limit could return different quota and TTL value depending
on the request. E.g. we could define a custom rate limit for each user
of our application, where both the quota and the TTL would be looked
up from the user database after the user has been authenticated.

The easiest way to do this with `rate-limit` would be to attach the
rate limit information to the request, e.g. in the authentication
middleware, and then simply look them up from the request in
`get-quota` and `get-ttl`:

```clj
(defrecord UserRateLimit [id quota ttl]
  RateLimit
  (get-key [self req]
    (str (.getName (type self)) id "-" (:user-name req)))

  (get-quota [self req]
    (:user-rate-limit-quota req))

  (get-ttl [self req]
    (:user-rate-limit-ttl req))
```


### Custom storage implementations

The library comes with two storage implementations: `local-storage`
and `redis-storage`, but it should be easy to write your own storage
implementation by implementing the `Storage` protocol by taking
inspiration from the provided storage implementations.

The `Storage` protocol looks like this:

```clj
(defprotocol Storage
  (get-count [self key])
  (increment-count [self key ttl])
  (counter-expiry [self key])
  (clear-counters [self]))
```

The `clear-counters` function is used to clear all counter state from
the storage which allows the application operator to reset all rate
limits. This is mainly a convenience for the operator in case
something goes wrong with the rate limiting implementation and HTTP
API users are unable to make requests.

The other three function, `get-count`, `increment-count` and
`counter-expiry`, are used to observe and increment the
counters. We'll describe the contracts of all three functions here to
make `Storage` implementation easier.

`get-count` is provided with a counter key, generated by calling
`get-key` on the `RateLimit` instance, and it is expected to return
the current value of the counter, or `0` if the counter does not
exist.

`increment-count` is provided again with a counter key, and a
time-to-live, which is a java.time.Duration (e.g.
`(java.time.Duration/ofHours 1)`). The `ttl` argument is used to
schedule the deletion of the counter after the counter expires, so
it's only really significant if the counter does not exist already.

The `LocalStorage` storage implementation keeps track of when counters
should expire and purges expired counters when `get-count` is
called. The `RedisStorage` storage implementation instead uses a
feature of Redis, the `EXPIRE` command, to specify when Redis should
delete the counter automatically.

`counter-expiry` is called in order to get the time-stamp that is used
to generate the `Retry-After` header for the `429 - Too Many Requests`
response.

Note: the `RedisStorage` storage implementation prefixes all limit
keys with the string `io.staticweb.rate-limit-`. The idea is to underline that
those Redis keys belong to `rate-limit` in cases where the same
Redis instance is used to store other application data as well. Having
a common prefix makes the implementation of the `clear-counters`
function simple as well.


### Resetting rate limits

The `Storage` protocol provides a `clear-counters` function for
clearing all rate limit counters from storage. This can be used both
in tests to clear state before and after tests, and by operators to
clear all limits if something goes wrong with rate limiting.

Note: since `LocalStorage` by default creates a new atom to store
state, it is not possible to clear state by simply creating a new
`LocalStorage` instance and calling `clear-counters` on it. Instead
the application must create the atom and hold on to it in order to be
able to clear it later on.


### Caveats

There are a few caveats in rate limiting requests.


#### IP-based rate limiting

IP-based rate limiting is usually based on the remote address of the
HTTP request. Unfortunately the remote address is not actually the
client's IP address in many cases. For example, any CDN, load
balancer, or proxy will mess with the remote address of the
request. So you have to be extra careful when applying rate limits on
the remote address.

Well behaving proxies usually set or adjust the `X-Forwarded-For`
header when they forward the request so it's possible in many cases to
pull out the client's real IP address from that header. But if the
number of forwarding proxies is not known or it varies, it's difficult
to implement IP-based rate limiting in such a way that a malicious
client cannot circumvent it by setting the `X-Forwarded-For` header
themselves. For example, if the application can be accessed both
directly or via a caching proxy, a malicious client could just set the
`X-Forwarded-For` header to some random IP address and access the
application directly.


#### LocalStorage

This is probably obvious, but we'll mention it just the same: the
`LocalStorage` storage implementation is super simple and fast to use
in an application for counter storage, but obviously it is only
visible within that instance of the application. If you're running
multiple instances of your application behind a load balancer, each
application will have its own counters. That might be acceptable in
some cases, but usually you'll want to use a database for counter
storage so that counters are shared across all instances of the
application.


#### Caching and rate limits

Another side effect of caching responses is that any remaining rate
limit headers might not be valid when the response is served from a
cache. Therefore `rate-limit` doesn't set any HTTP headers
reporting the total or remaining quota. Fortunately you can do it
yourself with a simple ring middleware!


## License

Copyright © 2014 Listora

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
