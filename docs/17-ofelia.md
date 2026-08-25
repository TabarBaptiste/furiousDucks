# Ofelia - Planificateur Cron Docker-Native

## 1. Presentation

- **Role** : Scheduler cron pour conteneurs Docker, declenche les taches de backup automatisees
- **Alternative Docker-native** a crontab (pas besoin de cron sur l'hote)
- **Pas de port expose**
- **Pas de sous-domaine**
- **Configuration par labels Docker** (declarative, versionnee dans le compose)
- **Leger** : image < 20 Mo, tres peu de ressources
- **Gere 3 jobs** : backup quotidien, verification hebdomadaire, retention mensuelle
- **Execution via `docker exec`** sur le conteneur `common-backup-manager-ci`

### Architecture

```
+-----------------+        +--------------------+        +-------------+
| Ofelia          | lit    | docker-compose.    |        |             |
| (cron scheduler)| <----- | backup.yml         |        |             |
| :unix socket    |        | (labels ofelia.*)  |        |             |
+-----------------+        +--------------------+        |             |
        |                                                |             |
        | declenche a l'heure prevue                     |             |
        | (02:00 / dimanche 03:00 / 1er 04:00)           |             |
        v                                                |             |
+-----------------------------+                          |   MinIO     |
| docker exec                 |                          |   (S3)      |
| common-backup-manager-ci    | --->  Restic CLI  --->   |             |
| /scripts/backup-all.sh      |                          |             |
+-----------------------------+                          +-------------+
```

Ofelia surveille le socket Docker, lit les labels `ofelia.job-exec.*` sur les conteneurs et declenche les commandes a l'heure prevue. Aucune configuration externe : tout est dans le `docker-compose.backup.yml`.

### Types de jobs Ofelia

| Type | Description | Utilise ici ? |
|------|-------------|---------------|
| `job-exec` | Execute une commande dans un conteneur deja demarre | Oui (nos 3 jobs) |
| `job-run` | Lance un conteneur pour executer la commande | Non |
| `job-local` | Execute une commande sur l'hote | Non |
| `job-service-run` | Execute dans un service Swarm | Non |

## 2. Configuration

### Image et conteneur

| Parametre | Valeur |
|-----------|--------|
| Image | `mcuadros/ofelia:v0.3.12` |
| Container | `common-ofelia-ci` |
| Volume | `/var/run/docker.sock:ro` (lecture seule) |
| Reseau | `backup_network` |

### Jobs planifies (labels Docker)

Dans `infrastructure/docker-compose.backup.yml`, sur le service `ofelia` :

```yaml
labels:
  # Backup quotidien a 02:00
  - "ofelia.job-exec.backup-daily.schedule=0 0 2 * * *"
  - "ofelia.job-exec.backup-daily.container=common-backup-manager-ci"
  - "ofelia.job-exec.backup-daily.command=/scripts/backup-all.sh"

  # Verification integrite dimanche a 03:00
  - "ofelia.job-exec.verify-weekly.schedule=0 0 3 * * 0"
  - "ofelia.job-exec.verify-weekly.container=common-backup-manager-ci"
  - "ofelia.job-exec.verify-weekly.command=/scripts/verify-integrity.sh"

  # Retention 1er du mois a 04:00
  - "ofelia.job-exec.retention-monthly.schedule=0 0 4 1 * *"
  - "ofelia.job-exec.retention-monthly.container=common-backup-manager-ci"
  - "ofelia.job-exec.retention-monthly.command=/scripts/retention.sh"
```

### Syntaxe Cron Ofelia (6 champs)

Attention : Ofelia utilise le format Go (6 champs, incluant les secondes) :

```
+---------- secondes (0-59)
| +-------- minutes (0-59)
| | +------ heures (0-23)
| | | +---- jour du mois (1-31)
| | | | +-- mois (1-12)
| | | | | + jour de la semaine (0-6, 0=dimanche)
| | | | | |
0 0 2 * * *   -> tous les jours a 02:00:00
0 0 3 * * 0   -> dimanche a 03:00:00
0 0 4 1 * *   -> le 1er du mois a 04:00:00
```

Le 6eme champ (jour semaine) suit la convention Go : **0=dimanche**, 1=lundi, ..., 6=samedi.

### Planification des 3 jobs

| Job | Cron (Ofelia) | Frequence | Script execute |
|-----|---------------|-----------|----------------|
| `backup-daily` | `0 0 2 * * *` | Quotidien 02:00 | `/scripts/backup-all.sh` |
| `verify-weekly` | `0 0 3 * * 0` | Dimanche 03:00 | `/scripts/verify-integrity.sh` |
| `retention-monthly` | `0 0 4 1 * *` | 1er du mois 04:00 | `/scripts/retention.sh` |

### Role du socket Docker

Ofelia lit le socket Docker en read-only pour :
- Detecter les conteneurs et leurs labels
- Executer des commandes via `docker exec` sur les conteneurs cibles

## 3. Mise en route

Ofelia est demarre automatiquement avec la stack backup.

### 3.1 Prerequis

- Stack backup demarree (`docker-compose.backup.yml up -d`)
- Conteneur `common-backup-manager-ci` en etat `running`
- Scripts de backup (`backup-all.sh`, `verify-integrity.sh`, `retention.sh`) presents dans `/scripts/` du conteneur backup-manager
- Bucket MinIO `furious-ducks-backups` cree

### 3.2 Verifier qu'Ofelia est demarre

```bash
docker ps --filter name=common-ofelia-ci
# STATUS = Up
```

### 3.3 Verifier les jobs charges

```bash
docker logs common-ofelia-ci 2>&1 | grep -E "(New job registered|Parsed)"
# Doit lister les 3 jobs : backup-daily, verify-weekly, retention-monthly
```

Sortie attendue du type :
```
[Jobs] New job registered "backup-daily"
[Jobs] New job registered "verify-weekly"
[Jobs] New job registered "retention-monthly"
```

### 3.4 Declencher un job manuellement (test)

Pour verifier sans attendre 02:00 :

```bash
docker exec common-backup-manager-ci /scripts/backup-all.sh
```

Observer la sortie : initialisation Restic, backup, envoi vers MinIO.

### 3.5 Verifier le prochain declenchement planifie

```bash
docker logs common-ofelia-ci 2>&1 | grep -i "next"
# Ou, plus simple : attendre les logs de la prochaine execution
```

### 3.6 Consulter les inspections des labels

```bash
docker inspect common-ofelia-ci | jq '.[0].Config.Labels' | grep ofelia
```

Tu dois voir les 3 jobs avec leur schedule, container et command.

### 3.7 Ajuster un horaire (optionnel)

Editer `infrastructure/docker-compose.backup.yml`, modifier les labels `ofelia.job-exec.*.schedule`, puis :

```bash
cd /opt/furious-ducks/infrastructure
docker compose -f docker-compose.backup.yml up -d ofelia
```

Ofelia redemarre avec les nouveaux labels. Verifier avec `docker logs` que les nouveaux horaires sont charges.

### 3.8 Declencher manuellement depuis l'interface Ofelia (N/A)

Ofelia n'a pas d'interface web. Pour declencher manuellement, utiliser `docker exec` directement (voir 3.4).

## 4. Utilisation au quotidien

### 4.1 Surveiller l'execution des jobs

```bash
# Logs des dernieres 24h
docker logs --since 24h common-ofelia-ci 2>&1 | grep -E "(started|finished|failed)"
```

Exemple de sortie :
```
[Job "backup-daily"] Started - /scripts/backup-all.sh
[Job "backup-daily"] Finished in 00:02:34, succeeded=true
```

### 4.2 Verifier qu'un backup quotidien a bien tourne

```bash
# Verifier qu'une ligne "backup-daily ... succeeded=true" existe sur les dernieres 24h
docker logs --since 24h common-ofelia-ci 2>&1 | grep "backup-daily" | grep "succeeded"
```

Si rien ne sort, le backup n'a pas tourne -> investiguer (voir Depannage).

### 4.3 Declencher un backup manuel en urgence

Avant une operation risquee (mise a jour Jenkins, migration GitLab) :

```bash
docker exec common-backup-manager-ci /scripts/backup-all.sh
```

### 4.4 Ajouter un nouveau job Ofelia

Exemple : un nettoyage de vieilles images Docker tous les samedis a 06:00.

Editer `docker-compose.backup.yml` (ou un autre compose) et ajouter des labels :

```yaml
labels:
  - "ofelia.job-exec.docker-prune.schedule=0 0 6 * * 6"
  - "ofelia.job-exec.docker-prune.container=common-backup-manager-ci"
  - "ofelia.job-exec.docker-prune.command=docker image prune -af"
```

Redemarrer : `docker compose up -d ofelia`.

### 4.5 Suspendre temporairement un job

Commenter le label `schedule` dans le compose et redemarrer ofelia. Alternativement :

```yaml
- "ofelia.job-exec.backup-daily.schedule=@every 8760h"  # jamais (1 an)
```

### 4.6 Suivre les jobs d'integrite et retention

Ces jobs ne tournent que hebdomadaire / mensuel, donc ont moins de logs. Pour verifier :

```bash
# Dimanche soir
docker logs --since 24h common-ofelia-ci 2>&1 | grep verify-weekly

# Debut de mois
docker logs --since 2d common-ofelia-ci 2>&1 | grep retention-monthly
```

### 4.7 Gerer les echecs

Si Ofelia reporte un job en echec :

1. Consulter les logs detailles :
```bash
docker logs common-ofelia-ci 2>&1 | grep -A 30 "Job .backup-daily. failed"
```

2. Executer manuellement pour reproduire :
```bash
docker exec common-backup-manager-ci /scripts/backup-all.sh
```

3. Consulter les logs du backup-manager :
```bash
docker logs common-backup-manager-ci | tail -50
```

### 4.8 Coordination avec les autres outils

Ofelia ne gere que le *timing*. Les resultats (succes/echec) sont dans les logs Docker, donc :

- **Alloy** collecte les logs Ofelia -> visibles dans **Loki / Grafana**
- Une alerte Prometheus peut etre basee sur l'absence de backup recent (metrique custom exposee par le script, ou via checks Blackbox Exporter)

## 5. Tests de validation

### Sante

```bash
docker ps --filter name=common-ofelia-ci --filter status=running
```

### Jobs enregistres

```bash
docker logs common-ofelia-ci 2>&1 | grep "New job registered" | wc -l
# Doit retourner 3
```

### Declenchement manuel d'un backup

```bash
docker exec common-backup-manager-ci /scripts/backup-all.sh
# Exit code 0 attendu
```

Verifier dans MinIO :

```bash
docker exec common-backup-manager-ci restic snapshots --latest 1
```

### Attendre le prochain declenchement automatique

Le backup quotidien tourne a 02:00. Le lendemain matin :

```bash
docker logs --since 12h common-ofelia-ci 2>&1 | grep backup-daily
# Doit contenir une execution de la nuit
```

### Inspection des labels

```bash
docker inspect common-ofelia-ci | jq '.[0].Config.Labels | to_entries | map(select(.key | startswith("ofelia")))'
```

### Test complet de chaine (backup -> verify -> retention)

Optionnel mais recommande une fois par trimestre :

1. Backup manuel : `docker exec common-backup-manager-ci /scripts/backup-all.sh`
2. Verification integrite : `docker exec common-backup-manager-ci /scripts/verify-integrity.sh`
3. Retention (dry-run si possible) : `docker exec common-backup-manager-ci /scripts/retention.sh`

Tous doivent retourner exit code 0.

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| Ofelia demarre mais aucun job registered | Labels mal formates | Verifier la syntaxe `ofelia.job-exec.<name>.<key>=...` |
| `socket /var/run/docker.sock: permission denied` | Socket mal monte | Verifier `- /var/run/docker.sock:/var/run/docker.sock:ro` |
| Job ne se declenche pas a l'heure | Fuseau horaire incorrect | Verifier `TZ=Europe/Paris` sur le conteneur Ofelia ou ajuster le cron en UTC |
| `Container not found` | Conteneur cible arrete | S'assurer que `common-backup-manager-ci` tourne en permanence |
| Jobs en doublon | 2 instances d'Ofelia | `docker ps | grep ofelia` pour verifier une seule instance |
| Syntaxe cron rejetee | Format 5 champs (Linux) au lieu de 6 (Go) | Ajouter un champ secondes en tete : `0 0 2 * * *` |
| Logs Ofelia vides | Verbosite trop basse | Demarrer avec `--debug` ou `-v` dans la commande |
| Backup quotidien saute un jour | Ofelia a ete restart a l'heure precise | Normal, reprise au prochain declenchement |
| Conteneur backup-manager OOM pendant backup | RAM insuffisante | Augmenter `mem_limit` dans le compose |
