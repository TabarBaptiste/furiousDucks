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
  donnees et leurs sauvegardes. Voir `09-restic-minio.md`.

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

---

## Ecarts reportes

*(vide — deplacer ici les entrees une fois integrees au CST, en conservant la
date d'origine et en ajoutant la date de report)*
