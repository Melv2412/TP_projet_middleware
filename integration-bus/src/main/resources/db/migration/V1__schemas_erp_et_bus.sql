-- =====================================================================
-- Schéma "erp" : simulation de l'ERP legacy (stock + facturation).
-- Le bus n'y accède que via l'adaptateur ERP, qui parle XML.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS erp;

CREATE TABLE erp.product (
    sku         VARCHAR(32)   PRIMARY KEY,
    name        VARCHAR(128)  NOT NULL,
    unit_price  NUMERIC(12,0) NOT NULL CHECK (unit_price >= 0)
);

CREATE TABLE erp.inventory (
    sku              VARCHAR(32) PRIMARY KEY REFERENCES erp.product(sku),
    available        INT         NOT NULL CHECK (available >= 0),
    reserved         INT         NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    initial_quantity INT         NOT NULL,           -- utilisé par le reset de démo
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Commande telle que reçue par l'ERP : on conserve le XML brut reçu.
CREATE TABLE erp.erp_order (
    ref          VARCHAR(32)  PRIMARY KEY,           -- clé unique = idempotence côté ERP
    customer_id  VARCHAR(64),
    xml_payload  TEXT         NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE erp.erp_order_line (
    id          BIGSERIAL     PRIMARY KEY,
    order_ref   VARCHAR(32)   NOT NULL REFERENCES erp.erp_order(ref),
    sku         VARCHAR(32)   NOT NULL REFERENCES erp.product(sku),
    quantity    INT           NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(12,0) NOT NULL
);

CREATE TABLE erp.invoice (
    invoice_no  VARCHAR(32)   PRIMARY KEY,
    order_ref   VARCHAR(32)   NOT NULL UNIQUE REFERENCES erp.erp_order(ref),  -- une facture par commande
    total       NUMERIC(14,0) NOT NULL,
    issued_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- =====================================================================
-- Schéma "bus" : état et audit du middleware.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS bus;

CREATE SEQUENCE bus.order_number_seq START 1;

CREATE TABLE bus.orders (
    order_id          VARCHAR(32)   PRIMARY KEY,
    correlation_id    UUID          NOT NULL UNIQUE,
    customer_id       VARCHAR(64),
    shipping_address  VARCHAR(255),
    total             NUMERIC(14,0),
    status            VARCHAR(32)   NOT NULL,
    status_reason     TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE bus.order_item (
    id          BIGSERIAL     PRIMARY KEY,
    order_id    VARCHAR(32)   NOT NULL REFERENCES bus.orders(order_id) ON DELETE CASCADE,
    sku         VARCHAR(32)   NOT NULL,
    quantity    INT           NOT NULL,
    unit_price  NUMERIC(12,0)
);

-- Journal d'audit : une ligne par étape traversée dans le bus.
CREATE TABLE bus.integration_event (
    id              BIGSERIAL    PRIMARY KEY,
    event_id        UUID         NOT NULL UNIQUE,
    order_id        VARCHAR(32),
    correlation_id  UUID,
    source          VARCHAR(32),
    destination     VARCHAR(32),
    route_id        VARCHAR(64),
    pattern         VARCHAR(64),
    status          VARCHAR(32)  NOT NULL,
    message         TEXT,
    payload         TEXT,
    payload_format  VARCHAR(8),
    attempt         INT          NOT NULL DEFAULT 1,
    duration_ms     BIGINT,
    error_code      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_event_order ON bus.integration_event(order_id);
CREATE INDEX idx_event_correlation ON bus.integration_event(correlation_id);
