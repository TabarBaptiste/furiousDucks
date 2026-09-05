# Restic + MinIO - Sauvegardes Automatisees

## 1. Presentation

### Restic

- **Role** : Moteur de backup incremental, chiffre (AES-256) et deduplique
- **Fonctionnement** : Mode CLI dans le conteneur `backup-manager`
- **Pas de port expose**
- **Sauvegarde tous les volumes critiques** : GitLab (config + data), Jenkins,
  Prometheus, Grafana, SonarQube (data + BDD), Mattermost (data + config),
  plus les **dumps logiques `pg_dump`** des BDD SonarQube et Mattermost, et le
  repertoire de donnees **Harbor** (`/data`, sauvegarde hebdomadaire dediee)

### MinIO

- **Role** : Stockage objet compatible S3 pour les snapshots Restic
- **Ports** : 9000 (API S3), 9001 (Console)
- **Sous-domaines** :
  - `s3.wk-archi-o24a-4-5-g1.fr` (API S3)
  - `minio.wk-archi-o24a-4-5-g1.fr` (Console web)
- **Bucket** : `furious-ducks-backups`

### Politique de sauvegarde

Politique appliquee par `retention.sh` (`restic forget --group-by paths`) :

| Politique restic | Valeur |
|------------------|--------|
| `--keep-daily`   | 7      |
| `--keep-weekly`  | 4      |
| `--keep-monthly` | 6      |
| `--keep-yearly`  | 2      |

**Principe 3-2-1** :
- **3** copies : donnees live (volumes Docker) + depot Restic MinIO (VPS)
  + depot Restic sur le poste local
- **2** supports : stockage objet S3 du VPS + disque du poste local
- **1** copie hors site : le depot local, hors du datacenter de l'hebergeur

MinIO tournant sur le **meme VPS** que les services sauvegardes, il ne
constitue ni un second support ni une copie hors site : une perte du serveur
ou de son disque detruirait donnees et sauvegardes ensemble. La troisieme
copie est assuree par `script/backup-pull-local.sh`, execute **sur le poste
local**, qui tire les snapshots via `restic copy` depuis `s3.wk-archi-o24a-4-5-g1.fr`.

La copie reste incrementale et dedupliquee, et les donnees restent chiffrees
AES-256 de bout en bout : la cle Restic ne quitte jamais le poste. L'acces
utilise un utilisateur MinIO dedie **`backup-reader`**, et non les credentials
root. Sa policy `backup-reader-policy` accorde :

| Action | Portee |
|--------|--------|
| `s3:ListBucket`, `s3:GetBucketLocation` | bucket `furious-ducks-backups` |
| `s3:GetObject` | tout le depot |
| `s3:PutObject`, `s3:DeleteObject` | **prefixe `locks/` uniquement** |

L'ecriture est limitee aux verrous parce que `restic copy` pose un verrou sur
le depot **source** et ignore l'option `--no-lock` (cf. restic/restic#3518) :
une policy strictement en lecture ferait echouer la copie. Les donnees de
sauvegarde (`data/`, `index/`, `snapshots/`, `config`) restent donc non
modifiables et non supprimables depuis le poste. Un poste compromis ne peut
que lire des blobs chiffres ; l'acces est revocable et journalise cote MinIO
(CST 3.4c).

Creation de l'utilisateur (l'UI d'administration ayant ete retiree des
releases MinIO recentes, tout passe par `mc`) :

```bash
docker exec -it common-minio-ci sh
mc alias set local http://localhost:9000 <MINIO_ROOT_USER> <MINIO_ROOT_PASSWORD>
mc admin policy create local backup-reader-policy /tmp/policy.json
mc admin user add local backup-reader <SECRET>
mc admin policy attach local backup-reader-policy --user backup-reader
```

Le script applique aussi la meme retention GFS au depot local et y execute un
`restic check` : la copie hors site est verifiee independamment du controle
hebdomadaire du VPS.

**Declenchement** : manuel, par contrainte d'espace disque sur le poste. La
planification quotidienne prevue par le CST (05h00, apres la sauvegarde de
02h00) est ecrite en commentaire en fin de script, prete a etre activee.

**Taches planifiees (via Ofelia)** :

| Tache                          | Planification            | Script |
|--------------------------------|--------------------------|--------|
| Sauvegarde Harbor (registre)   | Dimanche a 01h00         | `backup-harbor.sh` |
| Sauvegarde quotidienne         | Tous les jours a 02h00   | `backup-all.sh` |
| Verification d'integrite       | Dimanche a 03h00         | `verify-integrity.sh` |
| Nettoyage / retention          | 1er du mois a 04h00      | `retention.sh` |
| Metriques DORA (hors backup)   | Tous les jours a 06h00   | `dora_metrics.py` (conteneur `common-dora-metrics-ci`) |

## 2. Configuration

### Conteneur backup-manager

