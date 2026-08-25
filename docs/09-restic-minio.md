# Restic + MinIO - Sauvegardes Automatisees

## 1. Presentation

### Restic

- **Role** : Moteur de backup incremental, chiffre (AES-256) et deduplique
- **Fonctionnement** : Mode CLI dans le conteneur `backup-manager`
- **Pas de port expose**
- **Sauvegarde tous les volumes critiques** : GitLab, Jenkins, Prometheus, Grafana, SonarQube

### MinIO

- **Role** : Stockage objet compatible S3 pour les snapshots Restic
- **Ports** : 9000 (API S3), 9001 (Console)
- **Sous-domaines** :
  - `s3.wk-archi-o24a-4-5-g1.fr` (API S3)
  - `minio.wk-archi-o24a-4-5-g1.fr` (Console web)
- **Bucket** : `furious-ducks-backups`

### Politique de sauvegarde

| Type            | Frequence                    | Retention   |
|-----------------|------------------------------|-------------|
| **Quotidienne** | Tous les jours a 02h00       | 14 jours    |
| **Hebdomadaire**| Chaque dimanche              | 8 semaines  |
| **Mensuelle**   | 1er de chaque mois           | 12 mois     |

**Principe 3-2-1** :
- **3** copies des donnees (volumes Docker + snapshot local + MinIO)
- **2** supports differents (filesystem + stockage objet S3)
- **1** copie distante (MinIO, potentiellement replique)

**Taches planifiees (via Ofelia)** :

| Tache                      | Planification           |
|----------------------------|-------------------------|
| Sauvegarde quotidienne     | Tous les jours a 02h00  |
| Verification d'integrite   | Dimanche a 03h00        |
| Nettoyage / retention      | 1er du mois a 04h00     |

## 2. Configuration

### Conteneur backup-manager

| Parametre       | Valeur                                  |
|-----------------|-----------------------------------------|
| Image           | Custom (voir `infrastructure/backup/Dockerfile`) |
| Container       | `common-backup-manager-ci`              |
| Reseau Docker   | `ci-network`, `backup_network`          |

### Scripts de sauvegarde

```
infrastructure/backup/
|-- Dockerfile
`-- scripts/
    |-- backup-all.sh
    |-- verify-integrity.sh
    |-- retention.sh
    `-- restore.sh
```

### MinIO

| Parametre       | Valeur                     |
|-----------------|----------------------------|
| Image           | `minio/minio`              |
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
- `common-ofelia-ci` (scheduler)

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
# Editer infrastructure/backup/Dockerfile (FROM restic/restic:0.17.0)
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
| Disque MinIO sature | Retention trop longue | Adjuster dans `retention.sh` |
| `repository is already locked` | Backup precedent crashe | `restic unlock` puis relancer |
| Verification echoue | Donnees corrompues | Restic peut auto-reparer avec `--read-data` |
