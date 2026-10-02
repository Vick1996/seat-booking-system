# READ COMMITTED with explicit locks, not SERIALIZABLE

Correctness comes from explicit, ordered locking and a guarded UPDATE rather than from raising the isolation level. Seats are locked with `SELECT ... ORDER BY label FOR UPDATE` (the same order for every request, so overlapping multi-seat requests cannot deadlock), the decision is made on the locked rows, and the final `UPDATE ... WHERE status = 'available'` must affect every requested row or the transaction rolls back.

## Considered Options

SERIALIZABLE with a retry on `40001`. It is stronger, but every write conflict becomes an exception that must be retried in the app, and an exhausted retry budget surfaces as a 5xx, which the zero-5xx requirement forbids. It would also be redundant given the locks already taken.