| Parametre       | Valeur                                  |
|-----------------|-----------------------------------------|
| Image           | Custom, base `restic/restic:0.17.0` (voir `infrastructure/backup/Dockerfile`) |
| Container       | `common-backup-manager-ci`              |
| Hostname        | `backup-manager` (fige : restic tague les snapshots avec, et `retention.sh --group-by` en depend) |
| Reseau Docker   | `ci_network` (`pg_dump` SonarQube et Mattermost), `workflow-network` (`pg_dump` des bases projets, cf. `PROJECT_DB_DUMPS`), `backup_network` |

### Scripts de sauvegarde

```
infrastructure/backup/
|-- Dockerfile
`-- scripts/
    |-- backup-all.sh          # sauvegarde quotidienne (volumes + dumps SQL)
    |-- backup-harbor.sh       # sauvegarde hebdomadaire du registre Harbor
    |-- verify-integrity.sh
    |-- retention.sh
    `-- restore.sh
```

### MinIO

| Parametre       | Valeur                     |
|-----------------|----------------------------|
| Image           | `minio/minio:RELEASE.2025-09-07T16-13-09Z` |
| Volume          | `minio-data`               |
| Port API        | 9000                       |
| Port Console    | 9001                       |

### Variables d'environnement

| Variable              | Description                                    |
|-----------------------|------------------------------------------------|
| `RESTIC_REPOSITORY`  | Endpoint S3 du depot Restic (MinIO)            |
| `RESTIC_PASSWORD`    | Mot de passe de chiffrement AES-256            |
| `AWS_ACCESS_KEY_ID`  | Cle d'acces MinIO                              |
| `AWS_SECRET_ACCESS_KEY` | Cle secrete MinIO                           |
| `WEBHOOK_URL`        | Webhook Mattermost utilise par `notify_failure()` des scripts |
| `SONARQUBE_DB_*` / `MATTERMOST_DB_*` | Hote/user/base/mot de passe pour les `pg_dump` logiques |

### Chiffrement

- **Algorithme** : AES-256 cote client
- **Cle** : Definie dans `RESTIC_PASSWORD`
- **Principe** : Les donnees sont chiffrees **avant** l'envoi vers MinIO. MinIO ne voit que des blobs chiffres.

**Avertissement critique** : si tu perds `RESTIC_PASSWORD`, **les backups sont irrecuperables**. Sauvegarde-le dans un coffre-fort (1Password, Bitwarden, KeePass).

## 3. Mise en route

### 3.1 Prerequis

- Le stack principal CI/CD tourne (les volumes a sauvegarder existent).
- `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` et `RESTIC_PASSWORD` sont definis dans `.env`.
- Le DNS `s3.wk-archi-o24a-4-5-g1.fr` et `minio.wk-archi-o24a-4-5-g1.fr` pointent vers le serveur.

### 3.2 Demarrer le stack backup

```bash
cd /opt/furious-ducks/infrastructure
docker compose -f docker-compose.backup.yml up -d
```

Cela demarre **3 conteneurs** :
- `common-minio-ci` (stockage)
- `common-backup-manager-ci` (Restic + scripts)
- `common-ofelia-ci` (scheduler, qui pilote aussi le job DORA du stack monitoring)

### 3.3 Verifier que tout tourne

```bash
docker ps --filter name=common-minio-ci
docker ps --filter name=common-backup-manager-ci
docker ps --filter name=common-ofelia-ci
```

### 3.4 Premier login MinIO Console

1. Ouvre `https://minio.wk-archi-o24a-4-5-g1.fr`
2. User : `MINIO_ROOT_USER` du `.env`
3. Password : `MINIO_ROOT_PASSWORD` du `.env`

### 3.5 Creer le bucket de backups

1. Dans la Console MinIO : **Buckets > Create Bucket**
2. Bucket Name : `furious-ducks-backups`
3. Versioning : Suspended (pas necessaire, Restic gere lui-meme)
4. Object Locking : Off
5. **Create Bucket**

### 3.6 Lancer le premier backup pour initialiser le depot Restic

Restic doit etre **initialise** une fois avant utilisation. Le script `backup-all.sh` le fait automatiquement.

```bash
docker exec common-backup-manager-ci /scripts/backup-all.sh
```

Suivre l'execution :

```bash
docker exec common-backup-manager-ci tail -f /var/log/backup/backup-*.log
```

A la fin, tu dois voir : `All backups completed successfully`.

### 3.7 Verifier que les snapshots existent

```bash
docker exec common-backup-manager-ci restic snapshots
# Tu dois voir une liste de snapshots, un par volume sauvegarde
```

Va aussi dans la Console MinIO > bucket `furious-ducks-backups` > tu dois voir des dossiers `data/`, `index/`, `keys/`, `snapshots/` (structure interne Restic).

### 3.8 Verifier la planification automatique

```bash
docker logs common-ofelia-ci
# Tu dois voir : "Job 'backup-daily' has been registered"
# "Next execution at 02:00"
```

## 4. Utilisation au quotidien

### 4.1 Verifier l'etat des sauvegardes

