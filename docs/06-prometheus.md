# Prometheus - Monitoring et Metriques

## 1. Presentation

- **Role** : Collecte de metriques time-series, alerting, base du systeme de monitoring
- Scrape les metriques de tous les services de l'infrastructure CI/CD
- **Port** : `9090` (interne, expose via Traefik - aucun port hote)
- **Sous-domaine** : `prometheus.wk-archi-o24a-4-5-g1.fr`, protege par
  **basic auth** (`traefik-auth`, memes identifiants que le dashboard Traefik)
  et par le middleware `rate-limit`
- **Langage de requete** : PromQL (Prometheus Query Language)
- **Retention** : 30 jours de donnees (`--storage.tsdb.retention.time=30d`)
- **Services scrapes** : Prometheus (self), Node Exporter, cAdvisor, Traefik,
  Jenkins, GitLab, MinIO, Loki, Grafana, les deux postgres-exporter
  (SonarQube + Mattermost) et Harbor

## 2. Configuration

### Image et conteneur

- **Image** : `prom/prometheus:v3.5.4`
- **Container** : `common-prometheus-ci`

### Fichier de configuration

- Chemin : `infrastructure/prometheus/prometheus.yml`
- Definition des targets de scraping, intervalles, et labels

### Targets de scraping

Les cibles sont adressees par **nom de conteneur** (DNS Docker sur
`ci_network`), pas par nom de service Compose. `scrape_interval` est global
(15s) : aucun job ne le surcharge.

| Job | Endpoint | Remarque |
|-----|----------|----------|
| `prometheus` | `localhost:9090/metrics` | self-monitoring |
| `node-exporter` | `common-node-exporter-ci:9100/metrics` | metriques hote + textfiles (backup, DORA) |
| `cadvisor` | `common-cadvisor-ci:8080/metrics` | metriques conteneurs |
| `traefik` | `common-traefik-ci:8082/metrics` | entrypoint interne `metrics`, pas le dashboard |
| `jenkins` | `common-jenkins-ci:8080/prometheus` | plugin `prometheus` |
| `gitlab` | `common-gitlab-ci:80/-/metrics` | necessite `monitoring_whitelist` (deja dans le compose) |
| `minio` | `common-minio-ci:9000/minio/v2/metrics/cluster` | `bearer_token_file: /etc/prometheus/secrets/minio_token` |
| `loki` | `common-loki-ci:3100/metrics` | |
| `grafana` | `common-grafana-ci:3000/metrics` | |
| `postgres-exporter` | `common-sonarqube-db-exporter-ci:9187` et `common-mattermost-db-exporter-ci:9187` | label `database` = `sonarqube` / `mattermost` |
| `harbor` | `nginx:9090/metrics` | Harbor deploye separement ; DOWN tant que son `proxy` n'est pas sur `ci_network` (voir `04-harbor.md`) |

> **SonarQube n'a pas de job de scrape.** `/api/monitoring/metrics` (format
> Prometheus) est reserve a l'edition Developer ; en Community Build,
> `/api/system/status` ne renvoie que du JSON, inexploitable par Prometheus.
> La disponibilite de SonarQube est couverte au niveau conteneur via cAdvisor.

> **Le token MinIO** se genere avec `mc admin prometheus generate local` et
> s'ecrit dans `infrastructure/prometheus/secrets/minio_token` (jamais
> committe, voir le `.example`).

### Regles d'alerte

- Chemin : `infrastructure/prometheus/rules/alerts.yml`
- Categories d'alertes :
  - **Infrastructure** (`infrastructure`) :
    - `HostHighCpuUsage`, `HostHighMemoryUsage`
    - `HostDiskSpaceLow`, `HostDiskSpaceCritical`, `HostOutOfDiskSpace`
  - **Conteneurs** (`container`) :
    - `ContainerDown` (silence cAdvisor > 90s sur un conteneur `common-*`)
    - `ContainerHighCpuUsage`, `ContainerHighMemoryUsage`
    - `ContainerRestarting` (redemarrages en boucle)
  - **Services CI/CD** (`cicd-services`) :
    - `JenkinsDown`
    - `GitLabDown`
    - `TraefikHighErrorRate`
  - **Backups** (`backup`) :
    - `BackupFailed` : echec de backup detecte
    - `BackupTooOld` : backup non execute depuis trop longtemps

### Integration AlertManager

