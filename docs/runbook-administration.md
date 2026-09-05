# Runbook d'administration

Aide-mémoire des commandes d'exploitation courantes sur un serveur de la plateforme (`docker`/`docker compose`). **On part du principe que la connexion SSH au VPS est déjà établie** — toutes les commandes ci-dessous s'exécutent directement sur l'hôte, prêtes à copier-coller.

Les commandes sont données avec les **vrais noms** du projet pilote **Thé Tip Top** (`projectName = 'thetiptop'` dans [`Jenkinsfile.thetiptop`](../jenkins-pipelines/Jenkinsfile.thetiptop)). Pour un autre projet client, les noms suivent le même schéma (`dbContainerFor()`/`redisContainerFor()` dans [`ciPipeline.groovy`](../jenkins/shared-library/vars/ciPipeline.groovy)) — remplacer `thetiptop` par `<projet>`.

## 0. Identifiants Jenkins de Thé Tip Top

Les mots de passe réels (DB, JWT) ne sont **jamais dans le dépôt Git** : ils sont créés une seule fois dans Jenkins et référencés par un `credentialId`. Pour les consulter/faire tourner : Jenkins → *Manage Jenkins* → *Credentials*. Seuls les identifiants Postgres sont aussi recopiés dans `infrastructure/.env` (non versionné, variables `THETIPTOP_DB_*`) pour le dump nocturne de `backup-manager` : les faire tourner en même temps.

| Credential ID Jenkins | Contenu | Utilisé pour |
|---|---|---|
| `thetiptop-db-credentials` | username/password Postgres | DEV + PREPROD (instance partagée) |
| `thetiptop-db-credentials-prod` | username/password Postgres | PROD (instance dédiée) |
| `thetiptop-jwt-secret` / `thetiptop-jwt-refresh-secret` | secret string | JWT DEV + PREPROD |
| `thetiptop-jwt-secret-prod` / `thetiptop-jwt-refresh-secret-prod` | secret string | JWT PROD |

Tu n'as pas besoin d'aller les chercher dans Jenkins pour te connecter en SQL : le container Postgres a le username en variable d'environnement (voir §2), et l'auth locale (`docker exec`) ne demande pas le mot de passe.

## 1. Conventions de nommage par environnement (Thé Tip Top)

| | DEV | PREPROD | PROD |
|---|---|---|---|
| Container app (frontend) | `thetiptop-dev` | `thetiptop-preprod` | `thetiptop-prod-blue` / `thetiptop-prod-green` |
| Container API | `thetiptop-api-dev` | `thetiptop-api-preprod` | `thetiptop-api-prod-blue` / `thetiptop-api-prod-green` |
| Container Postgres | `thetiptop-db-shared` (partagé avec PREPROD) | `thetiptop-db-shared` (partagé avec DEV) | `thetiptop-db-prod` (dédié) |
| Base de données | `thetiptop_dev` | `thetiptop_preprod` | `thetiptop_prod` |
| Container Redis | `thetiptop-redis-shared` (nom legacy — non réellement partagé, voir [01-environnements-dev-preprod-prod.md](how/01-environnements-dev-preprod-prod.md)) | `thetiptop-redis-preprod` | `thetiptop-redis-prod` |
| Réseau Docker | `workflow-network` (les trois environnements) | | |

En PROD, seul un des deux containers `-blue`/`-green` sert vraiment le trafic à un instant donné :

```bash
docker ps --filter "name=thetiptop-prod-" --format "table {{.Names}}\t{{.Status}}"
docker ps --filter "name=thetiptop-api-prod-" --format "table {{.Names}}\t{{.Status}}"
```

Le container à l'arrêt (ou absent) est l'ancienne version conservée en standby par le Blue/Green ; celui qui tourne est l'actif.

## 2. Se connecter à la base de données Postgres

Le driver Postgres officiel configure l'authentification locale (socket Unix, à l'intérieur du container) en `trust` : une fois dans le container via `docker exec`, **pas besoin de mot de passe**. Il faut juste le username, présent dans l'environnement du container :

```bash
# Récupérer le username (identique pour DEV et PREPROD, différent en PROD)
docker exec thetiptop-db-shared printenv POSTGRES_USER
docker exec thetiptop-db-prod printenv POSTGRES_USER
```

Connexion directe :

```bash
# DEV
docker exec -it thetiptop-db-shared psql -U "$(docker exec thetiptop-db-shared printenv POSTGRES_USER)" -d thetiptop_dev

# PREPROD (même instance, base différente)
docker exec -it thetiptop-db-shared psql -U "$(docker exec thetiptop-db-shared printenv POSTGRES_USER)" -d thetiptop_preprod

# PROD (instance dédiée)
docker exec -it thetiptop-db-prod psql -U "$(docker exec thetiptop-db-prod printenv POSTGRES_USER)" -d thetiptop_prod
```

