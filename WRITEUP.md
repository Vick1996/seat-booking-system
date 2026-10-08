# Write-up

## The atomic decision

A seat is sold by a **guarded conditional UPDATE**: `UPDATE seats SET status='confirmed', ... WHERE show_id=? AND label = ANY(?) AND status='available'`, and the transaction rolls back unless it affected exactly as many rows as were requested. The test ("is it free?") and the set ("take it") are one statement, evaluated by Postgres under a row lock, so two buyers cannot both observe `available` and both win. There is no read-then-write window.

Before that statement the request runs `SELECT ... FROM seats WHERE label = ANY(?) ORDER BY label FOR UPDATE`. The decision is made on locked rows, so losers get a clean `seat-taken` 409 without writing anything, and the guarded UPDATE is the final check.

**Multi-seat deadlock avoidance.** Every request, and every cancel, locks its seats in the same global order (`ORDER BY label`). Two requests wanting `{A1,A2}` and `{A2,A1}` both lock A1 first, so there is no cycle. Multi-seat is all-or-nothing: any unavailable seat declines the whole request.

**Per-user limit.** The count is only race-free if concurrent requests from one user are serialized, so a transaction-scoped advisory lock on `(show, user)` guards it. Different users never contend.

## Idempotency

The key is stored on the reservation row with `UNIQUE(user_id, idempotency_key)`, next to a signature of the request (the sorted seat list). Exactly-once is enforced in two layers: a transaction-scoped advisory lock on `(user, key)` taken first, so a concurrent retry waits, then reads the committed row and replays it; and the unique constraint as a backstop. Same key and same request returns the original reservation with **200** (not 201, so "exactly one 201 per seat" holds under retries). Same key with a different seat set or show is **409 idempotency-conflict**. Keys are scoped to the user. Declined attempts store nothing, so they can be retried with the same key. A request with no key is treated as unique. See ADR 0002.

## Holds and expiry

Holds are **opt-in per show**: a show created with `hold_seconds` makes reserve place a hold (`status: "held"`, `expires_at`) that the owner confirms with `POST /reservations/{id}/confirm`. A show without it sells immediately, as the email's example shows. Cancel is owner-only and idempotent. See ADR 0005.

**Expiry is correct without any background job.** A lapsed hold is free by the SQL itself: the seat-locking query treats `held AND expires_at < clock_timestamp()` as available, the guarded UPDATE can take it over, the per-user limit counts only live holds, and `GET /shows/{id}` reports it as available. Confirm promotes only seats still `held AND expires_at > clock_timestamp()`, so a lapsed hold gets `409 hold-expired` and is never resurrected. All timestamps come from the database clock, so app-host skew cannot matter. A sweeper (every 10 s) only tidies rows, using `FOR UPDATE SKIP LOCKED` so it never waits on a live request; `HoldWithoutSweeperIT` runs with it switched off.

## Consistency versus availability under a partition

This is a CP system by construction: one Postgres primary is the only source of truth, and nothing is cached or answered from a replica. If the app cannot reach the database it refuses rather than guess: readiness returns 503 within 2 s, and requests that cannot get a connection or lock inside the bounded waits get **429**, never a wrong answer. The cost is availability: during a partition no seats can be sold, which is the right trade for a system whose one job is never to sell a seat twice. Caveat: a DB outage surfaces as 429 because the spec wants declines to be 4xx; a real deployment would want 503 + `Retry-After` for a true outage.

## Observability: what I would be paged for at 2 am

- **Any 5xx** (rate > 0 for 5 min). In this design a 5xx is a bug, never a normal outcome.
- **Readiness failing** for more than a minute (the DB is unreachable or hung).
- **`reservations_declined_total{reason="overloaded"}` rising**: we are shedding load; capacity, not correctness. Pair with Hikari pending connections.
- **Metric/state drift**: `reservations_confirmed_total` against confirmed seats in the DB.
- **`seat-taken` ratio collapsing to zero during an on-sale** (requests not reaching the service) or a p99 spike on `/reserve`.

Not paging: `seat-taken`, `per-user-limit`, `idempotent-replay`. Those are the system working.

## AI usage

The design decisions were mine; I used Claude Code to write the code to them and reviewed what it produced.

**Decided by me:** Java, Spring Boot and Postgres; the atomic mechanism (conditional UPDATE, ordered row locks, advisory-lock idempotency, READ COMMITTED rather than SERIALIZABLE); all-or-nothing multi-seat; token-derived identity; deploying on Render's free tier; that overload must be a 4xx, never a 5xx; and following the email's literal spec (reserve returns `confirmed`, release by cancel, holds only as an opt-in) over the assistant's proposed two-step hold-then-confirm flow.

**Written by the AI, reviewed by me:** the Spring/JDBC code, SQL, tests, Docker/compose/Render config, the burst program, and drafts of the docs.

**Where the AI was wrong and measurement corrected it:** it first over-built a hold/TTL/sweeper model beyond the spec; it blamed Docker Desktop's port proxy for failures that were the app's own connection limits; and its first readiness check hung under a DB outage until a test exposed it.

## What I would do next

- A periodic reconciliation job that recomputes seats from reservations and pages on drift.
- Return 503 + `Retry-After` for a true DB outage, keep 429 for saturation.
- Per-show rate limiting or a virtual waiting room for the hottest on-sales; shard hot shows across rows if one ever outgrows a single primary.
- Real auth (OIDC) instead of the dev token endpoint; paginate `GET /shows/{id}` for large halls; tracing (OpenTelemetry) on top of the request id.
- Restore a hold state with a payment step the moment one exists between reserving and paying.
