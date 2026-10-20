# SIDAM CONNECT — Workflow en terminal (sans interface)

Ce guide permet de configurer le projet puis de dérouler **tout le scénario de démonstration uniquement en ligne de commande**, sans ouvrir les écrans web. Chaque commande est suivie de **ce qu'elle fait** et du **résultat attendu**.

> Pour la démo avec les écrans (Control Tower, Store, ERP, Delivery), voir [README.md](README.md).

Toutes les commandes sont écrites pour **PowerShell** (terminal par défaut de Windows et de VS Code).

---

## Sommaire

1. [Configuration (à faire une seule fois)](#1-configuration-à-faire-une-seule-fois)
2. [Démarrer et vérifier](#2-démarrer-et-vérifier)
3. [Scénario 1 — Commande nominale jusqu'à la livraison](#3-scénario-1--commande-nominale-jusquà-la-livraison)
4. [Scénario 2 — Rupture de stock](#4-scénario-2--rupture-de-stock)
5. [Scénario 3 — Panne de l'ERP, DLQ et Replay](#5-scénario-3--panne-de-lerp-dlq-et-replay)
6. [Scénario 4 — Messages invalides et doublons](#6-scénario-4--messages-invalides-et-doublons)
7. [Scénario 5 — Import CSV (format historique)](#7-scénario-5--import-csv-format-historique)
8. [Superviser : métriques, files RabbitMQ, base de données, logs](#8-superviser--métriques-files-rabbitmq-base-de-données-logs)
9. [Tests automatiques](#9-tests-automatiques)
10. [Arrêter et nettoyer](#10-arrêter-et-nettoyer)
11. [Dépannage](#11-dépannage)
12. [Référence de l'API](#12-référence-de-lapi)

---

## 1. Configuration (à faire une seule fois)

### 1.1 Prérequis

| Outil | Obligatoire ? | Pourquoi |
|---|---|---|
| **Windows 10/11** avec **WSL 2** | Oui | Docker Desktop s'appuie sur WSL 2 |
| **Docker Desktop** (avec Docker Compose) | Oui | Fait tourner PostgreSQL, RabbitMQ et le bus. **Rien d'autre à installer pour lancer le projet.** |
| **Git** | Oui | Récupérer le projet |
| **8 Go de RAM** recommandés | — | Les 3 conteneurs utilisent environ 500 Mo, mais Docker Desktop lui-même est gourmand |
| Java 17 (JDK) | Non | Uniquement pour lancer les **tests unitaires** hors Docker (§ 9.2) |

Vérifier les installations :

```powershell
docker --version
docker compose version
git --version
```

**Ce que ça fait :** affiche la version de chaque outil.
**Résultat attendu :** trois lignes de version, par exemple `Docker version 29.x`, `Docker Compose version v2.x ou plus`, `git version 2.x`. Si une commande est « introuvable », installer l'outil correspondant.

> **Docker Desktop doit être démarré** (icône de la baleine dans la barre des tâches, état « Engine running ») avant toute commande `docker`.

### 1.2 Récupérer le projet

```powershell
git clone https://github.com/Melv2412/TP_projet_middleware.git
cd TP_projet_middleware
```

**Ce que ça fait :** télécharge le code et se place dans le dossier du projet.
**Résultat attendu :** un dossier `TP_projet_middleware` contenant `docker-compose.yml`, `integration-bus/`, `docs/`, `scripts/`.

> **Toutes les commandes suivantes se lancent depuis ce dossier.**

### 1.3 Vérifier que les ports sont libres

Le projet utilise 4 ports sur le PC :

| Port | Service |
|---|---|
| 8080 | Bus d'intégration (API + écrans) |
| 5432 | PostgreSQL |
| 5672 | RabbitMQ (messages) |
| 15672 | Console web de RabbitMQ |

```powershell
Get-NetTCPConnection -LocalPort 5432,5672,15672,8080 -State Listen -ErrorAction SilentlyContinue |
  Select-Object LocalPort, @{n='Programme';e={(Get-Process -Id $_.OwningProcess).ProcessName}}
```

**Ce que ça fait :** liste les programmes qui occupent déjà ces ports.
**Résultat attendu :** **aucune ligne** avant le premier lancement. Après le lancement du projet, les 4 ports apparaissent avec le programme `wslrelay` (c'est Docker).

Si un port est pris par un autre programme (souvent un PostgreSQL installé sur le PC pour le port 5432) : arrêter ce programme, **ou** modifier la partie gauche du port dans `docker-compose.yml`, par exemple `"5433:5432"` au lieu de `"5432:5432"`. La partie droite ne change jamais.

### 1.4 Antivirus (Avast et autres)

Certains antivirus inspectent le trafic HTTPS et peuvent :
- mettre en quarantaine les scripts de test (`scripts/*.ps1`) ;
- empêcher Maven de télécharger ses dépendances lors des tests unitaires (erreur `PKIX path building failed`).

**À faire :** ajouter le dossier du projet aux exceptions de l'antivirus. La construction Docker, elle, n'est pas concernée.

### 1.5 Autoriser l'exécution des scripts PowerShell (optionnel)

Uniquement pour les tests automatiques du § 9 :

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

**Ce que ça fait :** autorise l'exécution des scripts `.ps1` locaux pour votre utilisateur.
**Résultat attendu :** aucune sortie. (Alternative sans rien modifier : lancer chaque script avec `powershell -ExecutionPolicy Bypass -File ...`, comme au § 9.)

### 1.6 Configuration par défaut

Aucun fichier `.env` n'est nécessaire : toute la configuration est dans `docker-compose.yml`.

| Paramètre | Valeur |
|---|---|
| Base PostgreSQL | `sidam_db`, utilisateur `admin`, mot de passe `password` |
| RabbitMQ | utilisateur `sidam`, mot de passe `sidam` |
| `DEMO_MODE` | `true` (active les outils de démonstration `/api/demo/...`) |

Ce sont des identifiants **de démonstration uniquement**, à ne jamais réutiliser en production.

---

## 2. Démarrer et vérifier

### 2.1 Construire et démarrer

```powershell
docker compose up -d --build
```

**Ce que ça fait :** construit l'image du bus (compilation Java dans Docker), puis démarre 3 conteneurs en arrière-plan : `sidam_postgres`, `sidam_rabbitmq`, `sidam_bus`. Au premier démarrage, Flyway crée automatiquement les tables et charge le stock de démonstration.
**Résultat attendu :** des lignes `Container sidam_... Started`, puis le retour au terminal.

**Durée :**
- **premier lancement : 10 à 15 minutes** (téléchargement des images et des dépendances Java) ;
- lancements suivants : **3 à 5 minutes** (RabbitMQ puis le bus sont lents à démarrer sur un PC portable) ;
- `--build` n'est utile que si le code a changé. Sans Internet, utiliser `docker compose up -d`.

### 2.2 Voir l'état des conteneurs

```powershell
docker compose ps
```

**Ce que ça fait :** affiche les 3 conteneurs et leur état.
**Résultat attendu :**

```
NAME             STATUS
sidam_bus        Up 2 minutes
sidam_postgres   Up 5 minutes (healthy)
sidam_rabbitmq   Up 5 minutes (healthy)
```

`sidam_bus` n'apparaît qu'une fois PostgreSQL et RabbitMQ `healthy`. Pendant l'attente, RabbitMQ affiche `(health: starting)` : c'est normal.

### 2.3 Attendre que le bus soit prêt

```powershell
while ((curl.exe -s http://localhost:8080/actuator/health) -notmatch '"status":"UP"') { Write-Host "En attente du bus..."; Start-Sleep 5 }; Write-Host "Bus prêt."
```

**Ce que ça fait :** interroge le bus toutes les 5 secondes jusqu'à ce qu'il réponde « UP ».
**Résultat attendu :** quelques lignes `En attente du bus...`, puis `Bus prêt.`

### 2.4 Contrôler la santé de chaque composant

```powershell
$b = "http://localhost:8080"
$h = Invoke-RestMethod "$b/api/tower/health"
"PostgreSQL=$($h.database)  RabbitMQ=$($h.rabbitmq)  ERP=$($h.erpAvailable)  Routes Camel=$($h.camelRoutes.Count)"
$h.queues
```

**Ce que ça fait :** demande au bus l'état de la base, du broker, de l'ERP et des routes Camel, puis le nombre de messages en attente dans chaque file.
**Résultat attendu :**

```
PostgreSQL=True  RabbitMQ=True  ERP=True  Routes Camel=10

sidam.orders.incoming   : 0
sidam.delivery.assigned : 0
sidam.delivery.status   : 0
sidam.orders.dlq        : 0
```

> **Important :** la variable `$b` est réutilisée dans toutes les commandes suivantes. Si vous ouvrez un nouveau terminal, refaites `$b = "http://localhost:8080"`.

### 2.5 Repartir d'un jeu de données propre

```powershell
Invoke-RestMethod -Method Post "$b/api/demo/reset"
```

**Ce que ça fait :** remet le stock initial et efface commandes, factures, missions, journal et DLQ. La numérotation des commandes repart à `CMD-2026-0001`.
**Résultat attendu :** `status : Jeu de démonstration réinitialisé`

### 2.6 Consulter le catalogue

```powershell
Invoke-RestMethod "$b/api/catalog" | Format-Table sku, name, price, available
```

**Ce que ça fait :** affiche le catalogue tel que le Store le voit (prix et stock fournis par l'ERP, via le bus).
**Résultat attendu :**

```
sku       name                         price  available
---       ----                         -----  ---------
FR-LG-01  Réfrigérateur LG             425000         6
LAVE-01   Machine à laver              280000         2
TV-SAM-55 Téléviseur Samsung 55 pouces 350000        10
```

---

## 3. Scénario 1 — Commande nominale jusqu'à la livraison

> Les numéros `CMD-2026-0001`, `LIV-2026-0001`… ci-dessous supposent un reset juste avant (§ 2.5). Les commandes stockent les numéros dans des variables (`$o`, `$m`), vous n'avez rien à recopier.

### 3.1 Passer une commande (TV + frigo)

```powershell
$o = Invoke-RestMethod -Method Post "$b/api/orders" -ContentType "application/json" `
  -Body '{"customerId":"CLI-001","shippingAddress":"Cocody, Abidjan","items":[{"sku":"TV-SAM-55","quantity":1},{"sku":"FR-LG-01","quantity":1}]}'
$o
```

**Ce que ça fait :** joue le rôle du Store. La commande JSON est validée, enregistrée, puis publiée dans la file RabbitMQ `sidam.orders.incoming`. La réponse arrive immédiatement (HTTP 202) : le traitement continue en arrière-plan.
**Résultat attendu :**

```
status   correlationId                         orderId
------   -------------                         -------
RECEIVED 08c1e1e9-e541-4bed-b12b-3a16547a6be7  CMD-2026-0001
```

### 3.2 Suivre l'état de la commande

```powershell
Start-Sleep 3
$g = Invoke-RestMethod "$b/api/orders/$($o.orderId)"; "$($g.order_id)  $($g.status)  total=$($g.total) FCFA"
```

**Ce que ça fait :** lit l'état courant de la commande dans le bus.
**Résultat attendu :** `CMD-2026-0001  DELIVERY_ASSIGNED  total=775000 FCFA`
La commande a été vérifiée, facturée, et une mission a été envoyée au livreur.

### 3.3 Voir chaque étape traversée (journal d'intégration)

```powershell
Invoke-RestMethod "$b/api/orders/$($o.orderId)/events" | Format-Table status, pattern, message -AutoSize -Wrap
```

**Ce que ça fait :** affiche le journal de la commande : chaque étape, le pattern EIP appliqué et un détail.
**Résultat attendu (12 lignes) :**

```
status             pattern                                message
------             -------                                -------
RECEIVED           Correlation Identifier                 Commande reçue du Store (2 article(s))
PUBLISHED          Message Channel                        Commande publiée dans la file sidam.orders.incoming
TRANSFORMED        Message Translator + Content Enricher  JSON Store → modèle canonique (prix ERP ajoutés, total 775000 FCFA)
STOCK_LINE_CHECKED Splitter                               TV-SAM-55 : demandé 1, disponible 10 → OK
STOCK_LINE_CHECKED Splitter                               FR-LG-01 : demandé 1, disponible 6 → OK
STOCK_AGGREGATED   Aggregator                             2 ligne(s) vérifiée(s) : stock disponible
ROUTED             Content-Based Router                   Stock disponible → envoi à l'ERP
TRANSFORMED        Message Translator                     Modèle canonique → XML ERP legacy
STOCK_RESERVED     Request-Reply                          Stock réservé dans l'ERP (transaction atomique)
INVOICED           Request-Reply                          Facture FAC-2026-0001 émise : 775000 FCFA
DISPATCHED         Message Translator + Process Manager   Mission de livraison envoyée à SIDAM Delivery
DELIVERY_ASSIGNED  Content-Based Router + Process Manager Mission LIV-2026-0001 créée dans SIDAM Delivery
```

Pour voir aussi le contenu des messages (JSON, XML) : `Invoke-RestMethod "$b/api/orders/$($o.orderId)/events" | Select-Object status, payload_format, payload`

### 3.4 Vérifier côté ERP : XML reçu, facture, stock

```powershell
$e = Invoke-RestMethod "$b/api/erp/orders/$($o.orderId)"
"$($e.status)  facture $($e.invoice.invoice_no)  $($e.invoice.total) FCFA"
$e.xml_payload
Invoke-RestMethod "$b/api/erp/inventory" | Format-Table sku, available, reserved
```

**Ce que ça fait :** consulte la commande telle que l'ERP legacy l'a reçue, puis l'inventaire.
**Résultat attendu :**

```
INVOICED  facture FAC-2026-0001  775000 FCFA
<commande><ref>CMD-2026-0001</ref><client>CLI-001</client><adresseLivraison>Cocody, Abidjan</adresseLivraison><lignes><ligne><article>TV-SAM-55</article><qte>1</qte><prixUnitaire>350000</prixUnitaire></ligne><ligne><article>FR-LG-01</article><qte>1</qte><prixUnitaire>425000</prixUnitaire></ligne></lignes><montantTotal>775000</montantTotal></commande>

sku       available reserved
---       --------- --------
FR-LG-01          5        1
LAVE-01           2        0
TV-SAM-55         9        1
```

La commande JSON du Store est arrivée **en XML** dans l'ERP. Le stock est passé de 10 à 9 (TV) et de 6 à 5 (frigo), et les articles vendus sont marqués « réservés » en attendant la livraison.

### 3.5 Récupérer la mission du livreur

```powershell
$m = Invoke-RestMethod "$b/api/deliveries/by-order/$($o.orderId)"
"$($m.mission_id)  $($m.status)  $($m.address)"
```

**Ce que ça fait :** joue le rôle de l'app livreur et lit la mission que le bus lui a transmise (par RabbitMQ, en JSON).
**Résultat attendu :** `LIV-2026-0001  ASSIGNED  Cocody, Abidjan`

### 3.6 Le livreur prend en charge la mission

```powershell
Invoke-RestMethod -Method Post "$b/api/deliveries/$($m.mission_id)/pickup" -ContentType "application/json" -Body '{"driver":"Koffi"}' |
  Select-Object mission_id, status, driver
```

**Ce que ça fait :** l'app livreur passe la mission « en cours » et renvoie ce statut au bus par la file `sidam.delivery.status`.
**Résultat attendu :** `LIV-2026-0001  IN_DELIVERY  Koffi`

### 3.7 Le livreur confirme la livraison

```powershell
Invoke-RestMethod -Method Post "$b/api/deliveries/$($m.mission_id)/confirm" | Select-Object mission_id, status, delivered_at
Start-Sleep 3
"Bus : "  + (Invoke-RestMethod "$b/api/orders/$($o.orderId)").status
"ERP : "  + (Invoke-RestMethod "$b/api/erp/orders/$($o.orderId)").status
Invoke-RestMethod "$b/api/erp/inventory" | Format-Table sku, available, reserved
```

**Ce que ça fait :** confirme la livraison. Le bus reçoit le statut, le traduit en XML `<livraison>` pour l'ERP, et l'ERP sort la marchandise réservée du stock.
**Résultat attendu :**

```
LIV-2026-0001  DELIVERED  2026-10-20T23:04:25...
Bus : DELIVERED
ERP : DELIVERED

sku       available reserved
---       --------- --------
FR-LG-01          5        0
LAVE-01           2        0
TV-SAM-55         9        0
```

Le journal de la commande (§ 3.3) se termine maintenant par `DELIVERED` puis `WORKFLOW_COMPLETED`, avec la durée totale du parcours.

Essayer de confirmer une deuxième fois :

```powershell
curl.exe -s -w "  [HTTP %{http_code}]`n" -X POST "$b/api/deliveries/$($m.mission_id)/confirm"
```

**Résultat attendu :** un message d'erreur suivi de `[HTTP 409]`. Une livraison ne peut pas être confirmée deux fois.

---

## 4. Scénario 2 — Rupture de stock

```powershell
$r = Invoke-RestMethod -Method Post "$b/api/orders" -ContentType "application/json" `
  -Body '{"customerId":"CLI-002","shippingAddress":"Yopougon, Abidjan","items":[{"sku":"LAVE-01","quantity":5}]}'
Start-Sleep 3
$rr = Invoke-RestMethod "$b/api/orders/$($r.orderId)"; "$($rr.status) | $($rr.status_reason)"
curl.exe -s -o NUL -w "Commande dans l'ERP : HTTP %{http_code}`n" "$b/api/erp/orders/$($r.orderId)"
curl.exe -s -o NUL -w "Mission livreur : HTTP %{http_code}`n" "$b/api/deliveries/by-order/$($r.orderId)"
```

**Ce que ça fait :** commande 5 machines à laver alors qu'il n'en reste que 2, puis vérifie que rien n'a été créé dans l'ERP ni chez le livreur.
**Résultat attendu :**

```
REJECTED_OUT_OF_STOCK | LAVE-01 : demandé 5, disponible 2
Commande dans l'ERP : HTTP 404
Mission livreur : HTTP 404
```

Le **Content-Based Router** a envoyé la commande vers le canal de rejet : aucun stock débité, aucune facture, aucune mission.

### Valider une commande mal formée (vérification à l'entrée)

```powershell
curl.exe -s -w "  [HTTP %{http_code}]`n" -X POST "$b/api/orders" -H "Content-Type: application/json" -d '{\"items\":[]}'
```

**Résultat attendu :** `{"error":"La commande doit contenir au moins un article"}  [HTTP 400]`

---

## 5. Scénario 3 — Panne de l'ERP, DLQ et Replay

### 5.1 Couper l'ERP

```powershell
Invoke-RestMethod -Method Post "$b/api/demo/erp/down"
```

**Ce que ça fait :** simule une panne de l'ERP legacy (mode démo).
**Résultat attendu :** `erpAvailable : False`

### 5.2 Passer une commande pendant la panne

```powershell
$p = Invoke-RestMethod -Method Post "$b/api/orders" -ContentType "application/json" `
  -Body '{"customerId":"CLI-004","shippingAddress":"Marcory, Abidjan","items":[{"sku":"TV-SAM-55","quantity":1}]}'
Start-Sleep 5;  "Pendant les tentatives : " + (Invoke-RestMethod "$b/api/orders/$($p.orderId)").status
Start-Sleep 12; "Après 3 tentatives     : " + (Invoke-RestMethod "$b/api/orders/$($p.orderId)").status
(Invoke-RestMethod "$b/api/orders/$($p.orderId)/events") | Where-Object status -in RETRYING, DEAD_LETTERED | Format-Table status, attempt, message -Wrap
```

**Ce que ça fait :** le bus essaie de joindre l'ERP, échoue, puis **réessaie 3 fois** (après 2 s, 4 s, puis 8 s). Après la 3ᵉ tentative ratée, le message intact est mis de côté dans la **Dead Letter Queue** (`sidam.orders.dlq`). Rien n'est perdu.
**Résultat attendu :**

```
Pendant les tentatives : RETRYING
Après 3 tentatives     : FAILED

status        attempt message
------        ------- -------
RETRYING            1 Tentative 1/3 dans 2.0 s : ERP legacy injoignable (panne simulée)
RETRYING            2 Tentative 2/3 dans 4.0 s : ERP legacy injoignable (panne simulée)
RETRYING            3 Tentative 3/3 dans 8.0 s : ERP legacy injoignable (panne simulée)
DEAD_LETTERED       1 Message isolé dans sidam.orders.dlq (ERP_UNAVAILABLE) : ...
```

> Si l'ERP revient **avant** la fin des 3 tentatives, la commande passe automatiquement, sans aller en DLQ.

### 5.3 Inspecter la DLQ

```powershell
$d = Invoke-RestMethod "$b/api/dlq"
"Messages dans la file RabbitMQ sidam.orders.dlq : $($d.queueDepth)"
$d.messages | Where-Object { -not $_.replayed_at } | Format-Table dlq_id, order_id, error_code, reason -Wrap
```

**Ce que ça fait :** liste les messages en échec, leur motif et leur identifiant `dlq_id`.
**Résultat attendu :**

```
Messages dans la file RabbitMQ sidam.orders.dlq : 1

dlq_id                               order_id      error_code      reason
------                               --------      ----------      ------
b2312d89-a008-465a-9c06-ab3fcbffd8d8 CMD-2026-0003 ERP_UNAVAILABLE ErpUnavailableException : ERP legacy injoignable (panne simulée)
```

### 5.4 Rétablir l'ERP puis rejouer le message

```powershell
Invoke-RestMethod -Method Post "$b/api/demo/erp/up"
$id = ($d.messages | Where-Object { -not $_.replayed_at } | Select-Object -First 1).dlq_id
Invoke-RestMethod -Method Post "$b/api/dlq/$id/replay"
Start-Sleep 4
"Après Replay : " + (Invoke-RestMethod "$b/api/orders/$($p.orderId)").status
"Factures pour cette commande : " + @((Invoke-RestMethod "$b/api/orders/$($p.orderId)/events") | Where-Object status -eq INVOICED).Count
```

**Ce que ça fait :** remet l'ERP en service, retire le message de la DLQ et le republie dans sa file d'origine. Le bus le traite normalement.
**Résultat attendu :**

```
erpAvailable : True
status : Message rejoué
Après Replay : DELIVERY_ASSIGNED
Factures pour cette commande : 1
```

Rejouer une deuxième fois le même message :

```powershell
curl.exe -s -w "  [HTTP %{http_code}]`n" -X POST "$b/api/dlq/$id/replay"
```

**Résultat attendu :** `{"error":"Ce message a déjà été rejoué"}  [HTTP 409]`. La commande ne peut être traitée qu'une fois.

---

## 6. Scénario 4 — Messages invalides et doublons

### 6.1 Injecter un message corrompu directement dans RabbitMQ

```powershell
Invoke-RestMethod -Method Post "$b/api/demo/corrupt-message?type=malformed"    # JSON cassé
Invoke-RestMethod -Method Post "$b/api/demo/corrupt-message?type=incomplete"   # commande sans article
Start-Sleep 3
(Invoke-RestMethod "$b/api/dlq").messages | Where-Object { -not $_.replayed_at } | Format-Table order_id, error_code, reason -Wrap
```

**Ce que ça fait :** dépose des messages invalides dans la file des commandes, comme le ferait un système défaillant.
**Résultat attendu :** deux nouveaux messages en DLQ avec le code `INVALID_PAYLOAD`, isolés **immédiatement** (sans tentative inutile). Le stock n'a pas bougé.

### 6.2 Envoyer deux fois le même message

```powershell
Invoke-RestMethod -Method Post "$b/api/demo/orders/$($o.orderId)/duplicate"
Start-Sleep 3
(Invoke-RestMethod "$b/api/orders/$($o.orderId)/events") | Where-Object status -like "DUPLICATE*" | Format-Table status, pattern, message -Wrap
"Factures : " + @((Invoke-RestMethod "$b/api/orders/$($o.orderId)/events") | Where-Object status -eq INVOICED).Count
```

**Ce que ça fait :** republie dans RabbitMQ exactement le même message que la commande du scénario 1 (simulation d'un doublon réseau).
**Résultat attendu :**

```
status            pattern             message
------            -------             -------
DUPLICATE_SENT    Simulation          Simulation : le même message est renvoyé une seconde fois
DUPLICATE_IGNORED Idempotent Consumer Message déjà traité pour CMD-2026-0001 : ignoré, aucun nouvel effet

Factures : 1
```

### 6.3 Fixer un stock (pour démontrer la concurrence)

```powershell
Invoke-RestMethod -Method Post "$b/api/demo/stock/LAVE-01?available=1"
```

**Ce que ça fait :** règle le stock disponible d'un article.
**Résultat attendu :** `sku : LAVE-01`, `available : 1`. Si deux commandes demandent ensuite cette dernière machine en même temps, une seule est acceptée : la réservation est faite en une seule opération indivisible.

---

## 7. Scénario 5 — Import CSV (format historique)

```powershell
$csv = "order_id,sku,quantity,unit_price`nCMD-2026-0042,TV-SAM-55,1,350000`nCMD-2026-0042,FR-LG-01,1,425000"
$imp = Invoke-RestMethod -Method Post "$b/api/orders/import-csv" -ContentType "text/csv" -Body $csv
$imp.imported
Start-Sleep 3
$c = Invoke-RestMethod "$b/api/orders/$($imp.imported[0].orderId)"; "$($c.order_id)  $($c.status)  total=$($c.total)"
```

**Ce que ça fait :** envoie un fichier CSV au format historique de l'ERP (une ligne par article). Le bus regroupe les lignes par commande, les traduit (CSV → modèle canonique), puis les fait passer dans **le même circuit** qu'une commande du site.
**Résultat attendu :**

```
orderId       csvOrderId
-------       ----------
CMD-2026-0004 CMD-2026-0042

CMD-2026-0004  DELIVERY_ASSIGNED  total=775000
```

Les prix du fichier ne sont pas utilisés tels quels : le total est recalculé avec les prix de l'ERP. Un CSV invalide (mauvais en-tête, quantité non numérique) est refusé en entier avec une erreur 400.

Pour importer un vrai fichier :

```powershell
Invoke-RestMethod -Method Post "$b/api/orders/import-csv" -ContentType "text/csv" -Body (Get-Content .\commandes.csv -Raw)
```

---

## 8. Superviser : métriques, files RabbitMQ, base de données, logs

### 8.1 Métriques calculées sur les vraies données

```powershell
Invoke-RestMethod "$b/api/tower/metrics" | Select-Object total, accepted, rejected, failed, delivered, successRate, avgLatencyMs, events, dlqPending
```

**Ce que ça fait :** affiche les indicateurs de la Control Tower, calculés à partir des commandes et du journal (rien n'est codé en dur).
**Résultat attendu (après les scénarios 1 à 5) :** par exemple `total=4  accepted=3  rejected=1  delivered=1  successRate=100  avgLatencyMs=...  dlqPending=...`

### 8.2 Messages en attente dans RabbitMQ

```powershell
docker exec sidam_rabbitmq rabbitmqctl list_queues name messages --quiet
```

**Ce que ça fait :** demande directement au broker le nombre de messages dans chaque file.
**Résultat attendu :** les 4 files `sidam.*`. En temps normal, toutes sont à 0 sauf `sidam.orders.dlq`, qui contient les messages en échec non rejoués.

Console web équivalente : http://localhost:15672 (identifiant `sidam`, mot de passe `sidam`).

### 8.3 Interroger la base de données

```powershell
docker exec sidam_postgres psql -U admin -d sidam_db -c "SELECT sku, available, reserved FROM erp.inventory ORDER BY sku"
docker exec sidam_postgres psql -U admin -d sidam_db -c "SELECT order_id, status, total FROM bus.orders ORDER BY created_at"
```

**Ce que ça fait :** exécute une requête SQL dans le conteneur PostgreSQL.
**Résultat attendu :** un tableau. Les données de l'ERP simulé sont dans le schéma `erp` (`product`, `inventory`, `erp_order`, `invoice`…). Celles du bus sont dans `bus` (`orders`, `integration_event`, `dead_letter`…). Celles de l'app livreur sont dans `delivery`.

Pour ouvrir une session SQL interactive : `docker exec -it sidam_postgres psql -U admin -d sidam_db` (quitter avec `\q`).

### 8.4 Logs du bus

```powershell
docker logs --tail 50 sidam_bus      # 50 dernières lignes
docker logs -f sidam_bus             # suivi en direct (Ctrl+C pour quitter)
```

**Ce que ça fait :** affiche ce que le bus écrit dans sa console.
**Résultat attendu :** une ligne par événement, par exemple `[CMD-2026-0001] INVOICED | Request-Reply | Facture FAC-2026-0001 émise : 775000 FCFA`. Les tentatives apparaissent en `WARN`. Les erreurs inattendues apparaissent en `ERROR`.

---

## 9. Tests automatiques

### 9.1 Tests d'intégration (le projet doit tourner)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-etape2.ps1              # flux nominal, rupture, validation
powershell -ExecutionPolicy Bypass -File scripts\test-etape3.ps1              # doublons, commandes simultanées
powershell -ExecutionPolicy Bypass -File scripts\test-etape4-resilience.ps1   # panne ERP, retry, DLQ, replay, CSV
powershell -ExecutionPolicy Bypass -File scripts\test-etape5.ps1              # livraison jusqu'à DELIVERED
powershell -ExecutionPolicy Bypass -File scripts\test-etape6.ps1              # écrans, WebSocket, métriques
```

**Ce que ça fait :** chaque script réinitialise les données, joue un scénario de bout en bout et vérifie chaque résultat.
**Résultat attendu :** une liste de lignes `[OK]` en vert, puis `Tous les tests de l'étape N passent.` (107 vérifications au total). Une ligne `[ECHEC]` en rouge indique ce qui ne va pas.

| Script | Durée approximative |
|---|---|
| étapes 2, 3, 6 | 20 à 40 s |
| étapes 4 et 5 | environ 1 min 30 (attente des tentatives) |

> Les scripts **effacent les données de démonstration**. Refaire un reset (§ 2.5) avant une démo.

### 9.2 Tests unitaires (Java 17 requis, Docker non nécessaire)

```powershell
cd integration-bus
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
.\mvnw.cmd test
cd ..
```

**Ce que ça fait :** télécharge Maven si besoin, compile le projet et lance les tests JUnit (traductions JSON/XML, Aggregator, réservation concurrente, journal). La ligne `MAVEN_OPTS` fait utiliser à Java les certificats Windows ; elle est nécessaire si un antivirus inspecte le HTTPS.
**Résultat attendu :** `Tests run: ..., Failures: 0, Errors: 0`, puis `BUILD SUCCESS`. Le premier lancement prend plusieurs minutes (téléchargements).

---

## 10. Arrêter et nettoyer

| Commande | Ce que ça fait | Données |
|---|---|---|
| `docker compose stop` | Met les conteneurs en pause | Conservées |
| `docker compose down` | Arrête et supprime les conteneurs | **Conservées** (volumes) |
| `docker compose down -v` | Arrête tout et **supprime les volumes** | **Effacées** : au prochain démarrage, la base et RabbitMQ repartent de zéro |

**Résultat attendu :** des lignes `Container sidam_... Removed` (ou `Stopped`), puis `docker compose ps` n'affiche plus rien.

---

## 11. Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| `error during connect` / `Cannot connect to the Docker daemon` | Docker Desktop n'est pas lancé | Démarrer Docker Desktop, attendre « Engine running » |
| `dependency failed to start: container sidam_rabbitmq is unhealthy` | RabbitMQ a mis trop de temps à démarrer | Relancer `docker compose up -d` (il reprend là où il en était) |
| `Bind for 0.0.0.0:5432 failed: port is already allocated` | Un autre programme utilise le port | Voir § 1.3 |
| `failed to resolve source metadata` / `context deadline exceeded` pendant le build | Docker Hub ou Maven Central injoignable (réseau instable) | Relancer la commande. Sans Internet, utiliser `docker compose up -d` **sans** `--build` si l'image existe déjà |
| `Invoke-RestMethod : Impossible de se connecter au serveur distant` | Le bus n'a pas fini de démarrer | Attendre (§ 2.3), puis vérifier `docker logs --tail 50 sidam_bus` |
| Statut bloqué en `FAILED` alors que l'ERP est rétabli | Le message est en DLQ | Le rejouer (§ 5.4) |
| Un script `.ps1` a disparu | Quarantaine de l'antivirus | Le restaurer et ajouter le dossier aux exceptions (§ 1.4) |
| `PKIX path building failed` lors de `mvnw test` | Antivirus qui inspecte le HTTPS | Définir `MAVEN_OPTS` comme au § 9.2 |
| Accents mal affichés (`TÃ©lÃ©viseur`) | Ancienne version du bus | Reconstruire : `docker compose up -d --build` |

Voir pourquoi un conteneur s'est arrêté : `docker compose ps -a`, puis `docker logs <nom_du_conteneur>`.

---

## 12. Référence de l'API

Base : `http://localhost:8080`

### Commandes (Store)

| Méthode | Chemin | Rôle | Réponse |
|---|---|---|---|
| POST | `/api/orders` | Passer une commande JSON | 202 `{orderId, correlationId, status}` · 400 si invalide |
| POST | `/api/orders/import-csv` | Importer un CSV (`text/csv`) | 202 `{imported: [...]}` · 400 si invalide |
| GET | `/api/orders` | Lister les commandes | 200 |
| GET | `/api/orders/{orderId}` | État d'une commande | 200 · 404 |
| GET | `/api/orders/{orderId}/events` | Journal d'intégration de la commande | 200 |
| GET | `/api/catalog` | Catalogue (prix + stock via le bus) | 200 · 503 si l'ERP est en panne |

### ERP legacy (lecture)

| Méthode | Chemin | Rôle |
|---|---|---|
| GET | `/api/erp/inventory` | Stock disponible et réservé |
| GET | `/api/erp/orders` | Commandes reçues par l'ERP (XML + facture) |
| GET | `/api/erp/orders/{ref}` | Une commande ERP · 404 si l'ERP ne la connaît pas |

### Livraison (app livreur)

| Méthode | Chemin | Rôle |
|---|---|---|
| GET | `/api/deliveries` | Toutes les missions |
| GET | `/api/deliveries/{missionId}` | Une mission |
| GET | `/api/deliveries/by-order/{orderId}` | Mission d'une commande · 404 s'il n'y en a pas |
| POST | `/api/deliveries/{missionId}/pickup` | Prise en charge, corps optionnel `{"driver":"Koffi"}` |
| POST | `/api/deliveries/{missionId}/confirm` | Confirmer la livraison · 409 si l'ordre n'est pas respecté ou si c'est déjà fait |

### Dead Letter Queue

| Méthode | Chemin | Rôle |
|---|---|---|
| GET | `/api/dlq` | `{queueDepth, messages: [...]}` |
| POST | `/api/dlq/{dlqId}/replay` | Rejouer un message · 409 s'il a déjà été rejoué |

### Supervision

| Méthode | Chemin | Rôle |
|---|---|---|
| GET | `/actuator/health` | Santé globale (`UP` / `DOWN`) |
| GET | `/api/tower/health` | Base, broker, ERP, routes Camel, files |
| GET | `/api/tower/metrics` | Indicateurs calculés |
| GET | `/api/tower/events?limit=200` | Derniers événements |
| WebSocket | `/ws/events` | Événements poussés en temps réel |

### Outils de démonstration (`DEMO_MODE=true` uniquement)

| Méthode | Chemin | Rôle |
|---|---|---|
| POST | `/api/demo/reset` | Réinitialiser les données |
| POST | `/api/demo/erp/down` · `/api/demo/erp/up` | Couper / rétablir l'ERP |
| POST | `/api/demo/corrupt-message?type=malformed` (ou `incomplete`) | Injecter un message invalide dans RabbitMQ |
| POST | `/api/demo/orders/{orderId}/duplicate` | Renvoyer un message en double |
| POST | `/api/demo/stock/{sku}?available=N` | Fixer un stock |
