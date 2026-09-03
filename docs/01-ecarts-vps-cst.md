# Ecarts VPS / Cahier des Specifications Techniques

## Objet

Ce document est le **journal des divergences** entre l'infrastructure reellement
deployee sur le VPS et ce que decrit le CST
(`CAHIER_DES_SPECIFICATION_TECHNIQUES.md`).

Le CST est un document de reference : il n'est pas modifie a chaque operation
sur le VPS. Toute modification de l'infrastructure qui rend une affirmation du
CST inexacte, incomplete ou obsolete est donc **consignee ici**, avec la
section du CST concernee et la correction a apporter.

Objectifs :

1. Ne perdre aucune correction a reporter lors de la prochaine revision du CST.
2. Pouvoir justifier en soutenance tout ecart entre le document et le reel :
   un ecart identifie et date est une decision d'ingenierie ; un ecart invisible
   est une erreur.

## Mode d'emploi

A chaque changement sur le VPS, se demander : **est-ce que le CST dit encore
vrai ?** Si non, ajouter une entree ci-dessous.

Statuts : `A REPORTER` (correction en attente) - `REPORTE` (integre au CST)
- `ASSUME` (ecart volontaire et permanent, a justifier).

Format d'une entree :

```
### [AAAA-MM-JJ] Titre court

- **Statut** : A REPORTER | REPORTE | ASSUME
- **Section CST** : numero de section + ligne(s)
- **Le CST dit** : citation ou reformulation fidele
- **Le VPS fait** : etat reel
- **Correction a apporter** : formulation proposee pour le CST
- **Pourquoi** : justification technique
```

---

## Ecarts en cours

### [2026-08-27] Copie hors site : le 3-2-1 n'etait pas reellement applique

- **Statut** : A REPORTER
- **Section CST** : 3.4 c) (l.613) et 4.2 du plan d'integration (l.1112) ;
  responsabilites Admin CI (l.931)
- **Le CST dit** : les sauvegardes sont « stockees sur un support distinct
  selon le principe 3-2-1 », et l'Admin CI doit « garantir l'application du
  principe 3-2-1 ».
- **Le VPS fait** : jusqu'a cette date, l'unique copie Restic vivait dans
  MinIO, **sur le meme VPS et le meme disque** que les services sauvegardes :
  1 copie, 1 support, 0 hors site. Le 3-2-1 etait affirme mais pas atteint.
  La troisieme copie est desormais assuree par `script/backup-pull-local.sh`,
  execute sur le poste local (`restic copy` depuis `s3.wk-archi-...`).
- **Correction a apporter** : preciser dans le CST que MinIO, colocalise avec
  les services, ne constitue ni le second support ni la copie hors site, et
  documenter la copie locale comme troisieme copie.
- **Pourquoi** : une perte du VPS ou de son disque detruisait simultanement les
  donnees et leurs sauvegardes. Voir `tools/09-restic-minio.md`.

### [2026-08-27] Copie hors site declenchee manuellement, non planifiee

- **Statut** : ASSUME
- **Section CST** : 3.4 b) (l.598) — frequence des sauvegardes
- **Le CST dit** : la politique de sauvegarde est automatisee et quotidienne.
- **Le VPS fait** : les sauvegardes VPS -> MinIO sont bien automatisees
  (Ofelia, 02h00). En revanche la copie hors site vers le poste local est
  **declenchee manuellement**, par contrainte d'espace disque sur le poste. La
  planification cron correspondante est ecrite mais commentee en fin de
  `script/backup-pull-local.sh`.
- **Correction a apporter** : mentionner que le RPO hors site depend d'un
  declenchement manuel, et que l'automatisation quotidienne (05h00, apres la
  sauvegarde de 02h00) est prete a activer.
- **Pourquoi** : ecart volontaire lie aux ressources materielles disponibles,
  pas a la conception. A lever des que le stockage local le permet.

### [2026-08-27] Image MinIO figee, distribution communautaire arretee

- **Statut** : A REPORTER
- **Section CST** : tableau des images (l.1630) et tableau des outils (l.1647)
- **Le CST dit** : image `minio/minio:latest` ; MinIO justifie notamment par
  son « interface web » et sa « haute disponibilite ».
