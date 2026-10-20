# SIDAM CONNECT — Bus d'intégration

Bus d'intégration (Apache Camel + Spring Boot) reliant le site e-commerce (JSON), l'ERP legacy (XML / CSV) et l'application livreur de SIDAM Group, sans remplacer l'ERP.

Documentation produit : [docs/prd/README.md](docs/prd/README.md).

## Stack

Java 17 · Spring Boot 3.5 · Apache Camel 4.18 LTS · RabbitMQ 3 · PostgreSQL 15 · Docker Compose

## Lancer le projet

Seul Docker Desktop est nécessaire.

```powershell
docker compose up -d --build
```

Au premier lancement, compter **3 à 5 minutes** : RabbitMQ et le bus sont lents à démarrer sur un PC portable.
Le bus est prêt quand http://localhost:8080/actuator/health affiche `"status":"UP"`.

| Service | Adresse |
|---|---|
| Accueil (liens vers les 4 écrans) | http://localhost:8080 |
| Control Tower | http://localhost:8080/tower.html |
| SIDAM Store | http://localhost:8080/store.html |
| SIDAM Legacy ERP | http://localhost:8080/erp.html |
| SIDAM Delivery (mobile) | http://localhost:8080/delivery.html |
| Console RabbitMQ | http://localhost:15672 (sidam / sidam) |
| PostgreSQL | localhost:5432, base `sidam_db` (admin / password) |

Arrêter : `docker compose down` (ajouter `-v` pour effacer aussi les données).

### Le jour de la soutenance (même sans Internet)

1. **La veille, avec Internet** : `docker compose up -d --build` une dernière fois, puis vérifier les écrans.
2. **Le jour J** : `docker compose up -d` — **sans `--build`**. Les images (PostgreSQL, RabbitMQ, bus) sont déjà sur le PC : aucune connexion n'est nécessaire. Les écrans n'utilisent aucun CDN (Tailwind est inclus dans le projet).
3. Lancer 5 minutes avant de passer (RabbitMQ et le bus sont lents à démarrer), puis cliquer sur « Réinitialiser la démo » dans la Control Tower.

Pour ouvrir l'écran livreur sur un téléphone : même Wi-Fi que le PC, puis `http://<adresse-IP-du-PC>:8080/delivery.html` (autoriser le port 8080 dans le pare-feu Windows si besoin).

## Tester

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-etape2.ps1   # flux nominal, rupture, validation
powershell -ExecutionPolicy Bypass -File scripts\test-etape3.ps1   # doublons, commandes simultanées
powershell -ExecutionPolicy Bypass -File scripts\test-etape4-resilience.ps1   # panne ERP, retry, DLQ, replay, CSV
powershell -ExecutionPolicy Bypass -File scripts\test-etape5.ps1   # livraison jusqu'à DELIVERED
powershell -ExecutionPolicy Bypass -File scripts\test-etape6.ps1   # écrans, WebSocket temps réel, métriques
```

> **Avast** peut mettre en quarantaine des scripts de test (appels réseau + identifiants). Si un script disparaît,
> le restaurer depuis la quarantaine et ajouter le dossier du projet aux exceptions d'Avast.

Exemple de commande à la main :

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/orders -ContentType "application/json" `
  -Body '{"customerId":"CLI-001","items":[{"sku":"TV-SAM-55","quantity":1},{"sku":"FR-LG-01","quantity":1}]}'

Invoke-RestMethod http://localhost:8080/api/orders/CMD-2026-0001/events
```

Faire avancer la livraison d'une commande facturée :

```powershell
$m = Invoke-RestMethod http://localhost:8080/api/deliveries/by-order/CMD-2026-0001
Invoke-RestMethod -Method Post "http://localhost:8080/api/deliveries/$($m.mission_id)/pickup" `
  -ContentType "application/json" -Body '{"driver":"Koffi"}'
Invoke-RestMethod -Method Post "http://localhost:8080/api/deliveries/$($m.mission_id)/confirm"
```

Simuler une panne de l'ERP puis rejouer depuis la DLQ :

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/demo/erp/down   # couper l'ERP
# ... passer une commande : 3 tentatives (2 s, 4 s, 8 s) puis DLQ
Invoke-RestMethod -Method Post http://localhost:8080/api/demo/erp/up     # rétablir
(Invoke-RestMethod http://localhost:8080/api/dlq).messages               # repérer le dlq_id
Invoke-RestMethod -Method Post http://localhost:8080/api/dlq/<dlq_id>/replay
```

Tests unitaires (sans Docker) :

```powershell
cd integration-bus
$env:MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"   # nécessaire si Avast analyse le HTTPS
.\mvnw.cmd test
```

## Structure

```
docker-compose.yml          PostgreSQL + RabbitMQ + bus
docs/prd/                   PRD découpé (contexte, user flows, exigences, architecture, tests…)
integration-bus/            Application Spring Boot + Camel
  src/main/java/com/sidam/connect/
    api/        Contrôleurs REST (commandes, ERP, démo)
    route/      Routes Camel (Translator, Splitter, Aggregator, Router, Idempotent Consumer,
                Retry, Dead Letter Channel, import CSV)
    dlq/        Index de la DLQ et Replay
    delivery/   App livreur simulée (système externe, ne parle au bus que par RabbitMQ)
    erp/        Adaptateur ERP legacy (XML, réservation atomique, facture)
    translate/  Conversions JSON ↔ canonique ↔ XML
    audit/      Journal des événements d'intégration
    model/      Messages et modèle canonique
  src/main/resources/db/migration/   Schémas erp / bus + données de démo (Flyway)
scripts/test-etape*.ps1     Tests d'intégration par étape
```

## Avancement

Étapes 0 à 6 terminées et testées : squelette, base de données, flux nominal, idempotence et concurrence, résilience (retry, DLQ, replay, CSV), livraison, interfaces web temps réel. Captures d'écran dans [docs/captures/](docs/captures/). Prochaine étape : répétition de la démo (le support de présentation est préparé par un autre membre de l'équipe). Voir [docs/prd/11_planning_etapes.md](docs/prd/11_planning_etapes.md).
