# 04 — Exigences fonctionnelles

Priorité : **M** = Must, **S** = Should, **C** = Could. Étape = étape de réalisation (voir [11_planning_etapes.md](11_planning_etapes.md)).

## Réception des commandes

| ID | Exigence | Prio | Étape |
|---|---|---|---|
| EF-01 | Le bus expose `POST /api/orders` acceptant une commande JSON du Store | M | 2 |
| EF-02 | Le bus valide le contenu (articles non vides, quantités > 0, SKU renseignés) et répond `400` sinon | M | 2 |
| EF-03 | Le bus attribue un `orderId` (`CMD-AAAA-NNNN`) et un `correlationId` (UUID) uniques | M | 2 |
| EF-04 | La commande est publiée dans RabbitMQ (`sidam.orders.incoming`) puis le bus répond `202 Accepted` | M | 2 |
| EF-05 | Le bus importe un fichier CSV de commandes et le convertit en commande canonique | S | 4 |

## Transformation et routage

| ID | Exigence | Prio | Étape |
|---|---|---|---|
| EF-10 | Translator JSON → `CanonicalOrder` | M | 2 |
| EF-11 | Splitter : chaque ligne de commande est vérifiée séparément contre le stock ERP | M | 2 |
| EF-12 | Aggregator : les résultats des lignes sont regroupés en un verdict global | M | 2 |
| EF-13 | Content-Based Router : stock OK → ERP ; stock insuffisant → rejet | M | 2 |
| EF-14 | Translator `CanonicalOrder` → XML au format ERP legacy | M | 2 |
| EF-15 | Le XML envoyé à l'ERP est conservé pour inspection | M | 2 |

## Stock et facturation (ERP)

| ID | Exigence | Prio | Étape |
|---|---|---|---|
| EF-20 | La réservation de stock est **atomique** : toutes les lignes sont réservées ou aucune | M | 2 |
| EF-21 | La réservation est sûre en concurrence (`UPDATE … WHERE available >= qty`) | M | 3 |
| EF-22 | En cas de rupture : statut `REJECTED_OUT_OF_STOCK`, aucun débit, aucune facture | M | 2–3 |
| EF-23 | Une facture est créée pour chaque commande réservée | M | 2 |

## Fiabilité

| ID | Exigence | Prio | Étape |
|---|---|---|---|
| EF-30 | Idempotent Consumer : un même message traité deux fois n'a qu'un seul effet | M | 3 |
| EF-31 | Retry avec backoff pour les erreurs techniques ERP | M | 4 |
| EF-32 | Dead Letter Queue (`sidam.orders.dlq`) pour les messages en échec, avec motif | M | 4 |
| EF-33 | Replay manuel d'un message de la DLQ | M | 4 |
| EF-34 | Simulation de panne ERP activable uniquement en `DEMO_MODE` | S | 4 |
| EF-35 | Un message mal formé est isolé sans effet métier | M | 4 |

## Livraison

| ID | Exigence | Prio | Étape |
|---|---|---|---|
| EF-40 | Création automatique d'une mission de livraison après facturation | S | 5 |
| EF-41 | Le livreur peut prendre en charge et confirmer une mission | S | 5 |
| EF-42 | Le statut `DELIVERED` est propagé à toutes les vues | S | 5 |

## Traçabilité et supervision

| ID | Exigence | Prio | Étape |
|---|---|---|---|
| EF-50 | Chaque étape écrit un événement dans `bus.integration_event` | M | 2 |
| EF-51 | `GET /api/orders/{id}` renvoie la commande et son statut | M | 2 |
| EF-52 | `GET /api/orders/{id}/events` renvoie la timeline de la commande | M | 2 |
| EF-53 | Les événements sont poussés en temps réel (WebSocket) vers Control Tower | M | 6 |
| EF-54 | Control Tower affiche flux, journal, inspecteur de messages, DLQ, métriques réelles | M | 6 |
| EF-55 | `POST /api/demo/reset` remet le jeu de données à zéro (démo uniquement) | S | 2 |