## 3. Exécuter des requêtes SQL

Tables réelles (voir `api/prisma/schema.prisma` dans le dépôt applicatif Thé Tip Top) : `users`, `tickets`, `prizes`, `participations`, `leads`, `game_configs`, `audit_logs`, `refresh_tokens`, `newsletter_subscribers`.

**Exemple concret — récupérer des codes de tickets sur DEV :**

```bash
DB_USER="$(docker exec thetiptop-db-shared printenv POSTGRES_USER)"

docker exec -it thetiptop-db-shared psql -U "$DB_USER" -d thetiptop_dev \
  -c "SELECT code, \"prizeType\", \"isUsed\" FROM tickets LIMIT 20;"
```

(Les colonnes en camelCase de Prisma comme `prizeType`/`isUsed` doivent être entre guillemets doubles en SQL brut, sinon Postgres les cherche en minuscules.)

**Requête ponctuelle générique, sans ouvrir de session interactive :**

```bash
docker exec -it thetiptop-db-shared psql -U "$DB_USER" -d thetiptop_preprod -c "SELECT count(*) FROM tickets WHERE \"isUsed\" = true;"
```

**Depuis un fichier `.sql` présent sur le VPS :**

```bash
docker exec -i thetiptop-db-shared psql -U "$DB_USER" -d thetiptop_preprod < requete.sql
```

**Une fois en session `psql` interactive :**

```
\dt              -- lister les tables
\d tickets       -- décrire la table tickets
\l               -- lister les bases de l'instance
\q               -- quitter
```

⚠️ DEV et PREPROD partagent la **même instance** Postgres (`thetiptop-db-shared`) — vérifier systématiquement le `-d <base>` avant d'exécuter une requête, en particulier une requête d'écriture.

## 4. État des containers

```bash
# Tous les containers Thé Tip Top, avec leur statut
docker ps -a --filter "name=thetiptop" --format "table {{.Names}}\t{{.Status}}\t{{.Image}}"

# Consommation CPU/RAM en direct
docker stats --filter "name=thetiptop"

# Logs (derniers, puis en continu)
docker logs --tail 100 thetiptop-preprod
docker logs -f thetiptop-preprod
docker logs -f thetiptop-api-preprod

# Détail complet (image déployée, labels Traefik, variables d'env, réseau...)
docker inspect thetiptop-preprod

# Image et tag actuellement déployés
docker inspect --format='{{.Config.Image}}' thetiptop-preprod

# Tag de la version précédente (utilisé par rollback.sh pour l'auto-détection)
docker inspect --format='{{index .Config.Labels "deploy.previous-tag"}}' thetiptop-preprod
```

## 5. Démarrer / arrêter / redémarrer un container

```bash
docker stop thetiptop-preprod
docker start thetiptop-preprod
docker restart thetiptop-preprod
```

⚠️ Ne jamais `stop`/`rm` un container **Postgres ou Redis** (`thetiptop-db-shared`, `thetiptop-db-prod`, `thetiptop-redis-*`) sans savoir ce qu'on fait : ce sont les seuls containers à état (volumes de données), tout le reste est recréé sans risque par le pipeline Jenkins au build suivant.

⚠️ En PROD, ne pas `stop` directement `thetiptop-prod-blue`/`-green` à la main pour changer de version : ça coupe le service sans bascule propre. Utiliser [`scripts/rollback.sh`](../scripts/rollback.sh) (section 7) ou repasser par un déploiement Jenkins.

## 6. Rafraîchir / vider Redis

Le container Redis ne configure pas de mot de passe (`requirepass` non défini) — connexion directe.

```bash
# Ouvrir une session redis-cli (exemple PREPROD)
docker exec -it thetiptop-redis-preprod redis-cli

# Vider uniquement la base courante (0 par défaut)
docker exec -it thetiptop-redis-preprod redis-cli FLUSHDB

# Vider TOUTES les bases logiques du serveur Redis
docker exec -it thetiptop-redis-preprod redis-cli FLUSHALL

# Vérifier ce qu'il contient avant de vider (compter les clés)
docker exec -it thetiptop-redis-preprod redis-cli DBSIZE

# Simple redémarrage (relit l'AOF sur disque, ne vide rien)
docker restart thetiptop-redis-preprod
```

Containers Redis disponibles : `thetiptop-redis-shared` (DEV), `thetiptop-redis-preprod`, `thetiptop-redis-prod`.

⚠️ `FLUSHALL`/`FLUSHDB` sont irréversibles et immédiats (pas de confirmation). Sur `thetiptop-redis-prod`, mesurer l'impact avant (sessions actives, cache applicatif) — un flush en PROD peut déconnecter tous les utilisateurs.