- **Le VPS fait** : image epinglee a `minio/minio:RELEASE.2025-09-07T16-13-09Z`.
  MinIO a cesse fin 2025 de publier des images Docker et binaires
  pre-compiles pour l'edition communautaire ; le code reste maintenu en AGPL
  mais **uniquement sous forme de sources**. Cette image est donc la derniere
  officielle et ne recevra plus de correctif par `docker pull`.
- **Correction a apporter** : remplacer `minio/minio:latest` par le tag
  epingle, retirer « interface web » de la justification, et ajouter cette
  dette technique avec sa sortie prevue (compilation depuis les sources).
- **Pourquoi** : `latest` est proscrit sur une brique de sauvegarde (build non
  reproductible). Dette a suivre : MinIO est expose publiquement sur `s3.` et
  `minio.`, donc l'absence de correctifs est un risque reel. La CVE-2025-62506
  (CVSS 8.1, corrigee en `RELEASE.2025-10-15`) n'est pas dans cette image ;
  elle ne concerne toutefois que les comptes de service/STS a session policy,
  pas les utilisateurs a policy attachee.

### [2026-08-27] Console MinIO : administration retiree de l'edition communautaire

- **Statut** : A REPORTER
- **Section CST** : tableau des ports MinIO (l.769) ; plan d'integration,
  acces « MinIO | Web + API | Admin » (l.1021)
- **Le CST dit** : « Console UI | 9001 | Interface web MinIO », laissant
  entendre une administration par navigateur.
- **Le VPS fait** : depuis la release de mai 2025, la console communautaire est
  reduite a un **navigateur d'objets**. La gestion des utilisateurs, policies,
  buckets, configuration et replication se fait exclusivement via `mc`
  (embarque dans l'image, accessible par `docker exec common-minio-ci`).
- **Correction a apporter** : requalifier la console en « navigateur d'objets
  (lecture) » et indiquer `mc` comme outil d'administration de reference.
- **Pourquoi** : impacte le plan d'integration du nouveau collaborateur, qui
  prevoit un acces « Admin » via l'interface web — cet acces n'existe plus.

### [2026-08-27] Utilisateur `backup-reader` : ecriture necessaire sur `locks/`

- **Statut** : A REPORTER
- **Section CST** : 3.4 c) (l.613) « les acces sont restreints et journalises » ;
  gestion des secrets (l.470)
