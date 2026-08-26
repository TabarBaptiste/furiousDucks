# Pipeline CI/CD - Matrice et justification des stages

Ce document repond a l'exigence "tous les stages doivent etre reellement
justifies". Il complete `18-environnements-dev-preprod-prod.md` (qui couvre le
mapping branche -> environnement et les differences DEV/PREPROD/PROD) en
detaillant, stage par stage, ce qui tourne ou non selon la branche, et
pourquoi. Source : `infrastructure/jenkins/shared-library/vars/ciPipeline.groovy`.

## 1. Regle unique : DEPLOY_ENV

Toutes les conditions du pipeline reposent sur une seule variable,
`env.DEPLOY_ENV`, calculee une fois dans le stage `Prepare` par
`deployEnvFor(branchName, tagName)` :

| Ref Git | DEPLOY_ENV |
|---|---|
| tag `vX.Y.Z` (semver) | `prod` |
| branche `main` | `preprod` |
| branche `develop` | `dev` |
| tout le reste (`feature/*`, `fix/*`, ...) | `''` (vide) |

Avant cette unification, certains stages testaient `env.BRANCH_NAME` ou
`env.TAG_NAME` directement (`Deploy DEV`, `E2E Tests`, `Approval PROD`, le tag
`latest`), en double de ce que `DEPLOY_ENV` exprimait deja. Une seule regle
evite que ces deux ecritures divergent avec le temps.

## 2. Matrice branche x stage

