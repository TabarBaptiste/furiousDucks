# Pipeline CI/CD - Matrice et justification des stages

Ce document repond a l'exigence "tous les stages doivent etre reellement
justifies". Il complete `01-environnements-dev-preprod-prod.md` (qui couvre le
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
| Install Dependencies | oui | oui | oui | oui si API (voir 3.5) |
| Lint | oui | oui | oui | non |
| Unit Tests | oui | oui | oui | non |
| Gitleaks | oui | oui | oui | non |
| SonarQube Analysis | non | oui | oui | non |
| Quality Gate | non | oui (non bloquant) | oui (bloquant) | non |
| Semgrep SAST | non | non | oui | non |
| Docker Build (+ API) | non | oui | oui | front seul si `webappIsEnvSpecific` |
| Trivy Scan (+ API) | non | oui | oui | front seul si `webappIsEnvSpecific` |
| Docker Push (+ API) | non | oui | oui | front seul si `webappIsEnvSpecific` |
| Promote Image | non | non | non | oui |
| **Approval PROD** | non | non | non | **oui** |
| Ensure Database | non | oui | oui | oui |
| Run Prisma Migrations | non | oui | oui | oui |
| Deploy DEV (+ API) | non | oui | non | non |
| Deploy PREPROD (+ API) | non | non | oui | non |
| E2E Tests (Playwright/Cypress) | non | non | oui | non |
| OWASP ZAP | non | non | oui (si `runZap`) | non |
| Deploy PROD (+ API), Blue/Green | non | non | non | oui |

L'ordre des lignes est celui d'execution. `Approval PROD` est volontairement
place **avant** `Ensure Database` : voir 3.7.

## 3. Justification par stage

### 3.1 Toujours actifs (toutes branches)

- **Prepare** : calcule `IMAGE_TAG`, `HOST_WORKSPACE`, `DEPLOY_ENV`. Sans lui,
  aucun autre stage ne sait sur quel environnement se brancher.
- **Install Dependencies** : voir 3.5, cas particulier du tag.
- **Lint** : retour rapide et peu couteux (quelques secondes), utile meme sur
  une branche jetable avant sa Merge Request. Exclu du tag (3.5).
- **Unit Tests** : joues des `feature/*`. Les dependances sont deja installees,
  le cout marginal, et c'est le **seul retour automatise** qu'une branche de
  travail obtient avant sa Merge Request - la relecture humaine n'est pas un
  substitut a une suite de tests. Exclu du tag : meme commit, deja teste sur
  `main`.
- **Gitleaks** : voir 3.3.

### 3.2 Reserves aux branches qui deploient (dev, preprod)

- **SonarQube Analysis, Quality Gate** : absents de `feature/*` pour une raison
  technique, pas de cout : SonarQube tourne en **edition Community**, qui ne
  connait pas l'analyse par branche. Toute analyse, quelle que soit sa branche
  d'origine, ecrase les metriques du projet unique - scanner une `feature/*`
  ferait donc mentir le Quality Gate de `develop` et de `main`. Absents du tag
  parce que le commit a deja ete analyse sur `main` (voir Promote Image, 3.4).
  Le Quality Gate est **bloquant** sur `preprod`, **non bloquant** (`unstable`)
  sur `dev` : une branche encore en developpement actif garde un retour rapide,
  PREPROD (qui alimente la promotion vers PROD) ne laisse plus passer un gate
  en echec sans le signaler.
- **Docker Build / Trivy Scan / Docker Push** (+ variantes API) : construits et
  scannes uniquement sur `dev`/`preprod`. Le tag ne reconstruit rien (3.4),
  a l'exception du front lorsque `webappIsEnvSpecific` est actif (3.9).

### 3.3 Barriere securite avant toute image

- **Gitleaks** (toutes branches sauf tag) : tourne avant `Docker Build`.
  Anciennement place apres `Docker Push` : un secret n'etait alors detecte
  qu'une fois l'image deja publiee sur Harbor. C'est le seul controle dont la
  valeur depend strictement de sa **precocite** : un secret pousse sur une
  `feature/*` est deja compromis et devra etre revoque - l'apprendre a la
  Merge Request est deja tard, l'apprendre au tag n'a plus aucun sens. D'ou sa
  presence des les branches de travail, et son absence du chemin tag (3.4).
- **Semgrep SAST** (`preprod` uniquement) : porte d'entree vers la production
  et dernier point ou l'arbre source change encore. Ecarte de `dev` (rythme des
  pushs, deja couvert par SonarQube), de `feature/*` (aucun artefact ne sort)
  et du tag (3.4). Remonte avant le build, la ou il peut encore bloquer quelque
  chose - il tournait avant apres `Deploy DEV`, soit trop tard pour empecher un
  deploiement. A noter : Semgrep ne pose qu'un `unstable`, jamais un `error`.

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
- **Corollaire : aucun controle sur source n'est rejoue sur le tag.** Lint,
  Unit Tests, SonarQube, Quality Gate, Gitleaks et Semgrep operent tous sur
  l'arbre source. Or un build de tag ne modifie pas cet arbre : c'est le meme
  commit que celui construit sur `main`, et l'existence de l'image
  `main-<sha>` dans Harbor - sans laquelle la promotion echoue - le prouve.
  Rejouer ces controles avec les memes regles sur les memes fichiers ne peut
  produire qu'un verdict identique. Seul Trivy pourrait, lui, changer d'avis
  (la base CVE evolue apres le build PREPROD) - mais cette surveillance est
  deja assuree par le **re-scan quotidien de Harbor** (`tools/04-harbor.md`
  section 3.8), qui couvre toutes les images stockees sans immobiliser un
  agent Jenkins.

