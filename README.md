# Seat Reservation at Scale

JSON HTTP API that sells assigned seats and stays correct when thousands of buyers hit the same show at once: no seat sold twice, per-user limit enforced, retries counted once, zero 5xx.

**Live URL:** `<LIVE_URL>` (fill in after deploying) · **Metrics:** `<LIVE_URL>/actuator/prometheus` · **Health:** `<LIVE_URL>/actuator/health/readiness`

Stack: Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway. Design notes in [WRITEUP.md](WRITEUP.md), vocabulary in [CONTEXT.md](CONTEXT.md), decisions in [docs/adr](docs/adr).

## Run it

```sh
docker compose up --build        # app + postgres on http://localhost:8080  (or: make up)
./mvnw package                   # build the jar, no Docker needed
./mvnw verify                    # concurrency + ops integration tests (needs Docker)
```

A clean checkout needs only Docker (to run) or a JDK 21 (to build). `mvnw` downloads Maven itself.

## One-command burst

```sh
./burst.sh http://localhost:8080          # or: make burst BASE_URL=<LIVE_URL>
```

Needs only a JDK 17+ (single-file program, no build). It creates a fresh show, then fires 20,000 requests: a **hot-seat storm** (40%, three hot seats, many users each), spread single seats, multi-seat pairs, and **same-key retries** (10%). It prints the outcome distribution (confirmed / declined by reason / 5xx) and the reconciliation, and **exits 1** if any of these fail:

- a seat was sold to more than one reservation, or a hot seat has anything but exactly one winner
- any 5xx, or any transport-level failure
- `available + held + confirmed != total_seats` (sampled every 500 ms *during* the burst and again after)
- confirmed seats in `GET /shows/{id}` differ from the seats in the 201 responses
- (reported, not failing, because other clients may share the service) metric deltas versus responses observed

Tunables: `BURST_REQUESTS` (20000) `BURST_USERS` (2000) `BURST_SEATS` (5000) `BURST_HOT` (3) `BURST_CONCURRENCY` (1000) `BURST_ADMIN_KEY` (`dev-admin-key`).

> Docker Desktop on Windows/macOS can refuse a flood of simultaneous connections at its own port proxy (`Connection refused` before the app sees the request). The app is not involved: the same burst run from inside the Docker network shows zero errors. If you see this locally, lower `BURST_CONCURRENCY` to ~200.

## API

Identity comes from a bearer token, never from a request body. Get one (dev endpoint; a real deployment would sit behind an identity provider):

```sh
curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}'
curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"admin","admin_key":"<ADMIN_KEY>"}'   # admin
```

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /shows` `{name, seats[], price_paise, per_user_limit?}` | admin | all seats start `available`; limit defaults to 4 |
| `POST /shows/{id}/reserve` `{seats[], idempotency_key}` | user | key may also be an `Idempotency-Key` header. **201** new, **200** replay, **409** declined |
| `POST /reservations/{id}/cancel` | owner only | releases the seats; safe to repeat; never frees a seat now owned by someone else |
| `GET /shows/{id}` | none | `total_seats`, `available`, `held`, `confirmed`, per-seat status |

Behaviour that is documented and tested:

- **Money** is integer paise. `amount_paise = price_paise * seat_count`.
- **Multi-seat is all-or-nothing.** If any requested seat is taken, the whole request is a 409 and nothing is held.
- **Declines are 409** with a machine-readable `reason`: `seat-taken`, `per-user-limit`, `idempotency-conflict`. Same key + same seats is a replay (200); same key + different seats is a 409.
- **Release model:** explicit owner-only cancel. A reserve is confirmed immediately (no timed hold).
- Under extreme saturation (no DB connection or lock within the bounded waits) the service sheds load as **429**, never 5xx.

## Observability

- `GET /actuator/health/liveness` (process up) and `/readiness` (DB reachable, **fails closed with 503** within 2 s, even if the DB hangs).
- `GET /actuator/prometheus`: `reservations_confirmed_total`, `reservations_declined_total{reason="seat-taken|per-user-limit|idempotent-replay|idempotency-conflict|overloaded"}`, `seats_available{show_id}`. Counters are bumped after commit and reconcile with the API; the burst checks it.
- Logs are structured JSON on stdout with a `request_id` on every line (also returned as `X-Request-Id`; send your own to correlate), and one access line per request. On Render: dashboard > Logs.

## Deploy (Render, free tier)

1. Push this repo to GitHub (public).
2. Render > **New > Blueprint** > select the repo. `render.yaml` creates the web service (Docker) and a free Postgres, and wires the DB settings.
3. When prompted, set `SEAT_ADMIN_KEY` (share it with reviewers). `SEAT_JWT_SECRET` is generated.
4. Wait for the first deploy; the health check is `/actuator/health/readiness`.

Free tier notes: the web service sleeps after ~15 min idle (cold start ~1 min; the burst runner waits for readiness), and the free Postgres has a low connection cap, so the pool is capped at 15 (`DB_POOL_SIZE`).
