# Furious Ducks — Plateforme CI/CD

Plateforme d'infrastructure CI/CD complète, décrite en **Infrastructure as Code** et déployée avec **Docker Compose** sur un VPS Linux. Elle fournit à des projets clients toute la chaîne de livraison : gestion du code source, intégration continue, contrôles qualité et sécurité, registre d'images, déploiement multi-environnements, supervision et sauvegardes.

Projet réalisé en équipe dans le cadre d'une formation. Le projet pilote déployé sur la plateforme est [Thé Tip Top](https://github.com/TabarBaptiste/theTipTop), un jeu-concours (NestJS, Next.js, PostgreSQL).

## Fonctionnalités

- **Reverse proxy et TLS automatique** : Traefik v3 et Let's Encrypt, chaque service exposé en HTTPS sur son sous-domaine
- **Pipeline CI/CD partagé** : shared library Jenkins (Groovy) réutilisable par les projets clients, pour les stacks `node`, `php`, `python` et `ruby`
- **Qualité et sécurité dans le pipeline** : SonarQube (quality gate), Semgrep (SAST), Gitleaks (secrets), Trivy (scan des images)
- **Déploiement DEV → PREPROD → PROD** : remplacement de conteneur en DEV et PREPROD, **Blue/Green** en PROD avec approbation manuelle, rollback d'urgence par script
- **Observabilité** : Prometheus, Grafana, Loki, Alloy, Alertmanager (alertes vers Mattermost), cAdvisor, node-exporter et métriques **DORA**
- **Sauvegardes chiffrées** : Restic vers MinIO (S3), planifiées par Ofelia, avec vérification d'intégrité et rétention
- **Tests de charge** : k6, intégré à Jenkins

## Architecture

```
                        Internet
                          ↓↑
                    ┌─────────────┐
                    │   Traefik   │ (Reverse Proxy, TLS)
                    │ :80, :443   │
                    └──────┬──────┘
                           │
      ┌──────────┬─────────┼──────────┬────────────┐
      │          │         │          │            │
  ┌───▼───┐  ┌───▼───┐ ┌──▼────┐ ┌───▼──────┐  ┌───▼───┐
  │GitLab │  │Jenkins│ │Sonar  │ │Mattermost│  │Harbor │
  │  CE   │  │  CI   │ │ Qube  │ │          │  │       │
  └───┬───┘  └───┬───┘ └───┬───┘ └────┬─────┘  └───┬───┘
      │          │         │          │            │
      └──────────┴─────────┴──────────┴────────────┘
                    ci_network
                           │
      ┌──────────┬─────────┼──────────┬────────────┐
      │          │         │          │            │
  ┌───▼──────┐ ┌─▼──────┐ ┌▼───────┐ ┌▼────────┐ ┌─▼───────┐
  │Prometheus│ │ Grafana│ │  Loki  │ │  Alloy  │ │cAdvisor │
  │(Metrics) │ │(Charts)│ │(Logs)  │ │(Shipper)│ │         │
  └──────────┘ └────────┘ └────────┘ └─────────┘ └─────────┘
                    monitoring_network
                           │
            ┌──────────────┼──────────────┐
            │              │              │
        ┌───▼────┐    ┌───▼────┐    ┌───▼────┐
        │  MinIO │    │ Backup │    │ Ofelia │
        │(Storage)    │Manager │    │(Cron)  │
        │        │    │(Restic)│    │        │
        └────────┘    └────────┘    └────────┘
                    backup_network
```

La plateforme est organisée en trois stacks Compose, plus Harbor, installé à part avec son installeur officiel.

| Stack | Fichier | Services |
|---|---|---|
| CI/CD | `docker-compose.yml` | Traefik, GitLab CE, Jenkins, SonarQube (+ PostgreSQL), Mattermost (+ PostgreSQL), exporters PostgreSQL |
| Monitoring | `docker-compose.monitoring.yml` | Prometheus, Grafana, Loki, Alloy, Alertmanager, cAdvisor, node-exporter, DORA metrics |
| Backup | `docker-compose.backup.yml` | MinIO, Backup Manager (Restic), Ofelia |
| Registre | installeur Harbor | Harbor, intégré ensuite à Traefik et Prometheus |

### Pipeline CI/CD

```
1. PUSH CODE          Développeur -> GitLab
2. WEBHOOK            GitLab -> Jenkins
3. PIPELINE           Lint, tests unitaires, Gitleaks, Semgrep (SAST),
                      SonarQube (quality gate), build image, scan Trivy,
                      push Harbor, déploiement DEV -> PREPROD -> PROD
                      (Blue/Green en PROD, approbation manuelle requise)
4. MONITORING         Prometheus collecte les métriques, Alertmanager
                      notifie Mattermost
5. LOGS               Alloy expédie les logs vers Loki, visualisés dans Grafana
6. BACKUP             Ofelia déclenche Restic chaque nuit
```

