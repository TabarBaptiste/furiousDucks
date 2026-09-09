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

### [2026-09-08] Signature d'images Harbor, OAuth2/OIDC et 2FA admin annonces mais non configures

- **Statut** : A REPORTER
- **Section CST** : 2.4 (l.436) « [Harbor] assure leur signature » ; Securite
  reseau (l.835) « l'authentification privilegie OAuth2/OIDC pour les services
  qui le permettent [...] et la double authentification pour les acces
  administrateur ».
- **Le CST dit** : Harbor signe les images qu'il stocke ; l'authentification
  des services et des comptes administrateur repose sur OAuth2/OIDC et sur la
  double authentification (2FA).
- **Le VPS fait** : aucun mecanisme de signature d'image (Cosign/Notation) n'est
  configure sur Harbor ; aucune configuration OAuth2/OIDC n'existe dans
  l'infrastructure (GitLab, Jenkins, Grafana... restent en authentification
  locale) ; aucun compte administrateur n'a la 2FA activee.
- **Correction a apporter** : soit implementer la signature d'images (Cosign
  cote Harbor + verification cote deploiement), l'OAuth2/OIDC sur les services
  qui le supportent et la 2FA sur les comptes admin ; soit nuancer le CST pour
  refleter l'etat reel (pas de signature, authentification locale, pas de 2FA).
- **Pourquoi** : ecart de securite annonce dans le CST comme deja en place ; a
  l'oral, une question directe sur la signature d'image ou la 2FA admin
  exposerait l'ecart si le CST n'est pas corrige avant.

### [2026-09-08] Agents Jenkins ephemeres/dedies annonces, mais ciPipeline tourne en `agent any` sur le controleur

- **Statut** : A REPORTER
- **Section CST** : 3\. CI/CD, Serveur CI - Jenkins (l.432) « les agents Jenkins
  sont configures de maniere ephemere (ou dedies) pour isoler les executions
  entre jobs » ; plan de formation (l.1085) « son controller et ses agents
  ephemeres ou dedies ».
- **Le CST dit** : les jobs Jenkins s'executent sur des agents ephemeres ou
  dedies, isoles du controleur.
- **Le VPS fait** : `ciPipeline.groovy` declare `agent any` (l.161) ; toutes
  les etapes tournent directement sur le controleur, qui lance des conteneurs
  `docker run` jetables depuis sa propre socket Docker montee. Les templates
  d'agents Docker du JCasC existent mais ne sont pas utilises - voir l'entree
  du 2026-09-07 ci-dessus sur les 2 executeurs actifs sur le master, qui
  couvre le meme constat cote infrastructure.
- **Correction a apporter** : soit mettre en place les agents Docker dedies
  (templates JCasC deja presents) et corriger `agent any`, soit reformuler
  l.432 et l.1085 pour decrire l'execution reelle (controleur, conteneurs
  jetables via socket montee) tant que la remediation n'est pas faite.
- **Pourquoi** : c'est la meme cause racine que l'entree du 2026-09-07 (pas au
  CST) - ici c'est le CST lui-meme qui affirme une isolation qui n'existe pas,
  ce qui est plus grave qu'une simple omission.

### [2026-09-08] Outils Agile annonces (Taiga/OpenProject, Wiki.js, Jitsi) mais non deployes

- **Statut** : A REPORTER
- **Section CST** : Methodologie de projet a venir, 6. Outils Recommandes
  (l.321-325) ; Annexe D, tableau des outils (l.2810, 2812, 2813).
- **Le CST dit** : Taiga (ou OpenProject) pour la gestion de projet, Jitsi Meet
  pour les reunions a distance, Wiki.js pour la documentation interne, en plus
  de Mattermost pour la communication d'equipe.
- **Le VPS fait** : seul Mattermost est deploye et operationnel dans
  `infrastructure/`. Taiga, OpenProject, Wiki.js et Jitsi n'apparaissent dans
  aucun docker-compose ni aucune configuration Traefik. Le pilotage de projet
  se fait en pratique via Notion, un outil externe non auto-heberge et non
  mentionne au CST.
- **Correction a apporter** : soit deployer les outils annonces (ou au moins
  celui retenu entre Taiga/OpenProject), soit mettre a jour le CST pour
  refleter l'usage reel (Mattermost seul + Notion externe pour le pilotage).
- **Pourquoi** : ecart visible des la premiere question sur l'outillage de
  gestion de projet en soutenance ; impacte aussi le budget/justification
  outillage du CST (Annexe D) qui presente ces outils comme deployes.

### [2026-09-08] Annexe F, exigence 8 : stage d'optimisation des assets supprime du pipeline

- **Statut** : A REPORTER
- **Section CST** : Annexe F, matrice de correspondance exigences/reponses,
  ligne 8 (l.2852) « Optimisation des assets (minification, compression) |
  Stage dedie dans le pipeline Jenkins » ; voir aussi 1.4 (l.392) qui decrit
  cette optimisation comme une etape du build.
