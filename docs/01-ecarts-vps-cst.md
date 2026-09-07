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

### [2026-09-04] Ports publies et acces Docker : le CST decrit plus d'exposition que le VPS

- **Statut** : A REPORTER
- **Section CST** : Securite reseau (l.828) ; CI Server - Jenkins (l.683-687) ; cAdvisor (l.747) ; Annexe E (l.2851, 2857, 2864)
- **Le CST dit** : les ports 8080 (dashboard Traefik) et 8081 (cAdvisor) sont
  « publies sur l'hote a des fins de debug », le port 50000 « permet la
  connexion des agents Jenkins en JNLP » et est publie ; Jenkins « declenche
  les deploiements via l'API Docker en TLS sur le port 2376 » ; les agents sont
  « des conteneurs ephemeres fonctionnant en Docker-in-Docker ».
- **Le VPS fait** : seuls 80, 443 et 2222 sont publies (plus 127.0.0.1:1514,
  syslog interne de Harbor). Le dashboard Traefik n'est joignable qu'en HTTPS
  avec basic auth ; cAdvisor (:8080) et l'entrypoint metriques de Traefik
  (:8082) ne sont visibles que depuis ci_network. Jenkins utilise la socket
  /var/run/docker.sock montee (Docker-outside-of-Docker), aucun TCP 2376.
  Aucun agent JNLP n'est connecte : ciPipeline tourne en `agent any` (2
  executeurs sur le controleur) et lance chaque outil dans un `docker run`
  jetable ; les templates d'agents Docker du JCasC ne sont pas utilises.
- **Correction a apporter** : retirer 8080, 8081 et 50000 de la liste des
  ports publies (Securite reseau, Annexe E, tableau cAdvisor) ; decrire l'acces
  Docker par socket et l'execution des etapes en conteneurs jetables.
- **Pourquoi** : le CST decrit une surface d'exposition plus large que la
  realite ; a l'oral, annoncer la version la plus stricte, qui est la vraie.

### [2026-09-07] Jenkins execute les builds directement sur le controleur (2 executeurs actifs sur le master)

- **Statut** : A REPORTER
- **Section CST** : aucune — ni le nombre d'executeurs du controleur, ni la
  presence/absence d'agents Jenkins dedies n'est mentionnee dans le CST
  actuel. Consigne ici plutot que dans le CST : voir "Pourquoi".
- **Constat** : `ciPipeline.groovy` declare `agent any` et le controleur
  Jenkins est configure avec 2 executeurs actifs ; chaque etape du pipeline
  (Lint, Tests, scans, build) s'execute donc directement sur le noeud
  principal, via des conteneurs `docker run` jetables lances depuis sa propre
  socket Docker montee. Aucun agent Jenkins distant/dedie n'est utilise.
- **Le probleme** : Jenkins deconseille explicitement de batir sur le noeud
  master pour des raisons de securite (acces direct du build au contexte du
  controleur, a ses credentials et a sa JVM) et recommande de regler le
  nombre d'executeurs du noeud principal a 0, en deportant l'execution sur
  des agents dedies.
- **Correction a apporter** : pas au CST — la remediation porte sur
  l'infrastructure elle-meme : mettre `numExecutors: 0` sur le controleur
  dans le JCasC et introduire des agents Docker dedies (les templates JCasC
  existent deja mais ne sont pas utilises) pour executer les etapes du
  pipeline hors du contexte du controleur.
- **Pourquoi consigne ici et pas dans le CST** : documenter ce choix dans le
  CST (document de reference, pas un audit de securite) reviendrait a
  presenter une pratique deconseillee par l'outil lui-meme comme un fait
  d'architecture acquis. Le sujet reste neanmoins reel et a traiter — il est
  donc trace ici pour ne pas se perdre, en attendant la mise en place
  d'agents dedies.

### [2026-09-04] Reseaux Docker : roles inverses et environnements projets sur workflow-network

