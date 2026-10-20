# 09 — Routes Camel et patterns EIP

## Routes

| Route ID | Entrée | Sortie | EIP | Étape |
|---|---|---|---|---|
| `order-submit` | `direct:submitOrder` (depuis l'API) | RabbitMQ `sidam.orders.incoming` | Message Channel, Correlation Identifier | 2 |
| `order-intake` | RabbitMQ `sidam.orders.incoming` | `direct:checkStock` | Message Translator (JSON → Canonical) | 2 |
| `stock-check` | `direct:checkStock` | `direct:erpSubmit` ou `direct:rejectOrder` | **Splitter**, **Aggregator**, **Content-Based Router** | 2 |
| `erp-submit` | `direct:erpSubmit` | ERP (PostgreSQL `erp`) | **Message Translator** (Canonical → XML), Content Enricher (prix) | 2 |
| `order-reject` | `direct:rejectOrder` | Statut `REJECTED_OUT_OF_STOCK` | Invalid Message Channel | 2 |
| `order-intake` (bloc `idempotentConsumer`) | clé `ORDER_SUBMITTED:{orderId}` | `bus.processed_message` | **Idempotent Consumer** (mémoire PostgreSQL partagée) | 3 |
| errorHandler des routes de traitement | exception technique | `direct:deadLetter` | **Retry** ×3 (2 s, 4 s, 8 s, exponentiel) | 4 |
| `dead-letter` | `direct:deadLetter` | RabbitMQ `sidam.dlx` → `sidam.orders.dlq` | **Dead Letter Channel** (message d'origine intact) | 4 |
| `csv-import` | `POST /api/orders/import-csv` | `direct:submitOrder` | Message Translator (CSV → JSON Store), regroupement par `order_id`, Splitter | 4 |
| Replay (`DeadLetterService`) | `POST /api/dlq/{id}/replay` | `sidam.orders.incoming` | Message Store / rejeu | 4 |
| `delivery-dispatch` | après `INVOICED` | RabbitMQ `sidam.delivery.assigned` | Message Translator (canonique → mission JSON) + Content Enricher (désignations ERP) + Process Manager | 5 |
| `delivery-app-inbox` *(côté app livreur)* | RabbitMQ `sidam.delivery.assigned` | schéma `delivery` | — (système externe simulé) | 5 |
| `delivery-status` | RabbitMQ `sidam.delivery.status` | statut bus + ERP (XML `<livraison>`) | **Content-Based Router** (sur le statut), **Process Manager** (ordre ASSIGNED < IN_DELIVERY < DELIVERED, statut ancien ou en double ignoré), Message Translator | 5 |

## Correspondance EIP ↔ preuve observable

| EIP | Où dans le code | Ce qu'on montre au jury |
|---|---|---|
| Message Translator | `order-intake`, `erp-submit` | JSON entrant et XML sortant côte à côte (événements `TRANSFORMED`) |
| Splitter | `stock-check` → `.split(...)` | Un événement `STOCK_LINE_CHECKED` par article |
| Aggregator | `stock-check` → `AggregationStrategy` | Événement `STOCK_AGGREGATED` avec le verdict global |
| Content-Based Router | `stock-check` → `.choice()` | Événement `ROUTED` avec la destination choisie |
| Correlation Identifier | en-tête `correlationId` | Tous les événements d'une commande ont le même ID |
| Idempotent Consumer | étape 3 | Message envoyé deux fois → un seul effet |
| Dead Letter Channel | étape 4 | Message dans `sidam.orders.dlq` (console RabbitMQ) |
| Retry | étape 4 | Événements `RETRYING` avec numéro de tentative |

## Règle d'erreur

- **Erreur métier** (stock insuffisant) → pas de retry, route de rejet.
- **Erreur technique** (ERP injoignable, timeout) → retry avec backoff, puis DLQ.
- **Message invalide** (JSON/XML mal formé) → DLQ directe avec motif `INVALID_PAYLOAD`.
