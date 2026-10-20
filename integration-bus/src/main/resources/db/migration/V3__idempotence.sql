-- EIP Idempotent Consumer : une ligne par message déjà traité.
-- La clé primaire garantit qu'un même message ne peut être enregistré qu'une fois,
-- même si deux copies arrivent en même temps sur deux consommateurs.
CREATE TABLE bus.processed_message (
    message_key   VARCHAR(128) PRIMARY KEY,   -- ex. "ORDER_SUBMITTED:CMD-2026-0001"
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
