# 10 — Plan de tests

## Cas de test fonctionnels

| ID | Précondition | Action | Résultat attendu | Étape |
|---|---|---|---|---|
| T00 | Docker lancé | `docker compose up --build` | 3 conteneurs démarrés, `/actuator/health` = `UP` | 0 |
| T01 | TV=10, FR=6 | Commander TV×1 + FR×1 | `INVOICED`, TV=9, FR=5, XML ERP + facture 775 000 | 2 |
| T02 | LAVE=2 | Commander LAVE×5 | `REJECTED_OUT_OF_STOCK`, LAVE=2, aucune facture | 2–3 |
| T03 | ERP coupé | Passer commande | Retry puis DLQ | 4 |
| T04 | T03 en DLQ | Rétablir l'ERP et rejouer | Commande traitée une seule fois | 4 |
| T05 | T01 validée | Confirmer la livraison | `DELIVERED` partout | 5 |
| T06 | Message connu | Envoyer deux fois le même message | Un seul effet métier | 3 |
| T07 | CSV valide | Importer le CSV | Commande canonique créée | 4 |
| T08 | Message invalide | Envoyer un message mal formé | Erreur expliquée, isolée, aucun effet | 4 |
| T09 | Stock = 3 | 10 commandes simultanées ×1 (3 consommateurs en parallèle) | 3 acceptées, 7 refusées, stock 0 | 3 |
| T10 | — | `POST /api/orders` avec `items` vide | `400 Bad Request` | 2 |
| T11 | — | Commande d'un SKU inconnu | `REJECTED_OUT_OF_STOCK` (motif : article inconnu) | 2 |

## Tests automatisés

| Type | Outil | Portée |
|---|---|---|
| Unitaires | JUnit 5 | Translator JSON → Canonical → XML, stratégie d'agrégation |
| Concurrence | JUnit 5 + PostgreSQL Docker (`LegacyErpAdapterConcurrencyTest`) | 20 réservations au même instant sur un stock de 3 → exactement 3 acceptées (ignoré si la base n'est pas lancée) |
| Intégration | Script `scripts/test-etape2.ps1` contre l'environnement Docker | T00, T01, T02, T10, T11 |
| Intégration | Script `scripts/test-etape3.ps1` | T06 (doublon via le bus et via RabbitMQ), T09 |
| Intégration | Script `scripts/test-etape4-resilience.ps1` | T03 (+ reprise automatique), T04, T07, T08 |
| Intégration | Script `scripts/test-etape5.ps1` | T05 (+ statuts en double / en retard, rupture sans mission, panne ERP à la livraison) |
| Intégration | Script `scripts/test-etape6.ps1` | Pages servies hors ligne, WebSocket temps réel, métriques réelles, panne ERP vue par le Store |
| Journal | JUnit 5 + PostgreSQL Docker (`EventLogTest`) | Écriture réelle + diffusion d'un événement |

**Bilan au 20/10/2026 :** 10 tests unitaires et 107 vérifications d'intégration, toutes au vert.

## Critères d'acceptation globaux

- Tous les événements d'une commande partagent un `correlationId`.
- Une rupture de stock arrête le workflow (ni facture ni mission).
- Une panne ne crée ni double réservation ni double facture.
- JSON, XML et CSV sont transformés et visibles.
- Les écrans sont alimentés par des événements backend réels.