- **Statut** : A REPORTER
- **Section CST** : Convention de nommage, paragraphe « Reseaux » (l.872) ;
  tableau 1.3 Conventions de nommage, ligne « Reseau Docker » (l.1444) ;
  cibles Prometheus (l.720-722)
- **Le CST dit** : « workflow-network regroupe les services CI/CD : Traefik,
  GitLab, SonarQube » et « ci_network est le reseau principal partage » ; les
  environnements DEV/PREPROD/PROD sont sur ci_network ; Prometheus scrape
  Harbor et cAdvisor sur 8081.
- **Le VPS fait** : ci_network est le reseau du socle, celui ou Traefik
  decouvre les services (providers.docker.network) ; workflow-network est le
  reseau transverse externe ou vivent tous les conteneurs des projets
  (thetiptop-*), auquel les services du socle sont aussi attaches ;
  monitoring_network et backup_network (internal) sont propres a leur stack.
  Cibles reelles : Harbor via le conteneur nginx:9090, cAdvisor :8080,
  Traefik :8082, MinIO :9000 (bearer token), postgres-exporter :9187
  (SonarQube et Mattermost), Loki :3100, Grafana :3000 ; SonarQube n'est pas
  scrape (endpoint reserve a l'edition Developer, couvert par ContainerDown).
- **Correction a apporter** : reecrire le paragraphe « Reseaux » et la ligne
  du tableau 1.3 avec les roles reels ; corriger la table des cibles.
- **Pourquoi** : le diagramme d'infrastructure est presente a l'oral ; un
  `docker network inspect` montre l'inversion.

### [2026-09-04] Procedures de sauvegarde et de restauration : noms, chemins et perimetre differents du VPS

- **Statut** : A REPORTER
- **Section CST** : Sauvegarde manuelle 4.1 (l.1680) ; matrice 6.1 et tableau
  6.2 (l.1740) ; Restauration complete 2.2 (l.1817, 1838) ; Restauration PROD
  5.3 (l.1942)
- **Le CST dit** : `ssh furious-admin@vps-principal -p 2222`,
  `cd /opt/workflow-furious-ducks/infrastructure`, `docker exec backup-manager`,
  `docker volume rm gitlab-config gitlab-data jenkins-data prometheus-data
  grafana-data` ; « Donnees Portainer » parmi les elements non sauvegardes ;
  Mattermost absent de la matrice des elements sauvegardes.
- **Le VPS fait** : le port 2222 est celui du SSH GitLab, pas de l'hote ; le
  depot est dans /opt/furious-ducks/infrastructure ; le conteneur s'appelle
  common-backup-manager-ci ; les volumes suivent la convention de l'Annexe G
  (common-gitlab-config-ci, common-jenkins-data-ci, ...) ; Portainer n'est pas
  deploye ; Mattermost (donnees, config, dump pg_dump) est sauvegarde chaque
  nuit. Depuis le 2026-09-04, les bases et volumes des projets clients
  (thetiptop-db-*, thetiptop-redis-*) sont eux aussi sauvegardes : dumps
  pg_dump via PROJECT_DB_DUMPS et copies des volumes sous
  /backup-sources/projects/ (CST 3.4 a desormais exact sur ce point).
- **Correction a apporter** : aligner les commandes sur les noms reels (ou
  renvoyer vers docs/runbook-administration.md), retirer Portainer, ajouter
  Mattermost et les bases projets a la matrice 6.1.
- **Pourquoi** : une procedure de restauration copiee-collee telle quelle
  echouerait ; c'est le document que l'ingenieur DevOps est cense suivre en
  incident.

### [2026-09-05] Tag d'image Ofelia inexistant : toute recreation de la stack backup echouait

- **Statut** : A REPORTER
- **Section CST** : 1.1 Composants du systeme de backup (l.1617), ligne
  « Backup Scheduler (Ofelia) | mcuadros/ofelia:latest »
- **Le CST dit** : l'ordonnanceur est `mcuadros/ofelia:latest`.
- **Le VPS fait** : le conteneur en service tourne bien sur
  `mcuadros/ofelia:latest`, mais `docker-compose.backup.yml` et
  `docs/tools/17-ofelia.md` reclamaient `mcuadros/ofelia:v0.3.22` - **un tag
  qui n'existe pas** sur Docker Hub (le tag publie est `0.3.22`, sans `v`).
  Consequence : `docker compose -f docker-compose.backup.yml up -d` echouait sur
  `failed to resolve reference`, et **aucun job planifie n'aurait redemarre** -
  ni la sauvegarde quotidienne, ni la verification, ni la retention. Le defaut
  est reste invisible tant que personne ne recreait le conteneur. Corrige en
  `mcuadros/ofelia:0.3.22`, dont le digest
  (`sha256:efcbe2c5...f97e492`) est identique a celui de l'image en service :
  la correction ne change pas le binaire execute.
- **Correction a apporter** : remplacer `latest` par `0.3.22` dans le tableau
  des images du CST, coherent avec la regle d'epinglage deja appliquee a MinIO.
- **Pourquoi** : un tag epingle mais faux est plus dangereux qu'un `latest` -
  il donne l'illusion de la reproductibilite et ne se manifeste qu'au pire
  moment, une reconstruction apres incident.

### [2026-09-05] Reglages Docker de l'hote ecrits mais jamais charges par le demon

- **Statut** : A REPORTER
- **Section CST** : 3.4 Politique de sauvegarde et Outils d'administration /
  Maintenance (l.874) ; Annexe I, KPI « Conformite des backups »
- **Le CST dit** : la maitrise de l'espace disque releve de l'exploitation
  courante, sans preciser de mecanisme.
- **Le VPS fait** : `/etc/docker/daemon.json` declare une rotation des logs
  (50 Mo x 3) et un GC BuildKit (`maxUsedSpace: 25GB`), mais le fichier date du
  2026-09-03 alors que le demon tourne sans interruption depuis le 2026-07-08 :
  ces reglages ne sont lus qu'au demarrage du demon et **n'ont jamais pris
  effet**. Mesures du 2026-09-05 avant correction :
  `HostConfig.LogConfig.Config = {}` sur tous les conteneurs, journal de
  `common-gitlab-ci` a 797 Mo, cache de build a 57 Go pour un plafond declare a
  25 Go, 25,8 Go d'images inutilisees. Le disque etait a 76 % (146 Go / 193 Go).
- **Correction a apporter** : documenter dans le CST que la maitrise du disque
  repose sur deux jobs Ofelia quotidiens (`prune-workspaces-daily` 05:30,
  `prune-docker-images-daily` 05:45) et non sur les seuls reglages du demon,
  qui restent a activer lors de la prochaine fenetre de maintenance.
- **Pourquoi** : un redemarrage du demon Docker redemarre tous les conteneurs
  de la plateforme ; il n'a pas ete realise, la plateforme etant utilisee par
  l'equipe. Les scripts appliquent le meme plafond sans interruption de service.

### [2026-09-05] Politique de retention Harbor documentee mais jamais configuree

- **Statut** : A REPORTER
- **Section CST** : 7.5 « Pourquoi un backup hebdomadaire pour Harbor plutot que
  quotidien ? » (l.1785) - « Harbor conserve les 10 dernieres versions des tags
  Docker de travail (develop-*, main-*) par projet, retient sans limite les tags
  de release (vX.Y.Z) [...] et purge les images non taguees apres 7 jours »
- **Le CST dit** : cette politique est appliquee, et sert de justification au
  rythme hebdomadaire de la sauvegarde du registre (« le contenu sauvegarde est
  donc deja nettoye de l'essentiel du superflu »).
- **Le VPS fait** : jusqu'au 2026-09-05, **aucune politique n'etait attachee au
  projet** `furious-ducks` (l'API `projects/furious-ducks/metadatas` ne renvoyait
  pas de `retention_id`) et **aucune garbage collection n'etait planifiee**. Le
  registre contenait 130 artefacts sur `thetiptop` (94 tags `develop-*`) et 38
  sur `thetiptop-api`. La procedure existait bien dans `tools/04-harbor.md`
  sections 3.9 et 3.10, mais n'avait jamais ete executee : la sauvegarde
  hebdomadaire portait donc sur un registre non purge, contrairement a la
  justification du CST. Desormais : politique `retention_id = 1` planifiee le
  samedi 05:00, GC planifiee le samedi 07:00 avec `delete_untagged`. Premiere
  execution : 130 -> 29 et 38 -> 12 artefacts, `/data/registry` de 3,5 Go a
  870 Mo, les quatre tags `v*`, `latest` et les images deployees conserves
  (controle avant/apres).
- **Correction a apporter** : aucune sur le fond - le CST decrivait la cible,
  elle est maintenant reelle. Preciser seulement les horaires (samedi 05:00 et
  07:00) et que la purge des images non taguees est portee par la GC.
- **Pourquoi** : ecart typique entre une procedure ecrite et son application.
  Il ne se voyait pas dans la documentation, seulement dans l'etat de Harbor.

---

## Ecarts reportes

### [2026-08-27] Copie hors site : le 3-2-1 n'etait pas reellement applique

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

### [2026-08-27] Image MinIO figee, distribution communautaire arretee

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

### [2026-09-02] Tests unitaires et Gitleaks joues des les branches de travail

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

### [2026-09-02] Install Dependencies saute sur le tag pour les projets sans API

- **Statut** : REPORTE
- **Reporte le** : 2026-09-04, CST v2 (version de travail)
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

### [2026-09-03] Seuil de couverture local (Jest) durci au-dela du plancher du CST

- **Statut** : A REPORTER
- **Section CST** : 4., tableau des criteres de passage (l.1389) « Quality Gate
  SonarQube ? | Automatique | Maintient les standards de qualite de l'agence
  (couverture > 70%, pas de vulnerabilites critiques) » ; 3.2, specificites
  PROD (l.1491) « Quality Gate strict | Couverture >= 70%, 0 vulnerabilite
  critique, rating A » ; indicateurs de reussite (l.3055) « Couverture de
  tests (SonarQube) >= 70% (nouveaux projets) ».
- **Le CST dit** : un seuil de couverture unique, 70%, presente comme le
  standard de l'agence et verifie par le Quality Gate SonarQube — sans
  distinguer un plancher organisationnel d'un objectif par projet.
- **Le VPS fait** (perimetre `thetiptop`) : en plus du Quality Gate
  SonarQube, chaque sous-projet declare son propre seuil local dans son
  `jest.config.js`, verifie des le stage `Unit Tests`, avant meme l'analyse
  Sonar. Ces seuils sont volontairement plus stricts que le plancher de 70%
  et suivent la couverture reellement atteinte, en cliquet (jamais abaisses,
  releves au fil des tests ajoutes) : `api/jest.config.js` est a
  90/75/90/90 (statements/branches/functions/lines) pour une couverture
  reelle de 95.88/81.61/92.67/96.15 ; `webapp/jest.config.js` vient d'etre
  releve de 70/65/68/70 a 88/87/84/89, a la suite de l'ajout de tests sur
  `verification-step.tsx`, `lib/api.ts` et `header.tsx` qui a porte la
  couverture reelle du webapp de 80.1/77.04/76.53/81.53 a
  90.58/91.05/87.75/92.17. Le CST ne mentionne ni ce mecanisme de seuil
  local en cliquet, ni le fait que 70% n'est ici qu'un minimum.
- **Correction a apporter** : preciser dans le CST que 70% est un plancher
  organisationnel verifie par le Quality Gate SonarQube, et que chaque
  projet peut (et ici, doit) le durcir localement via un seuil Jest en
  cliquet aligne sur sa couverture reelle, sans remettre en cause le seuil
  Sonar de reference.
- **Pourquoi** : sans cette precision, une future revision du CST risque de
  lire 70% comme un plafond a ne pas depasser plutot qu'un minimum a ne
  jamais repasser sous — et ne trace pas que `thetiptop` applique deja un
  standard de couverture plus exigeant que celui de l'agence.

### [2026-09-06] Quality Gate SonarQube : le CST decrit un gate "Furious Ducks" a 70% qui n'existe pas ; le gate reel est "Sonar way", a 80% sur le nouveau code

- **Statut** : A REPORTER
- **Section CST** : 4., tableau des criteres de passage (l.1393) « Quality Gate
  SonarQube ? | Automatique | Maintient les standards de qualite de l'agence
  (couverture > 70%, pas de vulnerabilites critiques) » ; 3.2, specificites
  PREPROD (l.1494) « Quality Gate strict | Couverture >= 70%, 0 vulnerabilite
  critique, rating A ». Memes valeurs reprises dans `docs/00-getting-started.md`
  (l.270, 555) et `docs/tools/05-sonarqube.md` (l.10, 53, 118, 291), qui decrivent
  un gate nomme « Furious Ducks » avec ces conditions.
- **Le CST dit** : le quality gate applicable exige une couverture >= 70%,
  0 vulnerabilite critique et un rating A, sans preciser si cette couverture
  porte sur l'ensemble du projet ou sur le code nouvellement ecrit.
- **Le VPS fait** : interrogation directe de l'API `common-sonarqube-ci`
  (`GET /api/qualitygates/list`, `GET /api/qualitygates/get_by_project?project=thetiptop`) :
  aucun gate « Furious Ducks » n'existe sur le serveur. Le seul gate present
  est **`Sonar way`** (integre, `isBuiltIn: true`), et `thetiptop` y est
  rattache par defaut. Ses conditions reelles (`GET /api/qualitygates/show?name=Sonar%20way`) :
  `new_coverage < 80` (echec), `new_violations > 0` (echec), `new_duplicated_lines_density > 3` (echec),
  `new_security_hotspots_reviewed < 100` (echec) — toutes portant sur le
  **nouveau code** (delta depuis la derniere analyse de reference), pas sur le
  projet entier. Il n'existe ni condition `rating`, ni condition de
  vulnerabilite critique au sens litteral : `new_violations > 0` est en
  pratique plus strict (bloque sur toute nouvelle issue, quelle que soit sa
  severite).
- **Correction a apporter** : remplacer les deux occurrences du CST (l.1393,
  l.1494) par les conditions reelles du gate `Sonar way` sur le nouveau code
  (couverture >= 80%, 0 nouvelle issue, duplication <= 3%, 100% des hotspots
  de securite revus), et soit aligner `00-getting-started.md` et
  `tools/05-sonarqube.md` sur ces valeurs, soit creer effectivement un gate
  custom « Furious Ducks » a 70% sur SonarQube si ce seuil reste la cible
  voulue pour l'agence (auquel cas l'associer explicitement au projet et
  revalider le CST en consequence).
- **Pourquoi** : c'est le meme type d'ecart que l'entree REPORTE du
  2026-08-27 sur la retention Harbor - une procedure/un seuil documente mais
  jamais applique sur l'outil reel. Ici l'ecart est plus net : le seuil
  documente (70%, gate custom) n'a jamais existe sur le serveur, celui qui
  s'applique reellement (80%, `Sonar way`) est a la fois plus strict et de
  portee differente (nouveau code, pas le projet entier) — ce qui a un effet
  direct sur ce qui bloque ou non un build en PREPROD/PROD. A rapprocher de
  l'entree du 2026-09-03 sur les seuils Jest locaux : ces seuils par-projet
  restent corrects et independants de ce constat, mais leur justification
  ("plancher organisationnel a 70% verifie par le Quality Gate SonarQube")
  doit etre mise a jour avec le vrai seuil.