## 7. Rollback applicatif

Script prêt à l'emploi, voir [`scripts/rollback.sh`](../scripts/rollback.sh) pour le détail (Blue/Green en PROD, remplacement simple en DEV/PREPROD) :

```bash
# Auto-détection de la version précédente
./infrastructure/scripts/rollback.sh thetiptop preprod

# Version explicite
./infrastructure/scripts/rollback.sh thetiptop prod v1.2.2

# Avec restauration de la base de données (nécessite un backup présent sous /var/backups/thetiptop/db)
./infrastructure/scripts/rollback.sh thetiptop prod v1.2.2 --with-db
```

## 8. Backup manuel

Le backup automatique tourne via Ofelia dans `common-backup-manager-ci` (voir [how/03-backup.md](how/03-backup.md)). Il dumpe en base logique SonarQube, Mattermost et les bases Thé Tip Top (`thetiptop_dev`, `thetiptop_preprod`, `thetiptop_prod`, via `PROJECT_DB_DUMPS` dans `docker-compose.backup.yml`), et copie en plus les volumes PostgreSQL et Redis du projet sous `/backup-sources/projects/`. Les identifiants de connexion sont dans `.env` (`THETIPTOP_DB_*`) : les faire tourner en même temps que les credentials Jenkins correspondants.

```bash
# Backup complet (tous les volumes de la plateforme CI/CD + dumps logiques SonarQube/Mattermost)
docker exec common-backup-manager-ci /scripts/backup-all.sh

# Backup du registre Harbor uniquement (normalement hebdomadaire)
docker exec common-backup-manager-ci /scripts/backup-harbor.sh

# Vérifier l'intégrité du dépôt de sauvegarde
docker exec common-backup-manager-ci /scripts/verify-integrity.sh

# Lister les sauvegardes disponibles
docker exec common-backup-manager-ci /scripts/restore.sh

# Restaurer une sauvegarde précise dans un dossier temporaire (ne rien écraser en direct)
docker exec -it common-backup-manager-ci /scripts/restore.sh <snapshot_id> /tmp/restore-test

# Restaurer un seul volume
docker exec -it common-backup-manager-ci /scripts/restore.sh <snapshot_id> /tmp/restore-test --volume jenkins-data
```

**Dump logique manuel d'une base Thé Tip Top** (recommandé avant toute opération risquée en DEV/PREPROD/PROD, pour disposer d'un point de restauration plus récent que le dump nocturne) :

```bash
DB_USER="$(docker exec thetiptop-db-shared printenv POSTGRES_USER)"

# DEV
docker exec thetiptop-db-shared pg_dump -U "$DB_USER" -d thetiptop_dev | gzip > thetiptop_dev_$(date +%Y%m%d).sql.gz

# PREPROD
docker exec thetiptop-db-shared pg_dump -U "$DB_USER" -d thetiptop_preprod | gzip > thetiptop_preprod_$(date +%Y%m%d).sql.gz

# PROD
DB_USER_PROD="$(docker exec thetiptop-db-prod printenv POSTGRES_USER)"
docker exec thetiptop-db-prod pg_dump -U "$DB_USER_PROD" -d thetiptop_prod | gzip > thetiptop_prod_$(date +%Y%m%d).sql.gz
```

Restauration inverse (⚠️ écrase la base cible) :

```bash
gunzip -c thetiptop_preprod_20260101.sql.gz | docker exec -i thetiptop-db-shared psql -U "$DB_USER" -d thetiptop_preprod
```

⚠️ `restore.sh` écrase le dossier cible sans confirmation en mode non-interactif (`docker exec` sans `-it`) — toujours passer `-it` pour garder le prompt de confirmation, ou vérifier `TARGET_PATH` avant.

## 9. Réseaux Docker utiles

```bash
# Lister les containers connectés à workflow-network
docker network inspect workflow-network --format '{{range .Containers}}{{.Name}}{{"\n"}}{{end}}'

# Tester la résolution DNS interne entre deux containers du même réseau
docker exec thetiptop-preprod getent hosts thetiptop-db-shared
```

## 10. Maîtrise de l'espace disque

### 10.1 Ce qui tourne automatiquement

Deux jobs Ofelia quotidiens tiennent le disque, en plus de la rétention Restic (cf. [how/03-backup.md](how/03-backup.md)) :

