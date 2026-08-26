# Environnements DEV / PREPROD / PROD

## 1. Mapping branche -> environnement

Le mapping est calcule une seule fois par build, dans `deployEnvFor()`
(`infrastructure/jenkins/shared-library/vars/ciPipeline.groovy`), et stocke
dans `env.DEPLOY_ENV`. Toutes les autres stages (Docker Build, Trivy,
SonarQube, Deploy...) lisent cette seule variable, sans redecider chacune de
leur cote.

| Ref Git | DEPLOY_ENV | Environnement |
|---|---|---|
| branche `develop` | `dev` | DEV |
| branche `main` | `preprod` | PREPROD |
| tag `vX.Y.Z` (semver) | `prod` | PROD |
| `feature/*`, `fix/*`, `release/*`, `hotfix/*`, etc. | `''` (vide) | Aucun deploiement |

Point d'attention frequemment source de confusion : la branche `main` ne
deploie **pas** en PROD. Elle deploie en PREPROD. Seul un tag semantique
(`v1.2.3`, cree a la fusion d'une `release/*` ou d'un `hotfix/*`) declenche un
deploiement PROD - avec en plus une approbation manuelle (stage `Approval
PROD`, soumissible par `admin` ou `maintainers`).

Sur une branche qui ne deploie nulle part, `DEPLOY_ENV` reste vide : les
stages Docker Build / Trivy / Gitleaks / SonarQube / Quality Gate / Deploy
sont tous ignores (`when { expression { env.DEPLOY_ENV in [...] } } }`). Seuls
`Lint` et `Unit Tests` restent inconditionnels, pour donner un retour rapide
sur une branche jetable avant sa Merge Request.

## 2. Tableau comparatif

| | DEV | PREPROD | PROD |
|---|---|---|---|
| Ref declenchante | push sur `develop` | push sur `main` | tag `vX.Y.Z` |
| Domaine | `*-dev.wk-archi-o24a-4-5-g1.fr` | `*-preprod.wk-archi-o24a-4-5-g1.fr` | `dsp5-archi-o24a-4-5-g1.fr` (frontend) / `api.dsp5-archi-o24a-4-5-g1.fr` (API) |
| Methode de deploiement | remplacement simple du conteneur | remplacement simple du conteneur | Blue/Green (`deployBlueGreen.groovy`) |
| Approbation manuelle | non | non | oui (stage `Approval PROD`) |
| SonarQube / Quality Gate | oui | oui | oui (herite du build qui a promu le tag) |
| Trivy / Gitleaks | oui | oui | oui |
| E2E (Cypress) | non | oui, contre PREPROD deploye | non (deja valide en PREPROD) |
| OWASP ZAP baseline | non | oui, contre PREPROD deploye | non |
| K6 (perf) | non | oui, planifie hebdo (`Jenkinsfile.k6`, lundi ~2h) | non |
| Monitoring post-deploiement | non (retire, voir 3.) | non (retire, voir 3.) | oui, 15 min avec rollback auto (Blue/Green) |
| Conteneur Postgres | partage avec PREPROD (`<projet>-db-shared`) | partage avec DEV (`<projet>-db-shared`) | dedie (`<projet>-db-prod`) |
| Base de donnees | `<projet>_dev` (schema separe, meme instance que PREPROD) | `<projet>_preprod` (schema separe, meme instance que DEV) | `<projet>_prod`, instance dediee |
| Conteneur Redis | `<projet>-redis-shared` (nom legacy, voir 4.) | `<projet>-redis-preprod` | `<projet>-redis-prod` |
| Credentials / secrets | `dbCredentialsId` par defaut, ou override via `dbCredentialsByEnv.dev` | idem, override `dbCredentialsByEnv.preprod` | idem, override `dbCredentialsByEnv.prod` - recommande vu que PROD est la seule instance dediee |
| Rollback | redeploiement manuel de l'image precedente | idem | automatique si le Blue/Green echoue, ou manuel via `scripts/rollback.sh` |

## 3. Monitoring post-deploiement : DEV/PREPROD vs PROD

Un stage `Monitor PREPROD` existait et a ete retire (retour du jury du
23/08 : ce monitoring n'apportait rien, PREPROD n'a pas de mecanisme de
rollback automatique pour en tirer parti - contrairement a PROD).

En PROD, le monitoring 15 minutes reste actif et fait partie integrante du
Blue/Green (`deployBlueGreen.groovy` / `monitorDeployment()`) : le nouveau
conteneur GREEN est surveille avant promotion definitive, et est retire
automatiquement (rollback) en cas d'echec repete - sans jamais toucher au
conteneur BLUE encore en service. Voir aussi `scripts/rollback.sh` pour un
rollback manuel d'urgence.

## 4. Limitations connues (a garder en tete)

- **DEV et PREPROD partagent la meme instance Postgres** (conteneur
  `<projet>-db-shared`), avec juste une base logique differente par
  environnement (`<projet>_dev` / `<projet>_preprod`). Ce n'est pas une
  isolation infrastructurelle complete : un incident sur cette instance
  (ressources, corruption) affecte les deux environnements a la fois. Seul
  PROD a sa propre instance dediee.
- **Le conteneur Redis DEV s'appelle `<projet>-redis-shared`**, un nom herite
  du moment ou PREPROD n'existait pas encore. Il n'est pas realmente partage
  avec PREPROD (qui a son propre `<projet>-redis-preprod`) : le nom est juste
  trompeur et n'a pas ete renomme pour eviter de recreer le conteneur (et donc
  perdre son contenu).
- Les credentials/secrets (`dbCredentialsId`, `apiSecretEnv`) sont partages
  entre environnements par defaut ; un projet peut les isoler par
  environnement via `dbCredentialsByEnv` / `apiSecretEnvByEnv` dans son
  Jenkinsfile, sans modifier la shared library.

## 5. References code

- Mapping branche/tag -> environnement : `deployEnvFor()` dans
  `infrastructure/jenkins/shared-library/vars/ciPipeline.groovy`
- Nom du conteneur Postgres par environnement : `dbContainerFor()`
- Nom du conteneur Redis par environnement : `redisContainerFor()`
- Mecanisme Blue/Green PROD : `infrastructure/jenkins/shared-library/vars/deployBlueGreen.groovy`
- Rollback manuel : `infrastructure/scripts/rollback.sh`