Le quality gate SonarQube n'est bloquant qu'en PREPROD (branche `main`) et en PROD (tag `vX.Y.Z`). En DEV (branche `develop`), un échec fait passer le build en `UNSTABLE`.

### Exposition des services

Seuls Traefik (80, 443) et le SSH de GitLab (2222) publient des ports sur l'hôte. Tous les autres services ne sont accessibles que via Traefik, sur leur sous-domaine :

| Sous-domaine | Service |
|---|---|
| `git.` | GitLab CE |
| `ci.` | Jenkins |
| `sonar.` | SonarQube |
| `chat.` | Mattermost |
| `registry.` | Harbor |
| `monitor.` / `grafana.` | Grafana |
| `prometheus.` | Prometheus (basic auth) |
| `s3.` / `minio.` | MinIO (API S3 / console) |
| `traefik.` | Dashboard Traefik (basic auth) |

## Démarrage rapide

**Prérequis** : un serveur Linux avec Docker Engine ≥ 24 et Docker Compose v2 ≥ 2.20, un nom de domaine dont les sous-domaines pointent vers le serveur, et les ports 80, 443 et 2222 ouverts.

```bash
git clone https://github.com/TabarBaptiste/furiousDucks.git infrastructure
cd infrastructure

# Configuration : remplacer toutes les valeurs "changeme"
cp .env.example .env
chmod 600 .env

# Fichier de certificats Traefik (Traefik refuse de démarrer sans chmod 600)
mkdir -p traefik/acme
touch traefik/acme/acme.json
chmod 600 traefik/acme/acme.json

# Réseau partagé avec les environnements des projets clients
docker network create workflow-network

# Démarrage des trois stacks
docker compose up -d
docker compose -f docker-compose.monitoring.yml up -d
docker compose -f docker-compose.backup.yml up -d
```

Le script `scripts/init-infrastructure.sh` enchaîne ces étapes. Le domaine `wk-archi-o24a-4-5-g1.fr` utilisé dans la configuration est celui de notre déploiement : remplacez-le par le vôtre. La procédure complète (DNS, génération des mots de passe, ordre de démarrage, vérifications) est décrite dans [docs/00-getting-started.md](docs/00-getting-started.md).

## Structure du dépôt

```
.
├── docker-compose.yml                 # Stack CI/CD
├── docker-compose.monitoring.yml      # Stack monitoring
├── docker-compose.backup.yml          # Stack backup
├── .env.example                       # Modèle des variables d'environnement
├── traefik/                           # Configuration statique et dynamique
├── jenkins/                           # Dockerfile, plugins, JCasC, shared library
├── jenkins-pipelines/                 # Jenkinsfile modèles pour les projets clients
├── prometheus/                        # Scrape config et règles d'alerte
├── grafana/                           # Provisioning des dashboards et datasources
├── loki/  alloy/  alertmanager/       # Logs et alerting
├── dora/                              # Calcul des métriques DORA
├── backup/                            # Image et scripts Restic
├── scripts/                           # Initialisation, nouveau projet, rollback, purges
└── docs/                              # Documentation détaillée
```

## Documentation

| Document | Contenu |
|---|---|
| [docs/00-getting-started.md](docs/00-getting-started.md) | Installation pas à pas, à lire en premier |
| [docs/how/01-environnements-dev-preprod-prod.md](docs/how/01-environnements-dev-preprod-prod.md) | Environnements et stratégie de déploiement |
| [docs/how/02-pipeline-ci-cd.md](docs/how/02-pipeline-ci-cd.md) | Fonctionnement du pipeline et de la shared library |
| [docs/how/03-backup.md](docs/how/03-backup.md) | Sauvegarde, rétention et restauration |
| [docs/how/04-comprendre-grafana.md](docs/how/04-comprendre-grafana.md) | Lecture des dashboards Grafana |
| [docs/runbook-administration.md](docs/runbook-administration.md) | Runbook d'administration |
| [docs/tools/](docs/tools/) | Une fiche par outil, de Traefik à Ofelia |

## Auteurs

- **Baptiste Tabar Labonne** — [@TabarBaptiste](https://github.com/TabarBaptiste)
- **Harena Andriamananjara Mandimby** — [@AndriamHarena](https://github.com/AndriamHarena)
- **Ibrahima Elhadj Bah** — [@Ibrahima17](https://github.com/Ibrahima17)

## Licence

Distribué sous licence MIT. Voir [LICENSE](LICENSE).