| Quand | Job | Ce qu'il fait |
|---|---|---|
| 05:30 | `prune-workspaces-daily` | supprime les workspaces des branches Jenkins inactives depuis 7 jours (l'historique des builds, les rapports et les artefacts archivés ne sont **jamais** touchés) |
| 05:45 | `prune-docker-images-daily` | supprime les images applicatives du cache local devenues inutiles, puis ramène le cache BuildKit sous 25 Go |

Côté Harbor, deux planifications propres au registre (cf. [tools/04-harbor.md](tools/04-harbor.md) 3.9 et 3.10) : politique de rétention le **samedi 05:00** (10 derniers tags `develop-*`/`main-*`, `v*` et `latest` conservés sans limite), garbage collection le **samedi 07:00**. L'ordre est voulu : rétention → GC → sauvegarde du registre le dimanche 01:00.

`scripts/prune-docker-images.sh` protège explicitement, dans cet ordre : toute image référencée par un container (y compris les `*-previous` arrêtés, cibles du rollback Blue/Green), les tags sémantiques `vX.Y.Z`, le tag `latest`, et tout ce qui a moins de 24 h. Le lancer à blanc avant d'ajuster ses seuils :

```bash
docker exec common-jenkins-ci bash /opt/furious-ducks/scripts/prune-docker-images.sh --dry-run
```

### 10.2 Réglages hôte (hors dépôt, à rejouer si le serveur est réinstallé)

`/etc/docker/daemon.json` — rotation des logs de tous les containers (y compris ceux créés dynamiquement par Jenkins, que les compose files ne couvrent pas) et plafond du cache de build :

```json
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "50m", "max-file": "3", "compress": "true" },
  "builder": { "gc": { "enabled": true, "reservedSpace": "10GB", "maxUsedSpace": "25GB" } }
}
```

⚠️ **Ce fichier n'est pas actif en l'état.** Il a été écrit le 2026-09-03 alors que le démon tourne sans interruption depuis le 2026-07-08 : ni le plafond des logs ni le GC BuildKit n'ont jamais été lus. Preuve : `docker inspect <container> --format '{{json .HostConfig.LogConfig.Config}}'` renvoie `{}` sur tous les containers, et le cache de build avait atteint 57 Go pour un plafond déclaré à 25 Go. Les deux réglages ne prennent effet qu'après :

```bash
sudo systemctl restart docker   # redémarre TOUS les containers -> fenêtre de maintenance
```

Tant que ce redémarrage n'a pas eu lieu, le job `prune-docker-images-daily` applique le même plafond de cache par une commande explicite, et les logs doivent être tronqués à la main (10.3). Après le redémarrage, la rotation ne s'appliquera qu'aux containers **recréés ensuite** : les containers de longue durée (GitLab, Jenkins) garderont des logs illimités jusqu'à leur prochaine recréation.

`/etc/systemd/journald.conf.d/99-size-limit.conf` — plafond du journal systemd (sans quoi journald monte jusqu'à 10% du disque, soit ~19 Go ici) :

```ini
[Journal]
SystemMaxUse=500M
SystemMaxFileSize=50M
```

Celui-ci **est** actif : `journalctl --disk-usage` renvoie ~8 Mo.

### 10.3 Nettoyage manuel

```bash
# Où est passé l'espace ?
docker system df
df -h /

# Logs de containers dépassant 20 Mo (déjà expédiés vers Loki, rétention 90j)
sudo bash -c 'for f in /var/lib/docker/containers/*/*-json.log; do
  [ "$(stat -c %s "$f")" -gt 20971520 ] && { echo "$f"; truncate -s 0 "$f"; }
done'

# Images applicatives + cache de build (mêmes protections que le job quotidien)
docker exec common-jenkins-ci bash /opt/furious-ducks/scripts/prune-docker-images.sh

# Purge plus agressive du cache de build (le prochain build recompile tout : +3 à 5 min)
docker builder prune -af
```

⚠️ Ne **jamais** lancer `docker image prune -a` à l'aveugle : cela supprimerait les tags `vX.Y.Z` dont dépendent `scripts/rollback.sh` et le stage `Promote Image`. Utiliser le script, qui les protège.

## 11. Références

- Mapping environnement / nommage : [how/01-environnements-dev-preprod-prod.md](how/01-environnements-dev-preprod-prod.md)
- Fonctionnement du backup : [how/03-backup.md](how/03-backup.md)
- Rollback manuel : [`scripts/rollback.sh`](../scripts/rollback.sh)
- Provisioning Postgres/Redis : `ensureSharedDatabase()` dans [`jenkins/shared-library/vars/ciPipeline.groovy`](../jenkins/shared-library/vars/ciPipeline.groovy)
- Config Jenkins Thé Tip Top (credentials, domaines, ports) : [`jenkins-pipelines/Jenkinsfile.thetiptop`](../jenkins-pipelines/Jenkinsfile.thetiptop)
- Schéma de la base Thé Tip Top : `api/prisma/schema.prisma` (dépôt applicatif)
