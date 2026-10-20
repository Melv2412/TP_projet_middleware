# 05 — Exigences non fonctionnelles

| ID | Catégorie | Exigence |
|---|---|---|
| ENF-01 | **Déploiement** | Tout l'écosystème démarre avec `docker compose up --build`, sans installation locale autre que Docker |
| ENF-02 | **Reproductibilité** | Le schéma et les données de démo sont créés automatiquement (migrations Flyway) |
| ENF-03 | **Fiabilité** | Aucun message perdu si l'ERP est indisponible : files RabbitMQ durables |
| ENF-04 | **Cohérence** | Aucune survente, même avec deux commandes simultanées sur le dernier article |
| ENF-05 | **Idempotence** | Rejouer un message ne crée jamais de doublon (contrainte unique en base) |
| ENF-06 | **Traçabilité** | Chaque événement contient `orderId`, `correlationId`, `routeId`, `pattern`, `status`, horodatage |
| ENF-07 | **Performance** | Une commande nominale est traitée de bout en bout en moins de 2 s sur un poste étudiant |
| ENF-08 | **Observabilité** | Healthcheck exposé (`/actuator/health`) ; console RabbitMQ accessible (port 15672) |
| ENF-09 | **Sécurité** | Identifiants dans des variables d'environnement (pas dans le code) ; données client masquées dans les journaux publics |
| ENF-10 | **Honnêteté de la démo** | Aucune animation ou métrique codée en dur ; ce qui est simulé (ERP, paiement) est indiqué comme tel |
| ENF-11 | **Portabilité** | Java 17, fonctionne sous Windows, Linux et macOS via Docker |
| ENF-12 | **Maintenabilité** | Une route Camel par responsabilité ; code et commentaires lisibles |