### 3.5 Cas particulier : Install Dependencies sur le tag

Contrairement a Lint/Unit Tests/Sonar, **Install Dependencies reste actif sur
le chemin tag - mais seulement si le projet a une API**. Raison :
`Run Prisma Migrations` tourne aussi en PROD (voir 3.6) et resout le CLI Prisma
depuis les `node_modules` installes par ce stage, a la version fixee par le
lockfile - une resolution "a la volee" depuis un conteneur nu risquerait une
derive de version. Le cout est faible (quelques dizaines de secondes) au regard
du risque evite.

Sans API (`hasApi == false`), ce consommateur disparait et **plus aucun stage
du chemin tag ne lit les `node_modules`** : Lint, Unit Tests et Sonar en sont
tous absents. L'installation est donc sautee (`env.DEPLOY_ENV != 'prod' ||
hasApi`).

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
- **Approval PROD** : uniquement sur tag, `admin`/`maintainers`. Sa position
  dans le pipeline est un choix de conception : il est place **juste apres
  `Promote Image` et juste avant `Ensure Database`**, c'est-a-dire exactement
  sur la frontiere entre l'inerte et l'irreversible. Tout ce qui le precede se
  contente de preparer un artefact dans Harbor, sans toucher a la production
  qui tourne ; tout ce qui le suit modifie l'etat reel - creation de base,
  migrations Prisma, bascule Blue/Green.

  Il se trouvait auparavant apres les migrations. Un refus de l'approbateur
  laissait donc le **schema de la base de production deja migre** face a une
  application non deployee, et `scripts/rollback.sh` remettait l'ancien
  conteneur devant un schema en avance sur lui. Une migration Prisma n'ayant
  pas de retour arriere automatique, c'etait le seul point du pipeline ou un
  "non" humain arrivait trop tard.
- **Deploy PROD** (+ API), Blue/Green : uniquement sur tag. Le Blue/Green
  et son monitoring 15 min sont detailles dans
  `01-environnements-dev-preprod-prod.md` (section 3).

### 3.8 Validation post-deploiement (preprod uniquement)

- **E2E Tests, OWASP ZAP** : contre PREPROD deploye, avant la
  promotion vers PROD - c'est le dernier point ou un defaut fonctionnel ou une
  mauvaise configuration de securite peut encore bloquer une release. Le
  framework E2E est **Playwright par defaut** (`e2eFramework: 'playwright'`) ;
  un projet peut basculer sur Cypress via ce meme parametre.

### 3.9 Exception au "build once" : `webappIsEnvSpecific`

Le principe de 3.4 tient pour tout service qui lit sa configuration **au
demarrage** : c'est le cas de l'API NestJS, dont les variables sont injectees
par `docker run` au moment du deploiement. La meme image tourne donc telle
quelle en DEV, PREPROD et PROD.

Il ne tient pas pour un front Next.js. Chaque variable `NEXT_PUBLIC_*` est
**inlinee dans le bundle JavaScript** au moment de `next build` : elle n'est
plus une variable d'environnement une fois l'image construite, mais une chaine
litterale dans le code servi au navigateur. Une image construite pour PREPROD
porte donc l'URL de l'API PREPROD **definitivement**. La promouvoir en PROD
fait appeler l'API de preproduction par le site de production - avec, au
passage, les participations de vrais utilisateurs ecrites dans la base de
preproduction, et un CORS qui rejette l'origine PROD puisque l'API PREPROD ne
la connait pas.

Le drapeau `webappIsEnvSpecific: true` corrige ce cas : sur le chemin tag, le
**front est reconstruit** avec les valeurs de PROD (`Docker Build`, `Trivy
Scan`, `Docker Push` sont donc actifs pour lui), tandis que l'**API reste
promue** bit a bit depuis PREPROD par `Promote Image`. La garantie "meme
binaire qu'en PREPROD" est ainsi conservee partout ou elle a un sens, et
abandonnee explicitement la ou elle est techniquement impossible.

Consequence pratique : c'est aussi ce drapeau qui rend `webappBuildArgsByEnv`
utilisable pour PROD (identifiant Google Analytics, par exemple). Sans lui, ces
valeurs ne sont jamais compilees, puisque aucun build de front n'a lieu sur le
chemin tag.

Tout projet dont le front fige de la configuration au build doit l'activer.
`thetiptop` l'active.

## 4. Stages retires ou deplaces (hors matrice)

- **Build Assets** (supprime) : lancait `npm run build` a la racine, jamais
  consomme par le Dockerfile multi-stage qui refait son propre build. En plus
  de son cout propre, il polluait le workspace et gonflait le contexte Docker
  envoye a `docker build`.
- **Monitor PREPROD** (supprime) : voir
  `01-environnements-dev-preprod-prod.md` section 3 - PREPROD n'a pas de
  mecanisme de rollback automatique pour exploiter une fenetre de
  surveillance, contrairement a PROD (Blue/Green).
- **K6 Performance** (deplace en job planifie) : n'a pas de dependance au
  workspace du build, il tape sur PREPROD deja deploye. Devient
  `Jenkinsfile.k6`, execution hebdomadaire planifiee plutot qu'a chaque push -
  voir `../tools/13-k6.md`.
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

Detaillee dans `../tools/04-harbor.md` (sections 3.9 et 3.10). Resume :

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
- Politique de retention Harbor : `infrastructure/docs/tools/04-harbor.md`
- Environnements et Blue/Green : `infrastructure/docs/how/01-environnements-dev-preprod-prod.md`
