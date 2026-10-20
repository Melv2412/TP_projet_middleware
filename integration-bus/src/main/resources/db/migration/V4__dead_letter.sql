-- Index des messages envoyés dans la Dead Letter Queue RabbitMQ (sidam.orders.dlq).
-- Le message lui-même reste dans RabbitMQ ; cette table sert à l'affichage et au rejeu.
CREATE TABLE bus.dead_letter (
    dlq_id          UUID         PRIMARY KEY,   -- aussi transporté en en-tête AMQP "dlqId"
    order_id        VARCHAR(32),                -- null si le message est illisible
    correlation_id  UUID,
    error_code      VARCHAR(64)  NOT NULL,      -- ERP_UNAVAILABLE, INVALID_PAYLOAD, TECHNICAL_ERROR
    reason          TEXT         NOT NULL,
    failed_route    VARCHAR(64),
    payload         TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    replayed_at     TIMESTAMPTZ
);

CREATE INDEX idx_dead_letter_pending ON bus.dead_letter(created_at) WHERE replayed_at IS NULL;