- AlertManager configure pour recevoir les alertes de Prometheus
- Canaux de notification : email, Mattermost (webhook entrant)
- Regles de routage et de silencing configurees

## 3. Mise en route

Prometheus fait partie du stack monitoring (`docker-compose.monitoring.yml`). Il se configure principalement par fichier ; il n'y a pas d'interface de configuration interactive.

### 3.1 Prerequis

- Le stack principal CI/CD tourne (les services a scraper doivent etre demarres).
- Le reseau Docker `ci_network` existe (cree par le stack principal).
- Les fichiers `prometheus.yml` et `rules/alerts.yml` existent dans `infrastructure/prometheus/`.

### 3.2 Demarrer le stack monitoring

```bash
cd /opt/furious-ducks/infrastructure
docker compose -f docker-compose.monitoring.yml up -d
```

Cela demarre Prometheus + Grafana + Loki + Alloy + cAdvisor + AlertManager + Node Exporter en une fois.

### 3.3 Verifier que Prometheus tourne

```bash
docker ps --filter name=common-prometheus-ci
docker logs common-prometheus-ci --tail 30
# Tu dois voir : "Server is ready to receive web requests"
```

### 3.4 Acceder a l'interface

Ouvre `https://prometheus.wk-archi-o24a-4-5-g1.fr`. Le navigateur demande un
**login basic auth** : ce sont les identifiants de `TRAEFIK_DASHBOARD_AUTH`
(memes que le dashboard Traefik). Prometheus n'a pas d'authentification
native ; c'est le middleware Traefik `traefik-auth` qui protege la route
publique, double d'un `rate-limit`.

Les commandes `curl` de ce document doivent donc porter `-u admin:<mdp>`.

### 3.5 Verifier les targets

L'etape la plus importante : verifier que **tous les services sont scrapes correctement**.

1. Ouvre `https://prometheus.wk-archi-o24a-4-5-g1.fr/targets`
2. Tu vois la liste des jobs : `prometheus`, `node-exporter`, `cadvisor`,
   `traefik`, `jenkins`, `gitlab`, `minio`, `loki`, `grafana`,
   `postgres-exporter`, `harbor`
3. **Chaque target doit etre en etat UP (vert)**, a l'exception de `harbor`
   tant que Harbor n'est pas installe et rattache a `ci_network`.

Si un target est DOWN :
- Verifie que le service tourne : `docker ps`
- Verifie que le service expose bien son endpoint metrics
- Cas particuliers :
  - **GitLab** : il faut activer les metriques dans Admin > Settings > Metrics and profiling > Prometheus
  - **Jenkins** : le plugin Prometheus doit etre installe (deja dans `plugins.txt`)
  - **Harbor** : cible `nginx:9090`. Reste DOWN tant que Harbor n'est pas
    deploye avec `metric.enabled: true` et son service `proxy` rattache a
    `ci_network` (voir `04-harbor.md`)
  - **MinIO** : verifier que `prometheus/secrets/minio_token` est bien
    renseigne, sinon le scrape renvoie 401

### 3.6 Tester une requete PromQL

Dans `https://prometheus.wk-archi-o24a-4-5-g1.fr/graph` :

```promql
up
```

Resultat : tableau avec une ligne par target, valeur 1 si UP.

```promql
rate(traefik_service_requests_total[5m])
```

Resultat : taux de requetes par service.

### 3.7 Verifier les regles d'alerte

`https://prometheus.wk-archi-o24a-4-5-g1.fr/alerts`

Tu dois voir toutes les alertes definies, classees par groupe (`infrastructure`, `container`, `cicd-services`, `backup`). En conditions normales, **aucune ne doit etre en FIRING**.

### 3.8 Tester une alerte

Pour valider que l'alerting fonctionne, on declenche temporairement un seuil. Exemple : forcer une alerte CPU.

```bash
# Sur le serveur, generer du CPU pendant 6 min
docker run --rm -d --name stress alpine sh -c 'apk add stress && stress --cpu 4 --timeout 360'
```

Apres 5 minutes, l'alerte `HostHighCpuUsage` doit etre **FIRING** dans Prometheus, et AlertManager doit envoyer une notification (mail / Mattermost).

```bash
docker stop stress
```

## 4. Utilisation au quotidien

### 4.1 Requetes PromQL utiles

