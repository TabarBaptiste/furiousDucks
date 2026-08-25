# Prometheus - Monitoring et Metriques

## 1. Presentation

- **Role** : Collecte de metriques time-series, alerting, base du systeme de monitoring
- Scrape les metriques de tous les services de l'infrastructure CI/CD
- **Port** : `9090` (via Traefik)
- **Sous-domaine** : `prometheus.wk-archi-o24a-4-5-g1.fr`
- **Langage de requete** : PromQL (Prometheus Query Language)
- **Retention** : 30 jours de donnees
- **Services scrapes** : Jenkins, GitLab, Traefik, cAdvisor, Node Exporter, SonarQube, MinIO, Loki, Grafana

## 2. Configuration

### Image et conteneur

- **Image** : `prom/prometheus:v2.54.0`
- **Container** : `common-prometheus-ci`

### Fichier de configuration

- Chemin : `infrastructure/prometheus/prometheus.yml`
- Definition des targets de scraping, intervalles, et labels

### Targets de scraping

| Target          | Endpoint                          | Intervalle |
|-----------------|-----------------------------------|------------|
| Node Exporter   | `node-exporter:9100/metrics`      | 15s        |
| cAdvisor        | `cadvisor:8080/metrics`           | 15s        |
| Traefik         | `traefik:8080/metrics`            | 15s        |
| Jenkins         | `jenkins:8080/prometheus`         | 30s        |
| GitLab          | `gitlab:9168/metrics`             | 30s        |
| SonarQube       | `sonarqube:9000/api/monitoring`   | 30s        |
| MinIO           | `minio:9000/minio/v2/metrics`     | 30s        |
| Loki            | `loki:3100/metrics`               | 15s        |
| Grafana         | `grafana:3000/metrics`            | 30s        |

### Regles d'alerte

- Chemin : `infrastructure/prometheus/rules/alerts.yml`
- Categories d'alertes :
  - **Infrastructure** :
    - CPU utilisation > 80% pendant 5 minutes
    - RAM utilisation > 85% pendant 5 minutes
    - Disque utilisation > 90%
  - **Conteneurs** :
    - Conteneur down (restart frequent ou arret inattendu)
    - Conteneur utilisant plus de memoire que sa limite
  - **Services CI/CD** :
    - Jenkins indisponible
    - GitLab indisponible
    - Harbor indisponible
    - SonarQube indisponible
  - **Backups** :
    - Echec de backup detecte
    - Backup non execute depuis plus de 24h

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

Ouvre `https://prometheus.wk-archi-o24a-4-5-g1.fr`. **Pas de login** (Prometheus est en interne, l'acces est protege par Traefik et un middleware basic auth si tu en ajoutes un).

### 3.5 Verifier les targets

L'etape la plus importante : verifier que **tous les services sont scrapes correctement**.

1. Ouvre `https://prometheus.wk-archi-o24a-4-5-g1.fr/targets`
2. Tu vois la liste des jobs : `prometheus`, `node-exporter`, `cadvisor`, `traefik`, `jenkins`, `gitlab`, `sonarqube`, `minio`, `loki`, `grafana`
3. **Chaque target doit etre en etat UP (vert)**.

Si un target est DOWN :
- Verifie que le service tourne : `docker ps`
- Verifie que le service expose bien son endpoint metrics
- Cas particuliers :
  - **GitLab** : il faut activer les metriques dans Admin > Settings > Metrics and profiling > Prometheus
  - **Jenkins** : le plugin Prometheus doit etre installe (deja dans `plugins.txt`)
  - **SonarQube** : l'endpoint `/api/monitoring/metrics` necessite Developer Edition (en Community, on peut commenter ce job)

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

Puis recharge sans redemarrer :

```bash
curl -X POST http://localhost:9090/-/reload
# (depuis le serveur)
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
curl -X POST http://localhost:9090/-/reload
```

### 4.4 Silencier temporairement une alerte

Va dans AlertManager : `https://alertmanager.wk-archi-o24a-4-5-g1.fr` (si expose) ou via l'API.

Plus simple via l'UI Prometheus :
1. **Alerts > <alert> > silence**
2. Definir la duree et la raison

### 4.5 Voir l'historique d'une metrique

Onglet **Graph** > entrer la requete > selectionner la duree (1h, 24h, 7d).

### 4.6 Sauvegarder Prometheus

Le volume `common-prometheus-data-ci` est sauvegarde par Restic (voir `09-restic-minio.md`). Pas d'action manuelle.

## 5. Tests de validation

### Acces web

```bash
curl -f https://prometheus.wk-archi-o24a-4-5-g1.fr/-/healthy
```

Resultat attendu : code HTTP 200, reponse `Prometheus Server is Healthy.`

### Verifier les targets

1. Naviguer vers `https://prometheus.wk-archi-o24a-4-5-g1.fr/targets`
2. Verifier que tous les targets sont en etat **UP** (vert)
3. Verifier qu'aucun target n'est en erreur ou inaccessible

### Requete PromQL de test

```bash
curl -g 'https://prometheus.wk-archi-o24a-4-5-g1.fr/api/v1/query?query=up{job="jenkins"}'
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
