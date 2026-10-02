CREATE TABLE shows (
    id             UUID PRIMARY KEY,
    name           TEXT        NOT NULL UNIQUE,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    show_id         UUID        NOT NULL REFERENCES shows (id),
    user_id         TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('held', 'confirmed', 'cancelled', 'expired')),
    amount_paise    BIGINT      NOT NULL CHECK (amount_paise >= 0),
    seat_count      INT         NOT NULL CHECK (seat_count > 0),
    idempotency_key TEXT        NOT NULL,
    seat_signature  TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- exactly-once per (user, key)
    CONSTRAINT uq_reservation_user_key UNIQUE (user_id, idempotency_key)
);

CREATE TABLE seats (
    show_id        UUID        NOT NULL REFERENCES shows (id),
    label          TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'available'
                   CHECK (status IN ('available', 'held', 'confirmed')),
    holder_user_id TEXT,
    reservation_id UUID REFERENCES reservations (id),
    expires_at     TIMESTAMPTZ,
    PRIMARY KEY (show_id, label)
);

CREATE INDEX idx_seats_show_status ON seats (show_id, status);
CREATE INDEX idx_seats_reservation ON seats (reservation_id);
CREATE INDEX idx_seats_holder ON seats (show_id, holder_user_id) WHERE status IN ('held', 'confirmed');
