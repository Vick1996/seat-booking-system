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

## Try it in 60 seconds

Works against a local run (`URL=http://localhost:8080`) or the live service.

```sh
URL=http://localhost:8080
JSON='Content-Type: application/json'
tok() { curl -s -X POST $URL/auth/token -H "$JSON" -d "$1" | sed 's/.*"token":"\([^"]*\)".*/\1/'; }

ADMIN=$(tok '{"user_id":"admin","admin_key":"dev-admin-key"}')   # demo admin key, see below
ALICE=$(tok '{"user_id":"alice"}')
BOB=$(tok '{"user_id":"bob"}')

# admin creates a show                                          -> 201, every seat "available"
SHOW=$(curl -s -X POST $URL/shows -H "$JSON" -H "Authorization: Bearer $ADMIN" \
  -d "{\"name\":\"demo-$(date +%s)\",\"price_paise\":25000,\"seats\":[\"A1\",\"A2\",\"A3\"]}" \
  | sed 's/.*"id":"\([^"]*\)".*/\1/')

# alice reserves A1                                              -> 201, status "confirmed", amount_paise 25000
RESERVED=$(curl -s -X POST $URL/shows/$SHOW/reserve -H "$JSON" -H "Authorization: Bearer $ALICE" \
  -d '{"seats":["A1"],"idempotency_key":"k1"}'); echo "$RESERVED"
RES=$(echo "$RESERVED" | sed 's/.*"reservation_id":"\([^"]*\)".*/\1/')

# the same request again (a retry)                               -> 200, the original reservation
curl -s -o /dev/null -w "retry: %{http_code}\n" -X POST $URL/shows/$SHOW/reserve -H "$JSON" \
  -H "Authorization: Bearer $ALICE" -d '{"seats":["A1"],"idempotency_key":"k1"}'

# bob wants the same seat                                        -> 409 seat-taken
curl -s -w "  (%{http_code})\n" -X POST $URL/shows/$SHOW/reserve -H "$JSON" -H "Authorization: Bearer $BOB" \
  -d '{"seats":["A1"],"idempotency_key":"k2"}'

# anyone can read the state; available + held + confirmed == total_seats
curl -s $URL/shows/$SHOW | sed 's/"seats":.*//'; echo

# only the owner can cancel                                      -> 403 for bob, 200 for alice
curl -s -o /dev/null -w "bob cancels: %{http_code}\n" -X POST $URL/reservations/$RES/cancel -H "Authorization: Bearer $BOB"
curl -s -o /dev/null -w "alice cancels: %{http_code}\n" -X POST $URL/reservations/$RES/cancel -H "Authorization: Bearer $ALICE"
```

**The admin key is a public demo credential.** The assignment does not say how a checker obtains an admin token, so `dev-admin-key` is documented here on purpose. It only allows creating shows; every other call needs just a user token. Tokens cannot be forged without the JWT secret, which is private. A real deployment must set `SEAT_ADMIN_KEY` (and `SEAT_JWT_SECRET`) privately.

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

Tunables: `BURST_REQUESTS` (20000) `BURST_USERS` (2000) `BURST_SEATS` (497, plus 3 hot seats = the 500-seat maximum) `BURST_HOT` (3) `BURST_CONCURRENCY` (1000) `BURST_ADMIN_KEY` (`dev-admin-key`).

> Docker Desktop on Windows/macOS can refuse a flood of simultaneous connections at its own port proxy (`Connection refused` before the app sees the request). The app is not involved: the same burst run from inside the Docker network shows zero errors. If you see this locally, lower `BURST_CONCURRENCY` to ~200.

## API

Identity comes from a bearer token, never from a request body. Tokens are verified by Spring Security (OAuth2 resource server, HS256, stateless); a missing or invalid token is a `401` with a JSON body. There are three kinds of caller:

| Caller | Can do |
|---|---|
| **Anonymous** | `POST /auth/token`, `GET /shows/{id}`, health and metrics endpoints |
| **User** (any valid token) | reserve seats, cancel **their own** reservations |
| **Admin** (token with `"admin": true`) | everything a user can, plus `POST /shows`. A non-admin gets `403` |

Get a token (dev endpoint; a real deployment would sit behind an identity provider):

