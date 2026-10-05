-- Optional time-boxed holds. A show created with hold_seconds makes reserve place a HOLD that the owner must
-- confirm before it lapses; without hold_seconds (the default) reserve sells immediately, as before.
-- seats.status already allows 'held' and seats.expires_at already exists; reservations gain their own expiry.
ALTER TABLE shows ADD COLUMN hold_seconds INT CHECK (hold_seconds BETWEEN 1 AND 86400);
ALTER TABLE reservations ADD COLUMN expires_at TIMESTAMPTZ;
CREATE INDEX idx_reservations_expiring ON reservations (expires_at) WHERE status = 'held';