- **Le CST dit** : les acces aux sauvegardes sont restreints.
- **Le VPS fait** : creation d'un utilisateur MinIO dedie `backup-reader`
  (policy `backup-reader-policy`) pour la copie hors site, au lieu des
  credentials root. La policy n'est pas strictement en lecture : elle autorise
  `PutObject`/`DeleteObject` sur le **seul prefixe `locks/`**, car
  `restic copy` pose un verrou sur le depot source et ignore `--no-lock`
  (restic/restic#3518). Les donnees (`data/`, `index/`, `snapshots/`, `config`)
  restent non modifiables et non supprimables depuis le poste.
- **Correction a apporter** : documenter ce compte technique, sa policy et
  l'exception `locks/` dans la section sur les acces backup.
- **Pourquoi** : moindre privilege applique tout en gardant l'outil
  fonctionnel. Le compte entre dans le perimetre de rotation trimestrielle des
  comptes techniques (CST l.470).

### [2026-08-28] Monitor PREPROD supprime du pipeline

- **Statut** : A REPORTER
- **Section CST** : 3.2, tableau des specificites PREPROD (l.1488) et tableau
  comparatif des environnements (l.1572) « Monitoring post-deploy | ... |
  5 min metriques [PREPROD] | 15 min metriques strictes [PROD] »
- **Le CST dit** : PREPROD beneficie d'une fenetre de surveillance post-
  deploiement de 5 minutes, distincte du simple health check DEV et de la
  surveillance stricte 15 min de PROD.
- **Le VPS fait** : ce stage (`Monitor PREPROD`) a ete retire de
  `ciPipeline.groovy`. PREPROD ne conserve que le health check immediat avec
  rollback automatique deja integre a `deployContainer()` (30 tentatives, 2 s
  d'intervalle). Seule PROD garde une fenetre de surveillance active, 15 min,
  dans le Blue/Green (`deployBlueGreen.groovy`).
- **Correction a apporter** : dans le tableau comparatif (l.1572), remplacer
  « 5 min metriques » par « health check avec rollback automatique immediat »
  pour la colonne PREPROD, et retirer la ligne « Tests de performance ... K6
  ... 5 min » du tableau 3.2 des lors que K6 devient un job planifie
  hebdomadaire (voir `tools/13-k6.md`, `how/02-pipeline-ci-cd.md` section 4) plutot
  qu'une etape du pipeline de deploiement.
- **Pourquoi** : `deployContainer()` (DEV/PREPROD) n'a pas de mecanisme de
  Blue/Green a exploiter pendant une fenetre de surveillance — contrairement a
  PROD, un echec detecte a la 3e minute n'aurait rien de plus a faire qu'un
  echec detecte a la 30e seconde par le health check existant. 5 min
  d'attente passive par push sur `main`, agent Jenkins bloque
  (`disableConcurrentBuilds`), sans gain de fiabilite mesurable. Voir
  `how/02-pipeline-ci-cd.md` section 4 et `how/01-environnements-dev-preprod-prod.md`
  section 3.

### [2026-08-28] Quality gates non re-executes sur le chemin PROD (build once)

- **Statut** : A REPORTER
- **Section CST** : 2.9 (l.444) « quality gates automatiques ... avant tout
  deploiement en PREPROD ou en PROD » ; 4. (l.476) « quality gates : linting,
  tests automatises, analyse SonarQube et scans de securite sont obligatoires
  avant tout deploiement en PREPROD et en PROD »
- **Le CST dit** : lu litteralement, ces deux passages laissent entendre que
  Lint, Unit Tests, SonarQube Analysis et Quality Gate s'executent avant
  chaque deploiement, y compris en PROD.
- **Le VPS fait** : sur le chemin tag (PROD), ces quatre stages sont absents
  du pipeline (`ciPipeline.groovy`, conditions `DEPLOY_ENV in ['dev',
  'preprod']`). Le stage `Promote Image` retague dans Harbor l'image
  `main-<sha>` deja construite, lintee, testee et validee par le Quality Gate
  lors du build PREPROD sur ce meme commit — rien n'est reconstruit ni
  re-analyse pour PROD.
- **Correction a apporter** : preciser dans les deux passages que
  l'obligation « avant PROD » est satisfaite par transitivite : c'est le
  build PREPROD du commit promu qui a franchi les quality gates, pas un
  second passage au moment du tag. Ajouter un renvoi vers le principe
  « build once, deploy everywhere » (2.4, l.436).
- **Pourquoi** : c'est la consequence directe et voulue de la promotion
  d'image sans reconstruction (2.4) — reconstruire et re-analyser un binaire
  deja valide n'apporterait aucune information nouvelle et romprait la
  garantie que PROD execute l'image bit-a-bit identique a celle validee en
  PREPROD. Voir `how/02-pipeline-ci-cd.md` sections 3.2 et 3.4.

### [2026-08-28] Gitleaks et Semgrep retires du chemin PROD

> Cette entree remplace « Gitleaks bloque desormais aussi le chemin PROD »
> (meme date). Decision revue le 2026-09-02.

- **Statut** : A REPORTER
- **Section CST** : 4.2 (l.468) Gitleaks « bloque le pipeline aux stades DEV et
  PREPROD » ; tableau 3.2 (l.1490) « Scan securite complet : Trivy (image) +
  Gitleaks (secrets) + OWASP - derniere barriere de securite avant la
  production »
- **Le CST dit** : un scan de securite complet constitue la derniere barriere
  avant la production, au moment de la mise en production.
- **Le VPS fait** : `Gitleaks` et `Semgrep SAST` avaient ete etendus au chemin
  PROD le 2026-08-28 ; ils en sont retires. Les deux operent sur **l'arbre
  source**, or un build de tag ne le modifie pas : c'est le meme commit que
  celui deja construit et scanne sur `main`, et l'existence de l'image
  `main-<sha>` dans Harbor - sans laquelle `Promote Image` echoue - le prouve.
  Le verdict ne pouvait donc etre qu'identique. Semgrep, qui ne pose qu'un
  `unstable`, n'avait de surcroit aucun pouvoir d'arret sur le deploiement
  qu'il precedait. La « derniere barriere » decrite par le CST est celle du
  build PREPROD du commit promu (Trivy sur l'image + Gitleaks + OWASP ZAP),
  completee par le **re-scan quotidien de Harbor** pour les CVE apparues apres
  coup (`tools/04-harbor.md` section 3.8).
- **Correction a apporter** : reformuler le tableau 3.2 (l.1490) pour rattacher
  la barriere securite au build PREPROD plutot qu'au moment du tag, et renvoyer
  vers le re-scan Harbor pour la veille CVE continue. En 4.2, conserver « DEV
  et PREPROD » et y ajouter les branches de travail (voir entree du 2026-09-02).
- **Pourquoi** : deux stages de plusieurs minutes dont le resultat etait
  demontrable a l'avance, sur un agent Jenkins bloque par
  `disableConcurrentBuilds`, pour un pouvoir de blocage nul (Semgrep) ou sans
  objet (Gitleaks). Le seul controle dont le verdict pouvait reellement changer
  entre le build `main` et le tag est Trivy - et c'est precisement celui que
  Harbor rejoue tous les jours, sur toutes les images, sans immobiliser la CI.
  Voir `how/02-pipeline-ci-cd.md` section 3.4.

### [2026-08-28] Retention Harbor : exception non ecrite pour les tags de release

- **Statut** : A REPORTER
- **Section CST** : 7.5 (l.1804) « Harbor conserve les 10 dernieres versions
  taguees par projet et purge les images non taguees apres 7 jours »
- **Le CST dit** : une regle de retention unique, 10 versions taguees par
  projet, sans distinction de type de tag.
- **Le VPS fait** : la politique configuree (voir `tools/04-harbor.md` section 3.8)
  utilise **deux** regles : « 10 plus recentes » sur les tags
  `develop-*`/`main-*`, et **retention illimitee** sur les tags `v*`
  (releases).
- **Correction a apporter** : preciser en 7.5 que la regle des 10 versions ne
  s'applique qu'aux tags de travail (`develop-*`/`main-*`), et que les tags
  de release (`vX.Y.Z`) sont retenus indefiniment.
- **Pourquoi** : une regle unique « retenir les 10 dernieres versions taguees,
  toutes confondues » aurait fini par purger d'anciens tags `v*` des que 10
  images `develop-*`/`main-*` plus recentes existent — soit quelques jours.
  Le stage `Promote Image` (build once, deploy everywhere) et
  `scripts/rollback.sh` dependent tous deux de la presence permanente des
  tags `v*` dans Harbor ; les perdre casse a la fois la capacite de rollback
  et la garantie de reproductibilite du CST (2.4, l.436).

### [2026-08-28] PREPROD : base de donnees vide, pas de copie anonymisee de PROD

- **Statut** : A REPORTER
- **Section CST** : 3.2, tableau des specificites PREPROD (l.1489) « BDD
  anonymisee | Copie des donnees de prod avec anonymisation RGPD | Tester
  avec des donnees realistes sans compromission des donnees personnelles » ;
  plan de reprise (l.2017) « Donnees BDD corrompues | PREPROD | Dump
  anonymise de PROD | 15 min »
- **Le CST dit** : PREPROD est peuplee par une copie anonymisee des donnees
  de PROD, reutilisable comme source de restauration en cas de corruption.
- **Le VPS fait** : le stage `Ensure Database` cree une base **vide**
  (`CREATE DATABASE` idempotent, jamais de `pg_dump`/`pg_restore`/seed dans
  toute la shared library — voir `how/02-pipeline-ci-cd.md` section 3.6). PREPROD
  et DEV partagent la meme instance Postgres, chacune avec sa propre base
  logique, mais aucune n'est jamais alimentee depuis PROD. Il n'existe donc
  pas de dump anonymise mobilisable pour la ligne du plan de reprise (l.2017).
- **Correction a apporter** : soit implementer le pipeline d'anonymisation
  (export PROD -> anonymisation RGPD -> import PREPROD, hors scope des
  phases 3-5 traitees ici), soit, en attendant, corriger le CST pour refleter
  l'etat reel (base PREPROD vide, peuplee par les migrations Prisma et les
  jeux de donnees de test du projet) et retirer la ligne correspondante du
  plan de reprise tant que le mecanisme n'existe pas.
- **Pourquoi** : ecart decouvert en documentant le stage `Ensure Database`
  (phase 3), pas introduit par les phases 3-5 elles-memes — mais laisse tel
  quel, le CST promet une capacite de restauration qui n'existe pas et une
  qualite de recette ("donnees realistes") non garantie sur PREPROD.

### [2026-09-02] Approbation PROD deplacee avant les migrations de base

- **Statut** : A REPORTER
- **Section CST** : 7.2 (l.1588) « Pourquoi une approbation manuelle pour la
  PROD ? » ; tableau comparatif des environnements (l.1566) « Validation +
  deploiements controles »
- **Le CST dit** : une approbation humaine precede la mise en production, comme
  garantie de responsabilite, de conformite et de choix du moment.
- **Le VPS fait** : jusqu'a cette date, le stage `Approval PROD` etait place
  **apres** `Ensure Database` et `Run Prisma Migrations`. Le schema de la base
  de production etait donc migre avant que quiconque ait approuve quoi que ce
  soit : un refus (ou un timeout) laissait la base PROD en avance sur
  l'application restee en place, et `scripts/rollback.sh` remettait l'ancien
  conteneur devant un schema qu'il ne connaissait pas. Le stage est desormais
  place entre `Promote Image` et `Ensure Database`.
- **Correction a apporter** : ajouter en 7.2 que l'approbation est positionnee
  sur la frontiere entre les operations inertes (preparation d'un artefact dans
  Harbor) et les operations irreversibles (migration de schema, bascule de
  trafic), et non simplement « avant le deploiement ».
- **Pourquoi** : une migration Prisma n'a pas de retour arriere automatique.
  C'etait le seul point du pipeline ou un « non » humain arrivait apres un
  effet de bord non annulable, ce qui privait l'approbation de sa raison
  d'etre. Voir `how/02-pipeline-ci-cd.md` section 3.7.

### [2026-09-02] Front Next.js reconstruit pour la PROD (exception au build once)

- **Statut** : ASSUME
- **Section CST** : 2.4 (l.436) « la meme image construite une fois peut etre
  reutilisee a l'identique a travers les environnements sans reconstruction »
- **Le CST dit** : une image unique est promue sans reconstruction, de DEV a
  PROD.
- **Le VPS fait** : ce principe est conserve pour l'API (NestJS lit sa
  configuration au demarrage) mais **abandonne pour le front Next.js**. Chaque
  variable `NEXT_PUBLIC_*` est inlinee dans le bundle JavaScript au moment du
  `next build` : l'image construite pour PREPROD porte definitivement l'URL de
  l'API PREPROD. Le drapeau `webappIsEnvSpecific: true` (active sur
  `thetiptop`) fait donc reconstruire, scanner et pousser le front pour la
  PROD ; l'API, elle, reste promue bit a bit.