```bash
# Lister les 10 derniers snapshots
docker exec common-backup-manager-ci restic snapshots --latest 10

# Statistiques du depot
docker exec common-backup-manager-ci restic stats

# Espace utilise
docker exec common-backup-manager-ci restic stats --mode raw-data
```

### 4.2 Lancer un backup manuel (avant une operation risquee)

```bash
docker exec common-backup-manager-ci /scripts/backup-all.sh
```

Pour backupper un seul volume :

```bash
docker exec common-backup-manager-ci restic backup /backup-sources/jenkins-data --tag jenkins --tag manual
```

### 4.3 Verifier l'integrite

```bash
docker exec common-backup-manager-ci /scripts/verify-integrity.sh
```

Ou manuellement :

```bash
docker exec common-backup-manager-ci restic check
# Lecture rapide
docker exec common-backup-manager-ci restic check --read-data
# Lecture complete (tres long)
```

### 4.4 Restaurer un fichier specifique

```bash
# Voir les fichiers d'un snapshot
docker exec common-backup-manager-ci restic ls latest

# Restaurer un fichier precis vers /tmp
docker exec common-backup-manager-ci restic restore latest \
  --target /tmp/restore \
  --include /backup-sources/gitlab-data/git-data/repositories/<group>/<projet>.git
```

### 4.5 Restaurer un volume complet

Voir la procedure detaillee dans le **Cahier des Specifications Techniques** chapitre "Procedure de Restauration".

Resume :

```bash
# 1. Lister les snapshots et choisir un ID
docker exec common-backup-manager-ci restic snapshots

# 2. Restaurer dans /tmp/restore
docker exec common-backup-manager-ci /scripts/restore.sh <SNAPSHOT_ID> /tmp/restore

# 3. Stopper le service concerne
docker compose stop jenkins

# 4. Vider le volume
docker volume rm common-jenkins-data-ci
docker volume create common-jenkins-data-ci

# 5. Copier les donnees restaurees dans le nouveau volume
docker run --rm -v common-jenkins-data-ci:/dest -v /tmp/restore:/src alpine \
  cp -a /src/backup-sources/jenkins-data/. /dest/

# 6. Redemarrer
docker compose up -d jenkins
```

### 4.6 Faire un test de restauration trimestriel

C'est une **exigence** : un backup non teste = un backup non fiable.

1. Restaurer un snapshot dans un repertoire temporaire
2. Comparer avec les donnees actuelles
3. Documenter la duree et le succes
4. Conserver le rapport

### 4.7 Acceder a la console MinIO

`https://minio.wk-archi-o24a-4-5-g1.fr` permet de :
- Naviguer dans le bucket
- Voir l'espace utilise
- Configurer la replication vers un autre site
- Creer des users supplementaires (lecture seule pour audit)

### 4.8 Mettre a jour Restic / MinIO

```bash
# Editer infrastructure/backup/Dockerfile (ligne FROM restic/restic:0.17.0)
docker compose -f docker-compose.backup.yml build backup-manager
docker compose -f docker-compose.backup.yml up -d backup-manager

# MinIO
docker compose -f docker-compose.backup.yml pull minio
docker compose -f docker-compose.backup.yml up -d minio
```

## 5. Tests de validation

### Verification de MinIO

```bash
curl -f https://s3.wk-archi-o24a-4-5-g1.fr/minio/health/live
# HTTP 200 attendu
```

Console : `https://minio.wk-archi-o24a-4-5-g1.fr` (login MINIO_ROOT_*).

### Lancer un backup manuel

```bash
docker exec common-backup-manager-ci /scripts/backup-all.sh
docker logs common-backup-manager-ci --tail 50 -f
```

### Lister les snapshots

```bash
docker exec common-backup-manager-ci restic snapshots
docker exec common-backup-manager-ci restic snapshots --tag gitlab
```

### Verifier l'integrite

```bash
docker exec common-backup-manager-ci restic check
docker exec common-backup-manager-ci /scripts/verify-integrity.sh
```

### Test de restauration

```bash
docker exec common-backup-manager-ci /scripts/restore.sh latest /tmp/test-restore
docker exec common-backup-manager-ci ls -la /tmp/test-restore
```

### Verifier les statistiques

```bash
docker exec common-backup-manager-ci restic stats
```

### Verification de la planification

```bash
docker logs common-ofelia-ci --tail 50
docker exec common-backup-manager-ci restic snapshots --latest 1
```

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| `unable to open config file` | Repo Restic pas initialise | Lancer `backup-all.sh` (init auto) |
| `wrong password` | RESTIC_PASSWORD different | Verifier .env / variable d'env |
| MinIO inaccessible | Bucket inexistant | Creer le bucket via Console |
| Backup tres long | Premiere fois (full) | Normal, suivants incrementaux |
| Disque MinIO sature | Retention trop longue | Ajuster les `KEEP_*` en tete de `retention.sh` |
| `repository is already locked` | Backup precedent crashe | `restic unlock` puis relancer |
| Verification echoue | Donnees corrompues | Restic peut auto-reparer avec `--read-data` |
