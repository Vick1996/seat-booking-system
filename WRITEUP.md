# Write-up

## The atomic decision

A seat is sold by a **guarded conditional UPDATE**: `UPDATE seats SET status='confirmed', ... WHERE show_id=? AND label = ANY(?) AND status='available'`, and the transaction rolls back unless it affected exactly as many rows as were requested. There is no read-then-write window: the test ("is it free?") and the set ("take it") are one statement, evaluated by Postgres under a row lock, so two buyers cannot both observe `available` and both win.

Before that statement the request takes `SELECT ... FROM seats WHERE label = ANY(?) ORDER BY label FOR UPDATE`. That makes the decision on locked rows (losers get a clean `seat-taken` 409 without doing any write), and the guarded UPDATE is the belt-and-braces final check.

**Multi-seat deadlock avoidance.** Every request locks its seats in the same global order (`ORDER BY label`). Two requests wanting `{A1,A2}` and `{A2,A1}` both lock A1 first, so there is no cycle. Cancel locks in the same order. The test `overlappingMultiSeat_noDeadlock_noDoubleHold` fires overlapping pairs in both orders. Multi-seat is **all-or-nothing**: any unavailable seat declines the whole request, which keeps the behaviour identical under concurrency.

**Per-user limit.** The count (`confirmed` seats for this user in this show) is only race-free if concurrent requests from the same user are serialized, otherwise ten parallel requests all read "0 seats" and all pass. A transaction-scoped advisory lock on `(show, user)` serializes them. Different users never contend on it.

## Idempotency

The key is stored on the reservation row with `UNIQUE(user_id, idempotency_key)` and a signature of the request (the sorted seat list). Exactly-once is enforced in two layers: a transaction-scoped advisory lock on `(user, key)` taken at the start (so a concurrent retry waits, then reads the committed row and replays it), and the unique constraint as a backstop. Same key and same seats returns the original reservation with **200** (not 201, so "exactly one 201 per seat" stays true under retries); same key with a different seat set or show is **409 idempotency-conflict**. The key is scoped to the user, so one user cannot collide with another's keys. Declined attempts store nothing (the transaction rolls back), so a declined request can be retried with the same key. See ADR 0002.

## Holds and expiry

I chose the **explicit cancel** model. The spec's example shows reserve returning `confirmed`, and it asks for cancel *or* a timed hold. Cancel is owner-only, idempotent, and its UPDATE is guarded by `reservation_id`, so a stale cancel can never free a seat that has since been bought by someone else (tested). `held` exists as a seat status and is reported by `GET /shows/{id}` (always 0 today) so the invariant keeps its three terms. A timed-hold model was built and removed; it is in history at `c874052`, and ADR 0001 explains why.

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

Local (laptop, app on the host, Postgres in Docker), default `./burst.sh`: 20,000 requests in 35 s (~570 req/s): 3,691 confirmed, 649 idempotent replays, 13,500 `seat-taken`, 2,160 `per-user-limit`, **0 5xx, 0 transport errors, 0 seats sold twice**, invariant held at all 17 samples taken during the burst, and every metric delta matched the responses observed. The `./mvnw verify` suite (10 tests) passes: 300 buyers on one seat produce exactly one 201; a user firing 12 parallel reserves with a limit of 4 ends with exactly 4; 40 parallel same-key requests create one reservation; overlapping multi-seat requests do not deadlock; spoofed identity is ignored; readiness fails closed when Postgres is paused and recovers when it returns; 32 malformed requests across `/shows` and `/reserve` (and an unknown route, wrong method and wrong content type) all get a clean 4xx.

**Live results:** run `./burst.sh <LIVE_URL>` and paste the output here. <!-- TODO: fill in after deploying -->

Things I found by testing, not assuming: an unknown route returned **500** (the catch-all handler flattened Spring's own 404/405/415, which would have broken "zero 5xx" for any probing client); `price_paise: 100.5` was silently accepted and truncated to 100 (Jackson coerces floats into integer fields by default, quietly breaking "money is never a float"); the stock DB health check hung for 40 s+ when Postgres stopped answering (fixed: readiness has its own 2 s deadline and the driver has socket timeouts); a 2 s connection wait turned race losers into 429s, so waits are longer and 429 is a last resort; Docker Desktop's port proxy refuses connection floods that the app handles fine from inside the network.

## AI usage

> **Review and edit this section before submitting. It must reflect what actually happened and what you can defend live.**

I used Claude Code throughout. **Directed (my decisions):** Java/Spring Boot/Postgres; the atomic mechanism (conditional UPDATE + ordered row locks + advisory-lock idempotency, READ COMMITTED not SERIALIZABLE); all-or-nothing multi-seat; token-derived identity; Render free tier; a self-contained Java burst runner; that overload must be a 4xx, never a 5xx; and, after a design round where the assistant proposed a two-step hold-then-confirm flow, my call to follow the email's literal spec instead (reserve returns `confirmed`; release by cancel). **Delegated (written by the AI, reviewed by me):** the Spring/JDBC code, SQL, tests, Dockerfile/compose/Render config, the burst program, and drafts of these docs. **Where the AI was wrong and measurement corrected it:** it first over-built a hold/TTL/sweeper model beyond the spec; it assumed a larger Tomcat backlog would fix connection refusals (it did not; the cause was Docker Desktop's proxy); and its first readiness check hung under a DB outage until a test exposed it.

## What I would do next

- Restore a hold state with TTL (history `c874052`) the moment a real payment step exists between reserving and paying.
- A periodic reconciliation job that recomputes seats from reservations and pages on drift.
- Return 503 + `Retry-After` for a true DB outage, keep 429 for saturation.
- Per-show rate limiting / a virtual waiting room in front of the hottest on-sales; shard hot shows across rows if a single show ever outgrows one primary.
- Real auth (OIDC) instead of the dev token endpoint; paginate `GET /shows/{id}` for large halls; tracing (OpenTelemetry) on top of the request id.
