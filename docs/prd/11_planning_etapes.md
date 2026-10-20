# 11 — Planning par étapes

Modèle **en cascade avec validation à chaque phase** : une étape n'est commencée que lorsque la précédente est livrée **et testée**. À la fin de chaque étape, le projet fonctionne.

| Étape | Contenu | Critère de validation | État |
|---|---|---|---|
| **0. Squelette** | Projet Spring Boot + Camel, Dockerfile, `docker-compose.yml` (PostgreSQL, RabbitMQ, bus) | T00 | ✅ |
| **1. Base de données** | Migrations Flyway : schémas `erp` et `bus`, jeu de données | Tables et stock visibles | ✅ |
| **2. Flux nominal** | API commande → RabbitMQ → Camel (Translator, Splitter, Aggregator, Router) → XML ERP → réservation + facture | T01, T02, T10, T11 | ✅ |
| **3. Règles métier** | Idempotent Consumer, concurrence sur le stock | T06, T09 | ✅ |
| **4. Résilience** | Retry, DLQ, Replay, panne ERP simulée, CSV, messages invalides | T03, T04, T07, T08 | ✅ |
| **5. Livraison** | Missions livreur, confirmation, propagation `DELIVERED` | T05 | ✅ |
| **6. Interfaces** | Control Tower temps réel, Store, ERP, Delivery | Démo complète sans terminal | ✅ |
| **7. Finition** | README, répétition de la démo (le PowerPoint est réalisé par un autre membre de l'équipe) | Démo < 10 min | ⏳ |

## Priorité si le temps manque

**Camel fonctionnel + stock fiable + traces réelles > interfaces parfaites.**
