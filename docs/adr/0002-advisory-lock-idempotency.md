# Serialize idempotent retries with a Postgres advisory lock

Reserve starts by taking `pg_advisory_xact_lock(hashtextextended(user || ':' || key, 0))`. A concurrent retry with the same key blocks on that lock, and by the time it gets it the winner has committed, so it reads the stored reservation and replays it (200, not 201). `UNIQUE(user_id, idempotency_key)` remains as the backstop. A second advisory lock per (show, user) serializes the per-user limit check so parallel requests with different keys cannot overshoot it.

## Considered Options

`INSERT ... ON CONFLICT` plus a bounded retry loop (the loser can read before the winner commits, so it needs polling), and catching the unique violation then re-reading (turns a normal outcome into a DB error path and the blocking read can stall). The advisory lock has no retry loop and no visibility gap.

## Consequences

Same-key requests are serialized, which is the point. The lock auto-releases at commit or rollback, so a crashed request cannot leave it held.