- **Correction a apporter** : preciser en 2.4 que la promotion sans
  reconstruction s'applique aux images dont la configuration est lue au
  demarrage, et documenter l'exception des bundles front a configuration figee
  au build.
- **Pourquoi** : sans cette exception, le site de production appelait l'API de
  preproduction - donc ecrivait les participations de vrais utilisateurs dans
  la base de preproduction, quand le CORS de l'API PREPROD ne rejetait pas
  purement et simplement l'origine PROD. L'identifiant Google Analytics de
  production (`webappBuildArgsByEnv`) n'etait par ailleurs jamais compile.
  Voir `how/02-pipeline-ci-cd.md` section 3.9.

### [2026-09-02] Tests unitaires et Gitleaks joues des les branches de travail

- **Statut** : A REPORTER
- **Section CST** : 4. (l.442) « le pipeline echoue automatiquement en cas
  d'echec » des tests ; 4.2 (l.468) Gitleaks « bloque le pipeline aux stades
  DEV et PREPROD »
- **Le CST dit** : les tests et la detection de secrets s'appliquent aux stades
  de deploiement (DEV, PREPROD).
- **Le VPS fait** : `Unit Tests` et `Gitleaks` s'executent desormais aussi sur
  `feature/*`, `release/*` et `hotfix/*`. Jusqu'ici une branche de travail ne
  declenchait que `Lint` : aucun test automatise ne tournait avant la Merge
  Request vers `develop`, et un secret commite n'etait detecte qu'apres cette
  fusion. `SonarQube Analysis` et `Quality Gate` restent exclus de ces branches
  (voir entree suivante).
