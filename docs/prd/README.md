# PRD — SIDAM CONNECT (Bus d'intégration SIDAM Group)

> *Three systems. One intelligent ecosystem.*

Product Requirements Document du projet middleware « Sujet 4 : Bus d'intégration pour un groupe de distribution ».
Le PRD est découpé en fichiers courts, chacun traitant un seul aspect.

| # | Fichier | Contenu |
|---|---|---|
| 01 | [01_contexte_et_objectifs.md](01_contexte_et_objectifs.md) | Problème métier, objectifs, périmètre, hors-périmètre |
| 02 | [02_personas_et_user_stories.md](02_personas_et_user_stories.md) | Acteurs et user stories |
| 03 | [03_user_flows.md](03_user_flows.md) | Parcours utilisateurs et flux de messages (diagrammes) |
| 04 | [04_exigences_fonctionnelles.md](04_exigences_fonctionnelles.md) | Exigences fonctionnelles numérotées (EF-xx) |
| 05 | [05_exigences_non_fonctionnelles.md](05_exigences_non_fonctionnelles.md) | Fiabilité, performance, sécurité, exploitabilité (ENF-xx) |
| 06 | [06_architecture_technique.md](06_architecture_technique.md) | Stack, conteneurs, composants, choix techniques |
| 07 | [07_modele_de_donnees.md](07_modele_de_donnees.md) | Schémas PostgreSQL `erp` et `bus` |
| 08 | [08_contrats_et_api.md](08_contrats_et_api.md) | API REST, formats JSON / XML / CSV, files RabbitMQ |
| 09 | [09_routes_camel_et_eip.md](09_routes_camel_et_eip.md) | Routes Camel et patterns EIP appliqués |
| 10 | [10_plan_de_tests.md](10_plan_de_tests.md) | Cas de test T01–T09 et critères d'acceptation |
| 11 | [11_planning_etapes.md](11_planning_etapes.md) | Découpage en étapes (cascade avec validation par phase) |

## Sources

- Sujet de TP (capture `SUJET4_TP_CLass_ MIDDLWARE.jpeg`).
- Dossier d'architecture `Sujet4_Bus_Integration_SIDAM_Niveau17.docx`.
- Kit de conception `SIDAM_CONNECT_Pack`.

## Règle d'or

Tout ce qui est affiché (statut, animation, métrique) doit provenir d'un message **réellement traité par Apache Camel**, persisté en base et retrouvable par son `correlationId`. Aucune donnée de démonstration codée en dur.
