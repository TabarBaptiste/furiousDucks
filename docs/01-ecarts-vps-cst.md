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

### [2026-08-28] Gitleaks bloque desormais aussi le chemin PROD

- **Statut** : A REPORTER
- **Section CST** : 4.2 (l.468) « Gitleaks ... bloque le pipeline aux stades
  DEV et PREPROD si des secrets exposes sont trouves »
- **Le CST dit** : le blocage Gitleaks en pipeline couvre DEV et PREPROD.
- **Le VPS fait** : le stage `Gitleaks` s'execute aussi sur le chemin PROD
  (`DEPLOY_ENV in ['dev', 'preprod', 'prod']`), avant `Promote Image`.
- **Correction a apporter** : etendre la phrase a « DEV, PREPROD et PROD ».
- **Pourquoi** : couverture volontairement elargie lors de la remise a plat
  des stages (phase 3) — aucune raison de laisser passer un secret sur le
  seul chemin qui publie en production sous pretexte qu'il ne reconstruit pas
  l'image. Cout marginal (quelques secondes).

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

---

## Ecarts reportes

*(vide — deplacer ici les entrees une fois integrees au CST, en conservant la
date d'origine et en ajoutant la date de report)*