```sh
curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}'
curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"admin","admin_key":"dev-admin-key"}'   # admin (public demo key)
```

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /shows` `{name, seats[], price_paise, per_user_limit?, hold_seconds?}` | admin | all seats start `available`; limit defaults to 4. `hold_seconds` (1..86400) is optional and opts the show into **time-boxed holds** (see below) |
| `POST /shows/{id}/reserve` `{seats[], idempotency_key}` | user | key may also be an `Idempotency-Key` header; if **omitted**, the request is treated as unique (no retry protection, but a seat is still never sold twice); a blank key is a `400`. **201** new, **200** replay, **409** declined |
| `POST /reservations/{id}/confirm` | owner only | **hold shows only.** Turns a live hold into a sale (200, `status: "confirmed"`); safe to repeat. A hold that has lapsed is refused with `409 hold-expired` and never resurrected |
| `POST /reservations/{id}/cancel` | owner only | releases a hold or a sale; safe to repeat; never frees a seat now owned by someone else |
| `GET /shows/{id}` | none | `total_seats`, `available`, `held`, `confirmed`, per-seat status |

Behaviour that is documented and tested:

- **Money** is integer paise. `amount_paise = price_paise * seat_count`. `price_paise` may be at most 1,000,000,000,000 (10^12), so the largest request (50 seats) can never overflow a 64-bit amount.
- **Show names are labels, not keys.** Two shows may share a name (the email's own example is `"friday-night"`); every show gets its own `id`.
- **Input limits** (all breaches are a `400`): seat labels match `[A-Za-z0-9._-]{1,32}`, at most **500 seats per show** (a cinema or theatre hall, not a stadium; a larger request is a mistake) and 50 per reserve request; names, user ids and idempotency keys must not contain control characters (NUL, newlines, tabs). Anything else Postgres refuses to store is also a `400`, never a `500`.
- **Multi-seat is all-or-nothing.** If any requested seat is taken, the whole request is a 409 and nothing is held.
- **Declines are 409** with a machine-readable `reason`: `seat-taken`, `per-user-limit`, `idempotency-conflict`. Same key + same seats is a replay (200); same key + different seats is a 409.
- **Release, two ways.** Owner-only cancel always works. In addition, a show created with `hold_seconds` makes reserve place a **hold** (`201`, `status: "held"`, plus `expires_at`) that the owner must confirm before it lapses. A lapsed hold is free again **at once**: another user can reserve it, `GET /shows/{id}` reports it as `available`, and it stops counting toward the per-user limit. Correctness does not depend on a background job; a sweeper (every 10s) only tidies the reservation's status to `expired`.
- **Default is unchanged.** A show created **without** `hold_seconds` behaves exactly as the assignment's example describes: reserve returns `201` with `status: "confirmed"` immediately, and there is no `expires_at`.
- Under extreme saturation (no DB connection or lock within the bounded waits) the service sheds load as **429**, never 5xx.

## Observability

- `GET /healthz` (liveness: process up) and `GET /readyz` (readiness: DB reachable, **fails closed with 503** within 2 s, even if the DB hangs). Same checks as `/actuator/health/liveness` and `/actuator/health/readiness`. No token needed.
- `GET /metrics` (also `/actuator/prometheus`): `reservations_confirmed_total`, `reservations_declined_total{reason="seat-taken|per-user-limit|idempotent-replay|idempotency-conflict|overloaded"}`, `seats_available{show_id}`. Counters are bumped after commit and reconcile with the API; the burst checks it.
- Logs are structured JSON on stdout with a `request_id` on every line (also returned as `X-Request-Id`; send your own to correlate), and one access line per request. On Render: dashboard > Logs.

## Deploy (Render, free tier)

1. Push this repo to GitHub (public).
2. Render > **New > Blueprint** > select the repo. `render.yaml` creates the web service (Docker) and a free Postgres, and wires the DB settings.
3. Nothing to enter: `SEAT_JWT_SECRET` is generated (private), and `SEAT_ADMIN_KEY` is fixed to the documented demo key in `render.yaml`, so reviewers can create shows with no out-of-band step.
4. Wait for the first deploy; the health check is `/actuator/health/readiness`.

Free tier notes: the web service sleeps after ~15 min idle (cold start ~1 min; the burst runner waits for readiness), and the free Postgres has a low connection cap, so the pool is capped at 15 (`DB_POOL_SIZE`). The number of concurrent connections is capped from the JVM heap (`SEAT_MAX_CONNECTIONS` overrides it): a 512MB instance fully serves about 1,000 simultaneous clients, and beyond its cap clients wait in the kernel's queue rather than crashing the service. See "Capacity and failure behaviour" in `WRITEUP.md`.
