# cAdvisor - Metriques des Conteneurs Docker

## 1. Presentation

- **Role** : Collecte les metriques de ressources des conteneurs Docker (CPU, RAM, reseau, I/O disque)
- **Provient de Google** : Container Advisor, open source
- **Port** : `8081` sur l'hote (8080 interne au conteneur), scrape par Prometheus
- **Pas de sous-domaine** (service interne, accessible via l'IP hote si besoin debug)
- **Donnees en temps reel** sur chaque conteneur
- **Essentiel** pour le monitoring de l'infrastructure conteneurisee
- **Auto-decouverte** : tous les conteneurs du daemon Docker sont traces automatiquement

### Architecture

```
+---------------+       +----------+       +----------+
| Conteneurs    |       |          |       |          |
| Docker        | <---- | cAdvisor | <-----| Prometheus|
| (tous)        | (lit  |          | scrape|          |
+---------------+  /proc,|  :8080  |  /metrics +------+
                   /sys, |          |       |          |
                   docker|          |       | Grafana  |
                   .sock)+----------+       | dashboard|
                                            +----------+
```

cAdvisor lit les cgroups Linux et le socket Docker pour exposer les metriques de tous les conteneurs au format Prometheus. Prometheus scrape cAdvisor toutes les 15 secondes. Grafana affiche les donnees via le dashboard **"Docker Monitoring"**.

### Metriques cles exposees

| Metrique | Description |
|----------|-------------|
| `container_cpu_usage_seconds_total` | Temps CPU cumule par conteneur |
| `container_memory_usage_bytes` | RAM utilisee par conteneur |
| `container_memory_working_set_bytes` | RAM active (hors cache) |
| `container_network_receive_bytes_total` | Octets recus (reseau) |
| `container_network_transmit_bytes_total` | Octets emis (reseau) |
| `container_fs_reads_bytes_total` | Lectures disque |
| `container_fs_writes_bytes_total` | Ecritures disque |
| `container_last_seen` | Timestamp de la derniere observation |

## 2. Configuration

### Image et conteneur

| Parametre | Valeur |
|-----------|--------|
| Image | `gcr.io/cadvisor/cadvisor:v0.49.1` |
| Container | `common-cadvisor-ci` |
| Port hote | `8081` -> 8080 conteneur |
| Reseau | `ci_network` + `monitoring_network` |

### Volumes (lecture seule obligatoire)

| Volume hote | Montage | Role |
|-------------|---------|------|
| `/` | `/rootfs:ro` | Acces au filesystem hote |
| `/var/run` | `/var/run` | Socket Docker |
| `/sys` | `/sys:ro` | cgroups v1/v2 |
| `/var/lib/docker` | `/var/lib/docker:ro` | Metadata Docker |

### Scrape Prometheus

Cible configuree dans `infrastructure/prometheus/prometheus.yml` :

```yaml
- job_name: cadvisor
  static_configs:
    - targets:
        - common-cadvisor-ci:8080
```

Scrape interval : 15 secondes.

### Dashboard Grafana

Dashboard **"Docker Monitoring"** provisionne automatiquement dans `infrastructure/grafana/provisioning/dashboards/json/docker-monitoring.json`. Accessible dans Grafana > Dashboards > Furious Ducks.

## 3. Mise en route

cAdvisor est demarre automatiquement avec la stack monitoring (`docker-compose.monitoring.yml`). Aucune configuration manuelle.

### 3.1 Prerequis

- Docker installe (bien sur).
- Stack monitoring demarree (`docker-compose.monitoring.yml up -d`).
- Prometheus demarre et configure pour scraper cAdvisor.

### 3.2 Verifier que cAdvisor est demarre

```bash
docker ps --filter name=common-cadvisor-ci
# STATUS doit etre Up (healthy) ou Up
```

### 3.3 Acceder a l'interface web de cAdvisor (optionnel)

cAdvisor expose une UI basique sur le port 8081 :

```bash
# Depuis le serveur
curl -s http://localhost:8081/ | head -30

# Ou dans un navigateur (si port 8081 accessible) :
# http://<IP-serveur>:8081/
```

Pour le dashboarding de production, utilise Grafana plutot que l'UI cAdvisor native.

### 3.4 Verifier que les metriques sont exposees

```bash
curl -s http://localhost:8081/metrics | grep "^container_" | head -20
```

Doit retourner des lignes comme `container_cpu_usage_seconds_total{...}`.

### 3.5 Verifier le scrape dans Prometheus

1. Aller sur `https://prometheus.wk-archi-o24a-4-5-g1.fr/targets`
2. Chercher la section `cadvisor`
3. Le target doit etre **UP** (vert)
4. Dernier scrape : il y a quelques secondes

### 3.6 Faire une premiere requete PromQL

Dans Prometheus > Graph :

```promql
# CPU de tous les conteneurs Furious Ducks
rate(container_cpu_usage_seconds_total{name=~"common-.*"}[5m])

# RAM utilisee par Jenkins
container_memory_usage_bytes{name="common-jenkins-ci"} / 1024 / 1024
```

### 3.7 Importer le dashboard Docker Monitoring dans Grafana

Le dashboard est deja provisionne. Pour le verifier :

1. Grafana > Dashboards
2. Dossier **"Furious Ducks"**
3. Ouvrir **"Docker Monitoring"**
4. Verifier l'affichage des metriques CPU, RAM, reseau par conteneur

## 4. Utilisation au quotidien

### 4.1 Voir l'etat d'un conteneur specifique dans Grafana

1. Grafana > Docker Monitoring dashboard
2. Variable **"container"** en haut -> selectionner le conteneur
3. Tous les graphiques filtrent automatiquement

### 4.2 Verifier la consommation en temps reel via CLI

```bash
# Comparable a "docker stats" mais via cAdvisor
curl -s http://localhost:8081/api/v1.3/docker/<container-id>
```

### 4.3 Requetes PromQL utiles

```promql
# Top 5 des conteneurs les plus consommateurs en CPU (5 dernieres min)
topk(5, rate(container_cpu_usage_seconds_total{name=~".+"}[5m])) * 100

# Top 5 en RAM
topk(5, container_memory_usage_bytes{name=~".+"}) / 1024 / 1024

# Bande passante reseau (reception) par conteneur
rate(container_network_receive_bytes_total{name=~".+"}[5m])

# I/O disque (ecriture)
rate(container_fs_writes_bytes_total{name=~".+"}[5m])

# Conteneurs qui ont redemarre recemment
time() - container_start_time_seconds{name=~".+"} < 3600
```

### 4.4 Alerter sur conteneur consommant trop de RAM

Editer `infrastructure/prometheus/rules/alerts.yml` (cf. `15-alertmanager.md`) :

```yaml
- alert: ContainerHighMemoryUsage
  expr: (container_memory_usage_bytes{name=~".+"} / container_spec_memory_limit_bytes{name=~".+"}) * 100 > 80
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Conteneur {{ $labels.name }} utilise > 80% de sa limite RAM"
```

### 4.5 Correler metriques et logs

Dans Grafana > Explore :

1. Metriques Prometheus : selectionner un conteneur avec pic CPU
2. Ouvrir **"Split"** pour afficher Loki a cote
3. Filtrer les logs du meme conteneur au meme instant :

```logql
{container_name="common-jenkins-ci"} |= "error"
```

### 4.6 Lister tous les conteneurs traces

```bash
curl -s http://localhost:8081/metrics | grep '^container_last_seen{' | \
  sed -E 's/.*name="([^"]+)".*/\1/' | sort -u
```

### 4.7 Surveiller l'impact d'un nouveau service

Quand tu deploies un nouveau conteneur :

1. Note son nom Docker
2. Grafana > Docker Monitoring > filtre container = nouveau nom
3. Observe CPU/RAM sur les 30 prochaines minutes
4. Ajuste les limites Docker si besoin (`docker update --memory=...`)

## 5. Tests de validation

### Verifier que cAdvisor tourne

```bash
docker ps --filter name=common-cadvisor-ci
# STATUS doit etre Up
```

### Acces aux metriques

```bash
curl -sf http://localhost:8081/metrics | head -5
# Doit retourner des lignes de metriques Prometheus
```

### Target Prometheus UP

```bash
curl -s "https://prometheus.wk-archi-o24a-4-5-g1.fr/api/v1/targets" | \
  jq '.data.activeTargets[] | select(.labels.job == "cadvisor") | .health'
# Doit afficher "up"
```

### Requete PromQL de test

```bash
curl -s "https://prometheus.wk-archi-o24a-4-5-g1.fr/api/v1/query?query=up{job=\"cadvisor\"}" | jq
# Valeur "1" = cAdvisor OK
```

### Comparer avec `docker stats`

```bash
# Ouvrir 2 fenetres :
# Fenetre 1
docker stats --no-stream

# Fenetre 2 : verifier via PromQL qu'on retrouve les memes valeurs
curl -s "https://prometheus.wk-archi-o24a-4-5-g1.fr/api/v1/query?query=container_memory_usage_bytes{name=\"common-jenkins-ci\"}" | jq
```

### Dashboard Grafana

1. Grafana > Docker Monitoring
2. Tous les panneaux affichent des donnees recentes (< 1 min)
3. Dropdown "container" liste bien tous les conteneurs

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| cAdvisor ne demarre pas | Volume manquant ou permissions | Verifier montages `/sys`, `/var/lib/docker` |
| `Failed to get rootfs` | `/rootfs:ro` non monte | Ajouter `-v /:/rootfs:ro` |
| Aucune metrique dans Prometheus | Target DOWN | `docker logs common-cadvisor-ci` pour voir l'erreur |
| UI web cAdvisor KO (port 8081) | Port pas expose a l'hote | Verifier `ports: - "8081:8080"` dans compose |
| Metriques absentes pour certains conteneurs | Conteneur trop ephemere | Normal, cAdvisor scrape toutes les 15s |
| Consommation CPU elevee de cAdvisor | Trop de conteneurs (~100+) | Ajouter `--housekeeping_interval=30s` |
| `permission denied /var/lib/docker` | SELinux/AppArmor | Ajouter `--security-opt label:disable` |
| Dashboard Grafana vide | Datasource Prometheus non configuree | Grafana > Connections > Data sources > test |