- **Le CST dit** : un stage dedie du pipeline Jenkins assure la minification et
  la compression des assets CSS/JS.
- **Le VPS fait** : aucun stage de ce type n'existe dans `ciPipeline.groovy`
  (27 stages recenses, aucun ne porte sur les assets). L'optimisation reelle
  est assuree en amont par le build Docker (bundlers Next.js/Webpack qui
  minifient nativement) et en aval par le middleware de compression de
  Traefik.
- **Correction a apporter** : remplacer « Stage dedie dans le pipeline
  Jenkins » par « assuree par le build Docker (bundler) et le middleware
  compress de Traefik, sans stage pipeline dedie » en l.2852 et l.392.
- **Pourquoi** : le stage decrit n'a jamais existe tel quel ; le mecanisme
  reel est different (build-time + reverse proxy) mais repond bien a
  l'exigence, il suffit de corriger la description du moyen.

### [2026-09-08] Annexe F : "20 exigences identifiees" alors que le tableau en compte 21

- **Statut** : A REPORTER
- **Section CST** : Annexe F (l.2867) « L'ensemble des 20 exigences
  identifiees dans le cahier des charges est couvert... » ; tableau de la
  meme annexe, lignes 1 a 21 (derniere ligne l.2865, exigence 21 « SLA 99,9 %
  sur services critiques »).
- **Le CST dit** : le cahier des charges compte 20 exigences, toutes couvertes.
- **Le VPS fait** : le tableau de correspondance recense 21 lignes numerotees
  de 1 a 21.
- **Correction a apporter** : harmoniser le chiffre en l.2867 sur 21, ou
  retirer/fusionner une ligne du tableau si l'exigence 21 n'est pas distincte
  d'une autre.
- **Pourquoi** : incoherence arithmetique simple mais visible a la lecture,
  facile a corriger.

### [2026-09-08] Annexe I : KPI de disponibilite a 99,9 % incoherent avec le SLA MVP a 99,5 %

- **Statut** : A REPORTER
- **Section CST** : Annexe I, indicateurs de reussite (l.2988-2989) « Taux de
  disponibilite CI/SCM >= 99,9 % » et « Taux de disponibilite production >=
  99,9 % » ; 3.3 (l.550-551) SLA MVP « SCM / CI 99,5 % » et « Environnements
  de production 99,5 % ».
