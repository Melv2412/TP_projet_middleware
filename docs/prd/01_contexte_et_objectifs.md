# 01 — Contexte et objectifs

## Problème métier

SIDAM Group distribue du matériel électroménager. Son activité repose sur trois systèmes construits à des époques différentes, qui ne communiquent pas et n'utilisent pas les mêmes formats :

| Système | Rôle | Format |
|---|---|---|
| **ERP Legacy** | Stock, facturation | XML, fichiers plats CSV |
| **Site e-commerce (Store)** | Vente en ligne | JSON |
| **App mobile livreur (Delivery)** | Missions de livraison | JSON |

Processus actuel : chaque matin, un employé exporte les commandes du site en CSV, les saisit dans l'ERP, puis envoie la liste aux livreurs par e-mail.

Conséquences :
- erreurs de saisie ;
- commandes en retard ;
- **ventes de produits en rupture** (un client commande un article que l'ERP a déjà vendu) ;
- aucune traçabilité.

## Objectif

Construire **SIDAM CONNECT**, un bus d'intégration qui automatise le parcours complet d'une commande, **de la validation à la livraison, sans remplacer l'ERP** (migration jugée trop coûteuse).

Le bus doit :
1. **transformer** les formats (CSV, XML, JSON) ;
2. **router** les messages vers le bon système ;
3. **orchestrer** le processus `commande → vérification du stock → livraison` ;
4. appliquer les **patterns d'intégration d'entreprise (EIP)**.

## Objectifs mesurables

| ID | Objectif | Indicateur |
|---|---|---|
| OBJ-1 | Zéro saisie manuelle | Une commande Store atteint l'ERP sans intervention humaine |
| OBJ-2 | Zéro survente | Aucune commande validée si le stock est insuffisant, y compris en concurrence |
| OBJ-3 | Zéro perte de message | Une panne ERP ne fait perdre aucune commande (RabbitMQ + DLQ) |
| OBJ-4 | Zéro doublon | Un message rejoué ne crée qu'une seule réservation / facture / mission |
| OBJ-5 | Traçabilité totale | Chaque étape est journalisée avec un `correlationId` commun |

## Périmètre (in scope)

- Bus Apache Camel sur Spring Boot.
- RabbitMQ comme tampon asynchrone et Dead Letter Queue.
- PostgreSQL : simulation de l'ERP legacy (schéma `erp`) et journal du bus (schéma `bus`).
- Quatre interfaces web simples (HTML + Tailwind) : Store, ERP, Delivery, Control Tower.
- Déploiement complet en une commande : `docker compose up`.

## Hors périmètre

- Reconstruire un vrai ERP ou un vrai site e-commerce (ils sont **simulés**).
- Paiement réel (au plus un paiement **simulé**, présenté comme tel).
- Application mobile native (Delivery est une page web mobile-first).
- Authentification forte multi-utilisateurs.
- Haute disponibilité multi-nœuds.

## Message clé pour le jury

> Nous n'avons pas remplacé l'ERP de SIDAM Group. Nous avons créé ce qui manquait aux trois applications : la capacité à communiquer, à comprendre leurs données respectives et à travailler ensemble.