| Stage | feature/* | develop (DEV) | main (PREPROD) | tag v* (PROD) |
|---|---|---|---|---|
| Prepare | oui | oui | oui | oui |
| Install Dependencies | oui | oui | oui | oui (voir 3.5) |
| Lint | oui | oui | oui | non |
| Unit Tests | non | oui | oui | non |
| SonarQube Analysis | non | oui | oui | non |
| Quality Gate | non | oui (non bloquant) | oui (bloquant) | non |
| Gitleaks | non | oui | oui | oui |
| Semgrep SAST | non | non | oui | oui |
| Docker Build (+ API) | non | oui | oui | non |
| Trivy Scan (+ API) | non | oui | oui | non |
| Docker Push (+ API) | non | oui | oui | non |
| Promote Image | non | non | non | oui |
| Ensure Database | non | oui | oui | oui |
| Run Prisma Migrations | non | oui | oui | oui |
| Deploy DEV (+ API) | non | oui | non | non |
| Deploy PREPROD (+ API) | non | non | oui | non |
| E2E Tests (Cypress) | non | non | oui | non |
| OWASP ZAP | non | non | oui (si `runZap`) | non |
| Approval PROD | non | non | non | oui |
| Deploy PROD (+ API), Blue/Green | non | non | non | oui |

## 3. Justification par stage

### 3.1 Toujours actifs (toutes branches)

- **Prepare** : calcule `IMAGE_TAG`, `HOST_WORKSPACE`, `DEPLOY_ENV`. Sans lui,
  aucun autre stage ne sait sur quel environnement se brancher.
- **Install Dependencies** : voir 3.5, cas particulier du tag.
- **Lint** : retour rapide et peu couteux (quelques secondes), utile meme sur
  une branche jetable avant sa Merge Request. Exclu du tag (3.5).

### 3.2 Reserves aux branches qui deploient (dev, preprod)

- **Unit Tests, SonarQube Analysis, Quality Gate** : retires de `feature/*`
  parce qu'aucun artefact n'en sort avant la Merge Request vers `develop` -
  c'est elle qui sert de controle. Retires du tag parce que le commit a deja
  ete teste et analyse au moment du build sur `main` (voir Promote Image, 3.4).
  Le Quality Gate est desormais **bloquant** sur `preprod`, **non bloquant**
  (`unstable`) sur `dev` : une branche encore en developpement actif garde un
  retour rapide, PREPROD (qui alimente la promotion vers PROD) ne laisse plus
  passer un gate en echec sans le signaler.
- **Docker Build / Trivy Scan / Docker Push** (+ variantes API) : construits et
  scannes uniquement sur `dev`/`preprod`. Le tag ne reconstruit rien (3.4).

### 3.3 Barriere securite avant toute image (dev, preprod, prod)

- **Gitleaks** : tourne avant `Docker Build`, sur toutes les branches qui
  produisent ou promeuvent une image (y compris le tag, qui ne construit pas
  mais publie quand meme un artefact final). Anciennement place apres
  `Docker Push` : un secret n'etait alors detecte qu'une fois l'image deja
  publiee sur Harbor.
- **Semgrep SAST** : sur `preprod` et `prod` seulement (pas `dev`, deja
  couvert par SonarQube ; pas `feature/*`, aucun artefact ne sort). Remonte
  avant le build, la ou il peut encore bloquer quelque chose - il tournait
  avant apres `Deploy DEV`, soit trop tard pour empecher un deploiement.

### 3.4 Construction une seule fois (build once, deploy everywhere)

- **Promote Image** (tag uniquement) : retague dans Harbor l'image
  `main-<sha>` deja construite, scannee et validee sur PREPROD, au lieu de
  reconstruire depuis le source. Consequence directe : `Docker Build`,
  `Trivy Scan`, `Docker Push` sont tous absents du chemin tag - reconstruire
  un binaire deja valide n'apporterait rien et romprait la garantie CST 2.4
  ("la meme image construite une fois peut etre reutilisee a l'identique a
  travers les environnements sans reconstruction"). Une consequence: un tag ne
  peut etre pose que sur un commit deja construit et valide sur `main` ; sans
  image `main-<sha>` presente dans Harbor, la promotion echoue - c'est une
  contrainte de process voulue, pas un defaut.

### 3.5 Cas particulier : Install Dependencies sur le tag

Contrairement a Lint/Unit Tests/Sonar, **Install Dependencies reste actif sur
le chemin tag**. Raison : `Run Prisma Migrations` tourne aussi en PROD (voir
3.6) et resout le CLI Prisma depuis les `node_modules` installes par ce
stage, a la version fixee par le lockfile - une resolution "a la volee" depuis
un conteneur nu risquerait une derive de version. Le cout est faible (quelques
dizaines de secondes) au regard du risque evite.

### 3.6 Base de donnees (dev, preprod, prod)

- **Ensure Database / Run Prisma Migrations** : actifs sur les trois
  environnements de deploiement, y compris PROD. PROD a un conteneur Postgres
  **dedie** (`dbContainerFor()` retourne `<projet>-db-prod`), jamais partage
  avec DEV/PREPROD, et **aucune donnee n'est copiee ou restauree** entre
  environnements (aucun `pg_dump`/`pg_restore`/seed dans la shared library) :
  la base PROD nait vide a la premiere execution. Sans ce stage, l'API PROD
  demarrerait sur une base sans schema.

### 3.7 Deploiement

- **Deploy DEV / Deploy PREPROD** (+ API) : remplacement simple du conteneur,
  chacun sur sa branche (`DEPLOY_ENV == 'dev'` / `'preprod'`).
- **Approval PROD / Deploy PROD** (+ API), Blue/Green : uniquement sur tag,
  avec approbation manuelle prealable (`admin`/`maintainers`). Le Blue/Green
  et son monitoring 15 min sont detailles dans
  `18-environnements-dev-preprod-prod.md` (section 3).

### 3.8 Validation post-deploiement (preprod uniquement)

- **E2E Tests (Cypress), OWASP ZAP** : contre PREPROD deploye, avant la
  promotion vers PROD - c'est le dernier point ou un defaut fonctionnel ou une
  mauvaise configuration de securite peut encore bloquer une release.

## 4. Stages retires ou deplaces (hors matrice)

- **Build Assets** (supprime) : lancait `npm run build` a la racine, jamais
  consomme par le Dockerfile multi-stage qui refait son propre build. En plus
  de son cout propre, il polluait le workspace et gonflait le contexte Docker
  envoye a `docker build`.
- **Monitor PREPROD** (supprime) : voir
  `18-environnements-dev-preprod-prod.md` section 3 - PREPROD n'a pas de
  mecanisme de rollback automatique pour exploiter une fenetre de
  surveillance, contrairement a PROD (Blue/Green).
- **K6 Performance** (deplace en job planifie) : n'a pas de dependance au
  workspace du build, il tape sur PREPROD deja deploye. Devient
  `Jenkinsfile.k6`, execution hebdomadaire planifiee plutot qu'a chaque push -
  voir `13-k6.md`.
- Le doublon `checkout scm` explicite dans l'ancien stage "Checkout" a ete
  retire : Jenkins fait deja ce checkout dans son stage implicite
  "Declarative: Checkout SCM". Le stage renomme "Prepare" garde son role reel
  (calcul de `IMAGE_TAG`/`HOST_WORKSPACE`/`DEPLOY_ENV`).

## 5. Retention et stockage

### 5.1 Historique et artefacts Jenkins

`buildDiscarder(logRotator(...))` dans `ciPipeline.groovy`, deux compteurs
distincts :

| | Conserve | Duree/nombre |
|---|---|---|
| Builds (logs, metadonnees) | `numToKeepStr` | 30 |
| Artefacts (rapports Trivy/Semgrep/Gitleaks, HTML Playwright/ZAP) | `artifactNumToKeepStr` | 10 |

Les artefacts sont plus lourds et ne servent qu'au diagnostic recent ; les
logs sont legers et servent a la tracabilite sur une fenetre plus longue.
`k6Pipeline.groovy` suit le meme principe avec des chiffres plus larges
(60/20) car ce job ne tourne qu'une fois par semaine.

### 5.2 Nettoyage du workspace

`cleanupWorkspace()` tourne dans le bloc `post { always { ... } }` (et non
plus `post { success { ... } }`) : il archive les rapports de securite puis
vide le workspace, quel que soit le resultat du build. Avant ce changement, un
build en echec (par exemple une CVE Trivy critique) n'archivait jamais le
rapport qui explique l'echec, et son workspace restait indefiniment sur le
disque Jenkins.

### 5.3 Retention Harbor

Detaillee dans `04-harbor.md` (sections 3.8 et 3.8 bis). Resume :

- Images `develop-*`/`main-*` : 10 plus recentes conservees.
- Images `v*` (releases) : conservees indefiniment - dependance directe de
  `Promote Image` (3.4 ci-dessus) et de `scripts/rollback.sh`.
- Images non taguees : purgees apres 7 jours (garbage collection).

Conforme au CST (section 2.4 et 7.5) : la politique de retention est
appliquee en amont de la sauvegarde hebdomadaire Restic -> MinIO du registre,
qui protege ainsi un contenu deja nettoye de l'essentiel du superflu.

## 6. References code

- Regle DEPLOY_ENV : `deployEnvFor()` dans
  `infrastructure/jenkins/shared-library/vars/ciPipeline.groovy`
- Promotion d'image : `promoteImage()`, stage `Promote Image`
- Nettoyage post-build : `cleanupWorkspace()`
- Politique de retention Harbor : `infrastructure/docs/04-harbor.md`
- Environnements et Blue/Green : `infrastructure/docs/18-environnements-dev-preprod-prod.md`
