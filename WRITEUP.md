# Write-up

## The atomic decision

A seat is sold by a **guarded conditional UPDATE**: `UPDATE seats SET status='confirmed', ... WHERE show_id=? AND label = ANY(?) AND status='available'`, and the transaction rolls back unless it affected exactly as many rows as were requested. There is no read-then-write window: the test ("is it free?") and the set ("take it") are one statement, evaluated by Postgres under a row lock, so two buyers cannot both observe `available` and both win.

Before that statement the request takes `SELECT ... FROM seats WHERE label = ANY(?) ORDER BY label FOR UPDATE`. That makes the decision on locked rows (losers get a clean `seat-taken` 409 without doing any write), and the guarded UPDATE is the belt-and-braces final check.

**Multi-seat deadlock avoidance.** Every request locks its seats in the same global order (`ORDER BY label`). Two requests wanting `{A1,A2}` and `{A2,A1}` both lock A1 first, so there is no cycle. Cancel locks in the same order. The test `overlappingMultiSeat_noDeadlock_noDoubleHold` fires overlapping pairs in both orders. Multi-seat is **all-or-nothing**: any unavailable seat declines the whole request, which keeps the behaviour identical under concurrency.

**Per-user limit.** The count (`confirmed` seats for this user in this show) is only race-free if concurrent requests from the same user are serialized, otherwise ten parallel requests all read "0 seats" and all pass. A transaction-scoped advisory lock on `(show, user)` serializes them. Different users never contend on it.

## Idempotency

The key is stored on the reservation row with `UNIQUE(user_id, idempotency_key)` and a signature of the request (the sorted seat list). Exactly-once is enforced in two layers: a transaction-scoped advisory lock on `(user, key)` taken at the start (so a concurrent retry waits, then reads the committed row and replays it), and the unique constraint as a backstop. Same key and same seats returns the original reservation with **200** (not 201, so "exactly one 201 per seat" stays true under retries); same key with a different seat set or show is **409 idempotency-conflict**. The key is scoped to the user, so one user cannot collide with another's keys. Declined attempts store nothing (the transaction rolls back), so a declined request can be retried with the same key. A request that sends **no key at all** is treated as a unique request (the server makes up a key): it has no retry protection, since there is nothing to match a retry against, but seat uniqueness and the per-user limit still hold, so correctness never depends on the client sending a key. A blank or oversized key is a 400. See ADR 0002.

## Holds and expiry

The email offers two release models, an explicit cancel or a time-boxed hold, and the service has **both**. Its example also shows reserve returning `confirmed`, and a seat that can expire cannot be sold yet. So holds are **opt-in per show**: a show created with `hold_seconds` makes reserve place a hold (`status: "held"`, `expires_at`) that the owner confirms with `POST /reservations/{id}/confirm`; a show without it behaves exactly as the example describes. Owner-only cancel works on both. ADR 0005 records the choice.

**Expiry is correct without any background job.** A lapsed hold is free by the SQL itself: the seat-locking query treats `held AND expires_at < clock_timestamp()` as available, the guarded claim `UPDATE` can take it over, the per-user limit counts only holds that have not lapsed, and `GET /shows/{id}` reports it as available. Confirm promotes only seats that are still `held AND expires_at > clock_timestamp()`, so a lapsed hold is refused with `409 hold-expired` and never resurrected, even if nobody else has taken the seat yet. All timestamps come from the database clock, so app and DB host skew cannot matter. A sweeper (every 10s) only frees the rows and marks the reservation `expired`, using `FOR UPDATE SKIP LOCKED` so it never waits on a live request. This is tested with the sweeper effectively switched off (`HoldWithoutSweeperIT`), and I broke each of the three rules on purpose to confirm the right test fails. Cancel is owner-only, idempotent, and its UPDATE is guarded by `reservation_id`, so a stale cancel can never free a seat that has since been taken by someone else.

Trade-off: while a hold is live it counts against the per-user limit and blocks other buyers, which is the point of a hold but also lets a user park seats for `hold_seconds`. The limit bounds that.

## Authentication for reviewers

The email says `POST /shows` is admin-only and reserve needs an authenticated user, but not how a checker obtains either token. Identity is a bearer JWT verified by Spring Security (HS256, stateless); there is no users table, so a caller is whatever the token's subject says. `POST /auth/token` mints tokens, and an `admin_key` in that request grants the admin role. I documented one **demo** admin key in the README rather than withholding it, because a harness that cannot create a show fails at step one. The trade-off is deliberate: that key only allows creating shows, and tokens still cannot be forged without the private JWT secret. The same dev endpoint would let a caller mint a token for any user id, so it is a stand-in for a real identity provider, not a substitute.