```promql
# Disponibilite d'un service sur 24h
avg_over_time(up{job="jenkins"}[24h])

# Nombre de builds Jenkins en echec dans la derniere heure
sum(increase(jenkins_builds_failed_build_count_total[1h]))

# Latence p95 d'un service via Traefik
histogram_quantile(0.95,
  sum by (le, service) (
    rate(traefik_service_request_duration_seconds_bucket[5m])
  )
)

# Top 5 conteneurs consommant le plus de RAM
topk(5, container_memory_usage_bytes{name=~".+"})

# Espace disque libre par mountpoint
node_filesystem_avail_bytes{fstype!~"tmpfs|overlay"}
```

### 4.2 Ajouter un nouveau target

Edite `infrastructure/prometheus/prometheus.yml` :

```yaml
scrape_configs:
  - job_name: mon-service
    static_configs:
      - targets: ['mon-service:9100']
```

Puis recharge sans redemarrer. Le port 9090 n'etant **pas publie sur l'hote**,
il faut passer par le conteneur (ou par l'URL publique avec le basic auth) :

```bash
docker exec common-prometheus-ci wget --post-data='' -qO- http://localhost:9090/-/reload
```

### 4.3 Ajouter une regle d'alerte

Edite `infrastructure/prometheus/rules/alerts.yml`, ajoute :

```yaml
- alert: MaCustomAlert
  expr: my_metric > 100
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Custom alert"
    description: "Mon metric depasse 100"
```

Puis recharge :

```bash
docker exec common-prometheus-ci wget --post-data='' -qO- http://localhost:9090/-/reload
```

### 4.4 Silencier temporairement une alerte

AlertManager n'est **pas expose via Traefik** (`traefik.enable=false`) : il n'a
ni sous-domaine ni port hote, et l'UI Prometheus ne sait pas creer de silence.
Le silencing se fait avec `amtool` depuis le conteneur :

```bash
docker exec common-alertmanager-ci amtool \
  --alertmanager.url=http://localhost:9093 \
  silence add alertname=JenkinsDown --duration=30m \
  --comment="Maintenance planifiee"
```

Voir `15-alertmanager.md` section 4.2.

### 4.5 Voir l'historique d'une metrique

Onglet **Graph** > entrer la requete > selectionner la duree (1h, 24h, 7d).

### 4.6 Sauvegarder Prometheus

Le volume `common-prometheus-data-ci` est sauvegarde par Restic (voir `09-restic-minio.md`). Pas d'action manuelle.

## 5. Tests de validation

### Acces web

```bash
curl -f -u admin:<mdp> https://prometheus.wk-archi-o24a-4-5-g1.fr/-/healthy
```

Resultat attendu : code HTTP 200, reponse `Prometheus Server is Healthy.`

### Verifier les targets

1. Naviguer vers `https://prometheus.wk-archi-o24a-4-5-g1.fr/targets`
2. Verifier que tous les targets sont en etat **UP** (vert)
3. Verifier qu'aucun target n'est en erreur ou inaccessible

### Requete PromQL de test

```bash
curl -g -u admin:<mdp> 'https://prometheus.wk-archi-o24a-4-5-g1.fr/api/v1/query?query=up{job="jenkins"}'
```

Resultat attendu : reponse JSON avec la valeur `1` pour le job Jenkins (service UP).

### Verifier les alertes

1. Naviguer vers `https://prometheus.wk-archi-o24a-4-5-g1.fr/alerts`
2. Verifier que les regles d'alerte sont chargees (CPU, RAM, disque, services, backups)
3. Verifier qu'aucune alerte critique n'est en etat **FIRING** (en conditions normales)

### Verifier la retention

```promql
prometheus_tsdb_head_min_time
prometheus_tsdb_head_max_time
```

Resultat attendu : la difference entre `head_max_time` et `head_min_time` couvre environ 30 jours de donnees.

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| Tous les targets DOWN | Reseau Docker incorrect | Verifier que prometheus est sur `ci_network` |
| GitLab target DOWN | Metriques pas activees | GitLab > Admin > Settings > Metrics and profiling |
| Jenkins target DOWN | Plugin Prometheus manquant | Verifier `plugins.txt` |
| Alertes ne partent pas | AlertManager down | `docker logs common-alertmanager-ci` |
| Disque sature par les TSDB | Retention trop longue | Reduire `--storage.tsdb.retention.time` |
| `out of memory` | Trop de targets / serie | Augmenter limites memoire conteneur |