- **Correction a apporter** : etendre les deux passages aux branches de
  travail, et distinguer explicitement les controles joues des la branche
  (Lint, Unit Tests, Gitleaks) de ceux reserves aux branches deployantes.
- **Pourquoi** : les dependances sont deja installees pour `Lint`, le cout
  marginal est negligeable, et c'etait jusqu'ici le seul retour automatise
  qu'une branche obtenait avant sa Merge Request - la relecture humaine n'est
  pas un substitut a une suite de tests. Pour Gitleaks, la valeur du controle
  depend strictement de sa precocite : un secret pousse sur une branche est
  deja compromis et devra etre revoque. Voir `how/02-pipeline-ci-cd.md`
  sections 3.1 et 3.3.

### [2026-09-02] SonarQube absent des branches de travail : limite de l'edition Community

- **Statut** : ASSUME
- **Section CST** : 4. (l.444) « SonarQube Community Edition » et quality gates
  « avant tout deploiement en PREPROD ou en PROD » ; 5.2 (l.1029) « quality
  gates : linting, tests et scans de securite obligatoires avant tout
  deploiement »
- **Le CST dit** : SonarQube est utilise en edition Community, sans que la
  consequence de ce choix sur la couverture par branche soit enoncee.
- **Le VPS fait** : `SonarQube Analysis` et `Quality Gate` ne tournent que sur
  `develop` et `main`. L'edition Community ne supporte pas l'analyse par
  branche : toute analyse, quelle que soit sa branche d'origine, ecrit dans le
  projet unique et en ecrase les metriques. Analyser une `feature/*` ferait
  donc mentir le Quality Gate de `develop` et de `main`.
