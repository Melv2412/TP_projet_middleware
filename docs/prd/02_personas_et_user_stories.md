# 02 — Personas et user stories

## Personas

| Persona | Description | Interface |
|---|---|---|
| **Client** | Achète en ligne, veut savoir si sa commande est acceptée | SIDAM Store |
| **Gestionnaire ERP** | Gère le stock et la facturation dans l'ERP historique | SIDAM Legacy ERP |
| **Livreur** | Reçoit ses missions et confirme les livraisons | SIDAM Delivery (mobile) |
| **Opérateur d'intégration** | Surveille le bus, traite les erreurs, rejoue les messages | SIDAM Control Tower |
| **Jury** | Doit voir la preuve que le middleware fonctionne | Control Tower + RabbitMQ UI |

## User stories

### Client
- **US-01** — En tant que client, je passe une commande de plusieurs articles et je reçois immédiatement une référence (`CMD-…`).
- **US-02** — En tant que client, je vois le statut de ma commande (reçue, acceptée, refusée, livrée).
- **US-03** — En tant que client, si un article est en rupture, je suis informé du refus et de la quantité disponible.

### Gestionnaire ERP
- **US-04** — En tant que gestionnaire, les commandes validées arrivent dans l'ERP au format XML, sans ressaisie.
- **US-05** — En tant que gestionnaire, le stock est décrémenté automatiquement et une facture est générée.
- **US-06** — En tant que gestionnaire, je peux importer un fichier CSV de commandes (format historique).

### Livreur
- **US-07** — En tant que livreur, je reçois une mission dès qu'une commande est réservée et facturée.
- **US-08** — En tant que livreur, je prends en charge puis confirme la livraison ; le statut remonte partout.

### Opérateur d'intégration
- **US-09** — En tant qu'opérateur, je vois en temps réel chaque message traversant le bus (source, destination, pattern, statut).
- **US-10** — En tant qu'opérateur, j'inspecte le message avant / après transformation (JSON → XML).
- **US-11** — En tant qu'opérateur, je vois les messages en échec dans la DLQ avec leur motif, et je peux les rejouer (*Replay*).
- **US-12** — En tant qu'opérateur, je peux simuler une panne de l'ERP (mode démo uniquement).

### Jury
- **US-13** — En tant que membre du jury, je vois la timeline complète d'une commande avec durées réelles, prouvant le fonctionnement des EIP.

## Priorisation (MoSCoW)

| Priorité | User stories |
|---|---|
| **Must** | US-01, US-02, US-03, US-04, US-05, US-09, US-11 |
| **Should** | US-06, US-07, US-08, US-10, US-12, US-13 |
| **Could** | Métriques avancées, filtres du journal |
| **Won't** | Paiement réel, application native |
