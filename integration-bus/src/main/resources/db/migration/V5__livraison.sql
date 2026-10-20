-- =====================================================================
-- Schéma "delivery" : simulation de l'application mobile des livreurs.
-- Comme l'ERP, c'est un système à part : il ne parle au bus que par RabbitMQ.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS delivery;

CREATE SEQUENCE delivery.mission_seq START 1;

CREATE TABLE delivery.mission (
    mission_id      VARCHAR(32)   PRIMARY KEY,           -- LIV-AAAA-NNNN
    order_id        VARCHAR(32)   NOT NULL UNIQUE,       -- une seule mission par commande
    correlation_id  UUID          NOT NULL,
    customer_id     VARCHAR(64),
    address         VARCHAR(255),
    parcels         TEXT          NOT NULL,              -- colis au format JSON de l'app
    amount          NUMERIC(14,0),
    status          VARCHAR(32)   NOT NULL,              -- ASSIGNED, IN_DELIVERY, DELIVERED
    driver          VARCHAR(64),
    assigned_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    picked_up_at    TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ
);

-- Dernier statut de livraison connu du bus (Process Manager : ignore les retours en arrière).
ALTER TABLE bus.orders ADD COLUMN delivery_status VARCHAR(32);

-- File RabbitMQ d'origine d'un message en DLQ, pour le rejouer au bon endroit.
ALTER TABLE bus.dead_letter ADD COLUMN replay_routing_key VARCHAR(64) NOT NULL DEFAULT 'order.submitted';
