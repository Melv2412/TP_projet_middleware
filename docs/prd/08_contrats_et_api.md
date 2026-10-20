# 08 — Contrats de messages et API

## API REST du bus

| Méthode | Chemin | Rôle | Étape |
|---|---|---|---|
| `POST` | `/api/orders` | Le Store soumet une commande JSON | 2 |
| `GET` | `/api/orders` | Liste des commandes (plus récentes d'abord) | 2 |
| `GET` | `/api/orders/{orderId}` | Commande + statut | 2 |
| `GET` | `/api/orders/{orderId}/events` | Timeline d'intégration | 2 |
| `GET` | `/api/erp/inventory` | Stock ERP courant | 2 |
| `GET` | `/api/erp/orders/{ref}` | Commande vue par l'ERP (dont XML reçu) | 2 |
| `POST` | `/api/demo/reset` | Remise à zéro du jeu de démo | 2 |
| `POST` | `/api/demo/orders/{orderId}/duplicate` | Renvoie le même message dans RabbitMQ (test d'idempotence) | 3 |
| `POST` | `/api/demo/stock/{sku}?available=N` | Prépare un stock de test | 3 |
| `POST` | `/api/orders/import-csv` (corps `text/csv`) | Import CSV | 4 |
| `GET` | `/api/dlq` | Messages en DLQ + profondeur de la file RabbitMQ | 4 |
| `POST` | `/api/dlq/{dlqId}/replay` | Rejeu d'un message (409 si déjà rejoué) | 4 |
| `POST` | `/api/demo/erp/down` · `/api/demo/erp/up` · `GET /api/demo/erp` | Simuler une panne ERP | 4 |
| `POST` | `/api/demo/corrupt-message?type=malformed\|incomplete` | Injecter un message invalide dans RabbitMQ | 4 |
| `GET` | `/api/catalog` | Catalogue du Store (prix + stock, via le bus ; 503 si ERP en panne) | 6 |
| `GET` | `/api/erp/orders` | Commandes reçues par l'ERP (XML + facture) | 6 |
| `GET` | `/api/tower/metrics` · `/api/tower/health` · `/api/tower/events?limit=N` | Control Tower : KPI réels, santé (BDD, RabbitMQ, files, routes Camel), derniers événements | 6 |
| `WS` | `/ws/events` | Chaque événement d'intégration, poussé en temps réel (JSON) | 6 |
| `GET` | `/api/deliveries[?status=ASSIGNED]` · `/api/deliveries/{missionId}` · `/api/deliveries/by-order/{orderId}` | Missions de l'app livreur | 5 |
| `POST` | `/api/deliveries/{missionId}/pickup` (corps optionnel `{"driver":"Koffi"}`) | Prise en charge (409 si pas ASSIGNED) | 5 |
| `POST` | `/api/deliveries/{missionId}/confirm` | Livraison confirmée (409 si pas IN_DELIVERY) | 5 |

## Contrat JSON entrant (Store → bus)

```json
{
  "customerId": "CLI-001",
  "shippingAddress": "Cocody, Abidjan",
  "items": [
    { "sku": "TV-SAM-55", "quantity": 1 },
    { "sku": "FR-LG-01",  "quantity": 1 }
  ]
}
```

Réponse `202 Accepted` :

```json
{ "orderId": "CMD-2026-0001", "correlationId": "1b6f…", "status": "RECEIVED" }
```

Erreur `400` si `items` vide, `sku` manquant ou `quantity <= 0`.

## Modèle canonique (interne au bus)

```json
{
  "orderId": "CMD-2026-0001",
  "correlationId": "1b6f…",
  "customerId": "CLI-001",
  "shippingAddress": "Cocody, Abidjan",
  "items": [ { "sku": "TV-SAM-55", "quantity": 1, "unitPrice": 350000 } ],
  "total": 775000,
  "createdAt": "2026-10-20T16:11:08Z"
}
```

## Contrat XML ERP legacy (bus → ERP)

```xml
<commande>
  <ref>CMD-2026-0001</ref>
  <client>CLI-001</client>
  <adresseLivraison>Cocody, Abidjan</adresseLivraison>
  <lignes>
    <ligne><article>TV-SAM-55</article><qte>1</qte><prixUnitaire>350000</prixUnitaire></ligne>
    <ligne><article>FR-LG-01</article><qte>1</qte><prixUnitaire>425000</prixUnitaire></ligne>
  </lignes>
  <montantTotal>775000</montantTotal>
</commande>
```

## Contrats livraison (étape 5)

Mission (bus → app livreur, file `sidam.delivery.assigned`) :

```json
{"orderId":"CMD-2026-0001","correlationId":"…","client":"CLI-001","adresse":"Cocody, Abidjan",
 "colis":[{"article":"TV-SAM-55","designation":"Téléviseur Samsung 55 pouces","quantite":1}],"montant":775000}
```

Statut (app livreur → bus, file `sidam.delivery.status`) — `status` ∈ `ASSIGNED`, `IN_DELIVERY`, `DELIVERED` :

```json
{"missionId":"LIV-2026-0001","orderId":"CMD-2026-0001","correlationId":"…","status":"DELIVERED","driver":"Koffi","at":"2026-10-20T16:00:00Z"}
```

Avis de livraison (bus → ERP legacy, XML) :

```xml
<livraison><ref>CMD-2026-0001</ref><mission>LIV-2026-0001</mission><statut>LIVREE</statut>
  <livreur>Koffi</livreur><dateLivraison>2026-10-20T16:00:00Z</dateLivraison></livraison>
```

## Contrat CSV historique (étape 4)

```csv
order_id,sku,quantity,unit_price
CMD-2026-0042,TV-SAM-55,1,350000
CMD-2026-0042,FR-LG-01,1,425000
```

## Topologie RabbitMQ

| Élément | Nom | Rôle |
|---|---|---|
| Exchange (direct) | `sidam.exchange` | Point d'entrée des messages |
| File | `sidam.orders.incoming` (routing key `order.submitted`) | Commandes à traiter |
| Exchange (direct) | `sidam.dlx` (routing key `order.dead`) | Échecs |
| File durable | `sidam.orders.dlq` | Dead Letter Queue (en-têtes `dlqId`, `errorCode`, `errorReason`) |
| File durable | `sidam.delivery.assigned` (routing key `delivery.assigned`) | Missions pour l'app livreur |
| File durable | `sidam.delivery.status` (routing key `delivery.status`) | Statuts renvoyés par l'app livreur |

En-têtes AMQP transportés : `orderId`, `correlationId`.