## Consistency versus availability under a partition

This is a CP system by construction: one Postgres primary is the only source of truth, and nothing is cached or answered from a replica. If the app cannot reach the database it refuses rather than guess: readiness returns 503 within 2 s (so the orchestrator stops routing), and requests that cannot get a connection or lock inside the bounded waits get **429**, not a wrong answer. The cost is availability: during a DB partition no seats can be sold. For a system whose one job is "never sell a seat twice", that is the right trade. Honest caveat: a DB outage surfaces as 429 ("retry, same key"), not 503, because the spec wants declines to be 4xx; a real deployment would probably want 503 + `Retry-After` for a true outage.

## Observability: what I would be paged for at 2 am

- **Any 5xx** (rate > 0 for 5 min). In this design a 5xx is a bug, never a normal outcome.
- **Readiness failing** for more than a minute (the DB is unreachable or hung).
- **`reservations_declined_total{reason="overloaded"}` rising**: we are shedding load; capacity, not correctness. Pair with Hikari pending-connections and acquire time.
- **Metric/state drift**: `reservations_confirmed_total` vs confirmed seats from the DB (the burst script checks this; a periodic reconciliation job would page on it).
- **`seat-taken` ratio collapsing to zero during an on-sale** (would mean requests are not reaching the service) or a latency p99 spike on `/reserve`.

Not paging: `seat-taken`, `per-user-limit`, `idempotent-replay`. Those are the system working.

## Results

Local (laptop, app on the host, Postgres in Docker), default `./burst.sh`: 20,000 requests in 35 s (~570 req/s): 3,691 confirmed, 649 idempotent replays, 13,500 `seat-taken`, 2,160 `per-user-limit`, **0 5xx, 0 transport errors, 0 seats sold twice**, invariant held at all 17 samples taken during the burst, and every metric delta matched the responses observed. The `./mvnw verify` suite (23 tests: 3 unit, 20 integration) passes: 300 buyers on one seat produce exactly one 201; a user firing 12 parallel reserves with a limit of 4 ends with exactly 4; 40 parallel same-key requests create one reservation; overlapping multi-seat requests do not deadlock; spoofed identity is ignored; readiness fails closed when Postgres is paused and recovers when it returns; 32 malformed requests across `/shows` and `/reserve` (and an unknown route, wrong method and wrong content type) all get a clean 4xx.

**Live results:** run `./burst.sh <LIVE_URL>` and paste the output here. <!-- TODO: fill in after deploying -->