- **Correction a apporter** : mentionner explicitement en 4. que l'edition
  Community n'offre pas d'analyse par branche, et que la portee des quality
  gates SonarQube est de ce fait limitee aux branches deployantes.
- **Pourquoi** : contrainte de licence, pas de conception - la lever suppose
  une edition Developer (payante). C'est aussi la raison pour laquelle
  l'extension de la couverture aux branches de travail (entree precedente)
  s'arrete a Lint, Unit Tests et Gitleaks. Voir `tools/05-sonarqube.md` et
  `how/02-pipeline-ci-cd.md` section 3.2.

### [2026-09-02] Install Dependencies saute sur le tag pour les projets sans API

- **Statut** : A REPORTER
- **Section CST** : 3.2, tableau des etapes du pipeline (l.1464 et suivantes)
- **Le CST dit** : l'installation des dependances est une etape du pipeline,
  sans condition d'environnement.
- **Le VPS fait** : sur le chemin tag, `Install Dependencies` n'est desormais
  execute que si le projet declare une API (`hasApi`). Son unique consommateur
  y est `Run Prisma Migrations`, qui resout le CLI Prisma a la version du
  lockfile ; Lint, Unit Tests et SonarQube sont tous absents de ce chemin. Sans
  API, le stage installait donc l'integralite des dependances sans qu'aucun
  stage ne les lise.
- **Correction a apporter** : indiquer la condition dans le tableau des etapes.
- **Pourquoi** : quelques dizaines de secondes et un workspace rempli pour rien
  a chaque mise en production d'un projet sans backend. Voir
  `how/02-pipeline-ci-cd.md` section 3.5.

---

## Ecarts reportes

*(vide — deplacer ici les entrees une fois integrees au CST, en conservant la
date d'origine et en ajoutant la date de report)*