- **Le CST dit** : les KPI de reussite du projet fixent 99,9 % de
  disponibilite CI/SCM et production, alors que le SLA contractuel MVP defini
  en 3.3 est de 99,5 % (99,9 % n'etant atteint qu'apres activation de
  l'option HA, cf. l.576-582).
- **Le VPS fait** : sans objet (KPI cible, pas un etat mesure) - il s'agit
  d'une incoherence interne au document entre la cible KPI et le SLA MVP
  contractualise.
- **Correction a apporter** : aligner les KPI de l'Annexe I sur 99,5 % pour la
  phase MVP (ou preciser qu'ils sont la cible post-HA, avec l'echeance
  correspondante).
- **Pourquoi** : un KPI de reussite plus strict que le SLA contractuel donne
  l'objectif comme deja atteignable en MVP, ce qui n'est pas soutenable tel
  que 3.3 le decrit (l.572, absence de redondance active en MVP).

### [2026-09-08] Durees du projet incoherentes selon les sections (26 / 31 semaines, six mois, dates de debut divergentes)

- **Statut** : A REPORTER
- **Section CST** : page de garde / synthese (l.14) « Date de debut du projet :
  22 janvier 2026 » ; 2. Topologie retenue (l.357) « six mois[^2] » ; H.
  Planning (l.2030) « 31 semaines (du 19 janvier au 21 aout 2026...) » ; note
  de bas de page [^2] (l.3059) « planning previsionnel du projet... etabli sur
  26 semaines ».
- **Le CST dit**, selon la section : 26 semaines (note 2), 31 semaines
  (partie H), ou six mois (partie C), avec un debut au 19 ou au 22 janvier
  selon l'endroit.
- **Le VPS fait** : sans objet (planning, pas un etat d'infrastructure) -
  incoherence purement documentaire entre plusieurs sections du CST.
- **Correction a apporter** : choisir une valeur de reference unique (a priori
  31 semaines / 19 janvier, coherente avec le diagramme de Gantt en partie H,
  qui est la section la plus detaillee) et harmoniser la note [^2], la partie
  C et la synthese en debut de document sur cette meme valeur.
- **Pourquoi** : incoherence visible entre 3-4 sections differentes du meme
  document, susceptible d'etre relevee en soutenance des la premiere question
  sur le planning.

### [2026-09-08] Sommaire du CST desynchronise des titres reellement presents dans le corps du document

- **Statut** : A REPORTER
- **Section CST** : Sommaire (l.40) « 3. Limites et points de vigilance » ;
  sommaire (l.44-70) 7 sous-sections annoncees pour la partie "Introduction au
  mouvement DevOps et DevSecOps" ; corps du document, section B (l.273-329,
  sous-sections 1, 2, 4, 5, 6, 7 - pas de "3.") ; corps du document, partie C
  (l.345-359, seulement 2 sous-sections : "1. Le mouvement DevOps..." et
  "2. Topologie retenue").
- **Le CST dit** (dans le sommaire) : une section B.3 "Limites et points de
  vigilance", et une partie C organisee en 7 sous-sections.
- **Le VPS fait** : sans objet (structure documentaire) - le corps de la
  partie B saute de "2. Avantages de Scrum" a "4. Mise en oeuvre", sans
  section "3." ; le corps de la partie C ne compte que 2 sous-sections
  ("1. Le mouvement DevOps..." et "2. Topologie retenue"), pas 7.
- **Correction a apporter** : soit regenerer le sommaire automatique depuis
  les titres reels du document (le plus fiable, evite toute nouvelle
  divergence), soit renumeroter le corps pour faire correspondre les titres
  au sommaire annonce.
- **Pourquoi** : un sommaire qui ne correspond pas au corps nuit a la
  credibilite du document des la table des matieres, avant meme la lecture du
  contenu.

### [2026-09-08] Approbation de Merge Request "au moins une approbation" non imposable techniquement en GitLab CE

- **Statut** : A REPORTER
- **Section CST** : 2.9 Standards SCM (l.426) « Toute integration passe par
  une Merge Request soumise a au moins une approbation » ; plan de formation
  (l.1032) « une approbation minimum requise » ; checklist DoD (l.2966)
  « Merge Request approuvee (1 approbation minimum) ».
- **Le CST dit** : chaque Merge Request requiert au moins une approbation
  avant fusion, presente comme une regle appliquee techniquement au meme
  titre que la protection des branches `main`/`develop`.
- **Le VPS fait** : GitLab Community Edition ne dispose pas de l'API
  `approval_rules` (reservee aux editions Premium/Ultimate) - il n'existe
  aucun moyen technique d'imposer un nombre minimum d'approbations sur une
  Merge Request en CE. Seule la protection des branches (interdiction de push
  direct sur `main`/`develop`) est reellement appliquee ; `develop` accepte
  par ailleurs les push directs des Developers selon les permissions de
  groupe actuelles - la MR elle-meme n'est donc pas techniquement forcee non
  plus pour ce role.
- **Correction a apporter** : nuancer le CST pour presenter l'approbation
  comme une regle d'equipe (process, DoD) et non un controle technique
  GitLab ; et/ou retirer les Developers du droit de push direct sur `develop`
  pour au moins forcer le passage par MR (sans pouvoir en imposer le nombre
  d'approbations sans passer en edition payante).
- **Pourquoi** : ecart de licence identique dans son principe a celui deja
  documente pour l'analyse SonarQube par branche (entree du 2026-09-02
  ci-dessus) - une regle presentee comme un controle technique n'est en
  realite qu'une convention d'equipe non forcee par l'outil.

### [2026-09-08] Multi-stack : 4 stacks annoncees (node/php/python/ruby), une seule reellement testee

- **Statut** : A REPORTER
- **Section CST** : 2.5 Tests applicatifs (l.442) ; recrutement (l.961) ;
  plan de formation (l.1028) ; tableau des etapes du pipeline (l.1435-1436) ;
  Annexe F, exigence 17 (l.2861) « Adaptation multi-stacks (PHP, Node.js,
  Python, Ruby, Mobile) | Socle commun + configs par stack, Shared Libraries
  Jenkins ».
- **Le CST dit** : le workflow CI/CD supporte 4 stacks (node, php, python,
  ruby), chacune avec ses outils de test dedies (PHPUnit, Jest, Pytest,
  RSpec) et sa configuration dans la Shared Library.
- **Le VPS fait** : `ciPipeline.groovy` prevoit bien une branche de
  configuration par stack (node/php/python/ruby), mais seule la stack `node`
  est reellement encapsulee dans un projet Docker et testee de bout en bout,
  via le projet pilote Thé Tip Top. Les branches php/python/ruby de la Shared
  Library n'ont jamais ete exercees par un build reel.
- **Correction a apporter** : soit tester au moins un projet minimal par
  stack restante (php/python/ruby) pour valider les branches correspondantes
  de `ciPipeline.groovy`, soit nuancer le CST en indiquant que le support
  multi-stack est implemente mais valide uniquement sur `node` a ce stade.
- **Pourquoi** : une demonstration ou une question en soutenance sur une
  stack autre que node exposerait un code jamais execute en conditions
  reelles - le risque n'est pas theorique, c'est du code non teste.