Moving authentication from a hand-written filter to Spring Security was done behind characterization tests (forged, expired, unsigned and subject-less tokens; admin versus user). Breaking either of two details turns a test red, which is why they exist: without a dedicated handler a denied `@PreAuthorize` becomes a **500**, because the catch-all advice swallows the exception before the security filter chain sees it; and without a subject validator a token with no subject is accepted. An adversarial pass over the finished service then found four more, all fixed test-first. A `UNIQUE` constraint on the show name (my invention, not the email's) turned the email's own `"friday-night"` example into a `409` on a second run, so show names are now plain labels. Four different inputs still returned a **500**, which broke "zero 5xx": an extreme price overflowed `price x seats` (now capped at 10^12 paise, so 50 seats cannot overflow; a row that predates the cap, or was written straight into the database, still overflows, so that case is a `409 amount-too-large` rather than a 500, which I only found by replaying the probe against the live container), and a NUL character in a show name, idempotency key or user id cannot be stored by Postgres. Those inputs are rejected with a 400 up front, and a generic handler maps any other data the database refuses to a 400 too (I disabled the validators to confirm that net holds for NUL). Under starvation, Postgres lock timeouts also surfaced as a 500, because Spring does not translate them; they are now shed as 429. Things I found by testing, not assuming: an unknown route returned **500** (the catch-all handler flattened Spring's own 404/405/415, which would have broken "zero 5xx" for any probing client); `price_paise: 100.5` was silently accepted and truncated to 100 (Jackson coerces floats into integer fields by default, quietly breaking "money is never a float"); the stock DB health check hung for 40 s+ when Postgres stopped answering (fixed: readiness has its own 2 s deadline and the driver has socket timeouts); a 2 s connection wait turned race losers into 429s, so waits are longer and 429 is a last resort; Docker Desktop's port proxy refuses connection floods that the app handles fine from inside the network (true at about 1,000 connections; I then wrongly blamed it for the failures at 20,000, where the cause was the app's own limits: Tomcat's default connection cap and backlog, and a 20 s database wait); and an unbounded connection cap let a 512MB instance run out of memory, after which the JVM stayed alive but answered nothing for 24 minutes (see Capacity below).

## Capacity and failure behaviour

Measured against the real Docker image with 20,000 requests and a given number of them in flight at once, fired from inside the Docker network so Docker Desktop's port proxy is out of the picture:

| Instance | Simultaneous | Result |
|---|---|---|
| Unconstrained (11GB VM), cap 30,000 | 20,000 | All answered, 0 5xx, 0 double-sells |
| 512MB | 500 and 1,000 | All answered, ~780 and ~620 req/s |
| 512MB | 2,000 | Marginal: GC-bound, 190 req/s, one timeout |
| 512MB, fixed cap of 30,000 | 4,000 and up | JVM ran out of memory and was restarted; ~19,000 requests lost |
| 2GB, fixed cap of 30,000 | 20,000 | Still ran out of memory |
| 512MB, cap sized from heap (1,485) | 20,000 | JVM stays up, `/healthz` stays 200; ~14,000 clients time out waiting; 0 5xx, 0 double-sells |

A request in flight costs on the order of 250KB of heap (inferred from where it breaks, not profiled: virtual-thread stack, Tomcat and security objects, the parsed body), so a fixed connection cap is wrong for every instance size. The cap is therefore **derived from the heap** (`ConnectionLimitConfig`, overridable with `SEAT_MAX_CONNECTIONS`). Beyond it Tomcat stops accepting and waiting clients queue in the kernel's accept queue, which costs almost nothing. The listen backlog is 10,000, but the kernel caps it at `net.core.somaxconn` (4,096 in the Docker VM).

Failure behaviour matters as much as the numbers. With no restart-on-OOM flag, the JVM that ran out of memory could not even log, kept running, answered nothing, and was never restarted: the load generator hung for 24 minutes. The image now runs with `-XX:+ExitOnOutOfMemoryError` and compose has `restart: unless-stopped`; after a forced OOM the service restarted and `/readyz` returned `UP` on its own.

What this does not do: a small instance serves a few hundred requests per second, so 20,000 sockets opened in the *same instant* cannot all be served inside a typical client connect timeout. Those clients see connect timeouts, never a wrong answer or a 5xx. If a checker really does that against a 512MB instance, expect timeouts; the remedy is a larger instance, which should be measured on the real platform rather than assumed.

## AI usage

> **Review and edit this section before submitting. It must reflect what actually happened and what you can defend live.**

I used Claude Code throughout. **Directed (my decisions):** Java/Spring Boot/Postgres; the atomic mechanism (conditional UPDATE + ordered row locks + advisory-lock idempotency, READ COMMITTED not SERIALIZABLE); all-or-nothing multi-seat; token-derived identity; Render free tier; a self-contained Java burst runner; that overload must be a 4xx, never a 5xx; and, after a design round where the assistant proposed a two-step hold-then-confirm flow, my call to follow the email's literal spec instead (reserve returns `confirmed`; release by cancel). **Delegated (written by the AI, reviewed by me):** the Spring/JDBC code, SQL, tests, Dockerfile/compose/Render config, the burst program, and drafts of these docs. **Where the AI was wrong and measurement corrected it:** it first over-built a hold/TTL/sweeper model beyond the spec; it assumed a larger Tomcat backlog would fix connection refusals (it did not; the cause was Docker Desktop's proxy); and its first readiness check hung under a DB outage until a test exposed it.

## What I would do next

- Restore a hold state with TTL (history `c874052`) the moment a real payment step exists between reserving and paying.
- A periodic reconciliation job that recomputes seats from reservations and pages on drift.
- Return 503 + `Retry-After` for a true DB outage, keep 429 for saturation.
- Per-show rate limiting / a virtual waiting room in front of the hottest on-sales; shard hot shows across rows if a single show ever outgrows one primary.
- Real auth (OIDC) instead of the dev token endpoint; paginate `GET /shows/{id}` for large halls; tracing (OpenTelemetry) on top of the request id.
