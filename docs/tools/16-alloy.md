# Alloy - Agent de Collecte de Logs

## 1. Presentation

- **Role** : Agent de collecte de logs, envoie les logs vers Loki (remplace Promtail, EOL le 2026-03-02)
- **Collecte** : Logs de tous les conteneurs Docker (via le socket Docker) + logs systeme (`/var/log/syslog`)
- **Port** : `12345` (HTTP API + UI de debug interne, pas d'exposition Traefik)
- **Pas de sous-domaine** (service interne)
- **Deploye sur chaque serveur** qui produit des logs (VPS socle + VPS projets)
- **Decouverte automatique** des conteneurs via l'API Docker (pas de lecture directe des fichiers `*-json.log`)
- **Enrichissement** avec des labels (`container_name`, `job`)
- **Positions persistees** dans un volume Docker (`common-alloy-data-ci`) : Alloy reprend la collecte ou il s'etait arrete apres un restart

### Architecture

```
Hote Linux (VPS)
+----------------------------------------------------+
|                                                    |
| /var/run/docker.sock (API Docker)                 |
| /var/log/syslog                                    |
|         |                                          |
|         v                                          |
|  +---------------+   push API v1    +-----------+ |
|  | Alloy         | ---------------> | Loki      | |
|  | :12345        |                  | :3100     | |
|  +---------------+                  +-----------+ |
|         |                                          |
|         | ecrit /var/lib/alloy/data (volume)        |
|         | (reprise apres restart)                  |
+----------------------------------------------------+
```

Alloy decouvre les conteneurs via le socket Docker (`discovery.docker`), recupere le nom du conteneur via les metadonnees Docker (`__meta_docker_container_name`), tail les logs applicatifs et systeme, puis les envoie a Loki via son API push.

### Difference avec Loki

| Composant | Role |
|-----------|------|
| **Alloy** | Agent *collecteur* qui lit les logs sur les hotes |
| **Loki** | Serveur qui *stocke* et *indexe* les logs |

Sur chaque VPS supplementaire (DEV/PREPROD/PROD), il faut deployer un Alloy qui envoie vers le Loki central.

### Pourquoi Alloy et pas Promtail

Promtail est en fin de vie depuis le 2026-03-02 (support commercial termine, plus de mises a jour). Son code a ete retire du depot Loki a partir de la version 3.7.3, fusionne dans Grafana Alloy — successeur officiel, agent unifie logs/metriques/traces base sur l'OpenTelemetry Collector.

Au passage, la decouverte via le socket Docker corrige un defaut de l'ancienne config Promtail : celle-ci tentait d'extraire `container_name` via une regex sur un champ `name` que l'etape de pipeline `docker: {}` ne produisait jamais — le label restait donc vide en pratique. La decouverte Alloy via `__meta_docker_container_name` le peuple correctement.

## 2. Configuration

### Image et conteneur

| Parametre | Valeur |
|-----------|--------|
| Image | `grafana/alloy:v1.17.1` |
| Container | `common-alloy-ci` |
| Port interne | `12345` |
| Reseau | `ci_network` + `monitoring_network` |

### Volumes

| Hote | Montage | Role |
|------|---------|------|
| `/var/log` | `/var/log:ro` | Logs systeme |
| `/var/run/docker.sock` | `/var/run/docker.sock:ro` | Decouverte + lecture des logs des conteneurs via l'API Docker |
| `common-alloy-data-ci` (volume nomme) | `/var/lib/alloy/data` | Positions de lecture (reprise apres restart) |

### Fichier de configuration

Chemin : `infrastructure/alloy/config.alloy`

```alloy
logging {
  level  = "info"
  format = "logfmt"
}

discovery.docker "containers" {
  host = "unix:///var/run/docker.sock"
}

discovery.relabel "containers" {
  targets = discovery.docker.containers.targets

  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/(.*)"
    target_label  = "container_name"
  }
}

loki.source.docker "containers" {
  host          = "unix:///var/run/docker.sock"
  targets       = discovery.docker.containers.targets
  labels        = {"job" = "docker"}
  relabel_rules = discovery.relabel.containers.rules
  forward_to    = [loki.write.local.receiver]
}

local.file_match "syslog" {
  path_targets = [{"__path__" = "/var/log/syslog", "job" = "syslog"}]
}

loki.source.file "syslog" {
  targets    = local.file_match.syslog.targets
  forward_to = [loki.write.local.receiver]
}

loki.write "local" {
  endpoint {
    url = "http://common-loki-ci:3100/loki/api/v1/push"
  }
}
```

### Composants utilises

| Composant | Role |
|-----------|------|
| `discovery.docker` | Decouvre les conteneurs actifs via le socket Docker |
| `discovery.relabel` | Extrait `container_name` depuis `__meta_docker_container_name` |
| `loki.source.docker` | Tail les logs des conteneurs decouverts, applique les labels |
| `local.file_match` | Resout le glob `/var/log/syslog` en cibles fichiers |
| `loki.source.file` | Tail le(s) fichier(s) trouves par `local.file_match` |
| `loki.write` | Envoie les logs collectes vers l'API push de Loki |

### Labels disponibles dans Loki

| Label | Exemple | Source |
|-------|---------|--------|
| `job` | `docker` / `syslog` | Defini dans la config |
| `container_name` | `common-jenkins-ci` | Relabel depuis `__meta_docker_container_name` (flux `docker` uniquement) |

Ce sont les deux seuls labels produits par la configuration actuelle. Pour en
ajouter (`compose_service`, `host`, ...), completer le bloc
`discovery.relabel` - voir section 4.3.

## 3. Mise en route

Alloy est demarre automatiquement avec la stack monitoring sur le VPS socle. Pour les autres VPS, il faut deployer un conteneur Alloy separement (voir 3.7).

### 3.1 Prerequis

- Loki demarre et accessible sur `http://common-loki-ci:3100` (sur le VPS socle)
- Acces au socket Docker (`/var/run/docker.sock`)
- Reseau Docker partage entre Alloy et Loki

### 3.2 Verifier que Alloy est demarre

```bash
docker ps --filter name=common-alloy-ci
# STATUS = Up
```

### 3.3 Verifier la configuration chargee

```bash
docker logs --tail 30 common-alloy-ci
# Pas d'erreur, "All Alloy components are healthy" attendu
```

### 3.4 Verifier l'etat de sante

```bash
docker exec common-grafana-ci wget -qO- http://common-alloy-ci:12345/-/ready
# "Alloy is ready"

docker exec common-grafana-ci wget -qO- http://common-alloy-ci:12345/-/healthy
# "All Alloy components are healthy"
```

### 3.5 Verifier l'ingestion dans Loki (via Grafana)

1. Ouvrir Grafana : `https://monitor.wk-archi-o24a-4-5-g1.fr`
2. **Explore** > datasource **Loki**
3. Executer :

```logql
{job="docker"}
```

Tu dois voir des logs arriver en live.

### 3.6 Tester la collecte d'un conteneur specifique

```bash
# Generer un log dans un conteneur
docker exec common-jenkins-ci sh -c 'echo "ALLOY_TEST_$(date +%s)" >&2'

# Rechercher dans Grafana Explore :
# {container_name="common-jenkins-ci"} |= "ALLOY_TEST"
```

### 3.7 Deployer Alloy sur un autre VPS (DEV/PREPROD/PROD)

Sur chaque VPS additionnel, creer un `docker-compose.alloy.yml` :

```yaml
services:
  alloy:
    image: grafana/alloy:v1.17.1
    container_name: projects-alloy
    command:
      - "run"
      - "--server.http.listen-addr=0.0.0.0:12345"
      - "--storage.path=/var/lib/alloy/data"
      - "/etc/alloy/config.alloy"
    volumes:
      - ./config.alloy:/etc/alloy/config.alloy:ro
      - alloy-data:/var/lib/alloy/data
      - /var/log:/var/log:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
    restart: unless-stopped

volumes:
  alloy-data:
```

Et le `config.alloy` pointant vers le Loki central (adapter uniquement `loki.write.local.endpoint.url`) :

```alloy
loki.write "local" {
  endpoint {
    url = "http://<IP-VPS-SOCLE>:3100/loki/api/v1/push"
    # ou via domain interne si VPN ou reseau prive
  }
}
```

Demarrer : `docker compose -f docker-compose.alloy.yml up -d`

### 3.8 Verifier les positions (reprise apres restart)

```bash
docker exec common-alloy-ci ls /var/lib/alloy/data
# Contient les caches de position par composant (loki.source.docker.containers, loki.source.file.syslog)
```

## 4. Utilisation au quotidien

### 4.1 Rechercher des logs dans Grafana

Grafana > **Explore** > datasource Loki :

```logql
# Tous les logs Docker
{job="docker"}

# Logs d'un conteneur specifique
{container_name="common-jenkins-ci"}

# Filtrer les erreurs
{job="docker"} |= "error"

# Combiner plusieurs labels
{job="docker", container_name=~"common-.*"} |= "WARN"
```

### 4.2 Creer des alertes sur les logs (optionnel)

Dans Grafana > Alerting, sur une datasource Loki :

```logql
# Alerte si > 10 erreurs en 5 min sur Jenkins
count_over_time({container_name="common-jenkins-ci"} |= "ERROR" [5m]) > 10
```

### 4.3 Ajouter une nouvelle source de logs

Editer `infrastructure/alloy/config.alloy`, ajouter un couple `local.file_match` / `loki.source.file` (ou reutiliser `discovery.docker` pour une source deja basee conteneurs) :

```alloy
local.file_match "nginx" {
  path_targets = [{"__path__" = "/var/log/nginx/*.log", "job" = "nginx"}]
}

loki.source.file "nginx" {
  targets    = local.file_match.nginx.targets
  forward_to = [loki.write.local.receiver]
}
```

Redemarrer Alloy :

```bash
docker compose -f docker-compose.monitoring.yml restart alloy
```

### 4.4 Extraire des champs structures (JSON, logfmt)

Ajouter un composant `loki.process` entre la source et `loki.write` :

```alloy
loki.process "docker_json" {
  forward_to = [loki.write.local.receiver]

  stage.json {
    expressions = { level = "level", msg = "msg" }
  }

  stage.labels {
    values = { level = "" }
  }
}
```

Puis rediriger `loki.source.docker.containers.forward_to` vers `[loki.process.docker_json.receiver]` au lieu de `loki.write.local.receiver` directement.

### 4.5 Exclure certains conteneurs de la collecte

Ajouter un stage de filtrage dans un `loki.process` :

```alloy
stage.match {
  selector = "{container_name=~\"common-prometheus-ci|common-cadvisor-ci\"}"
  action   = "drop"
}
```

(Evite de collecter les metriques en boucle.)

### 4.6 Verifier les metriques d'Alloy lui-meme

```promql
# Nombre de logs envoyes a Loki
rate(loki_write_sent_entries_total[5m])

# Erreurs d'envoi
rate(loki_write_dropped_entries_total[5m])
```

## 5. Tests de validation

### Sante d'Alloy

```bash
docker exec common-grafana-ci wget -qO- http://common-alloy-ci:12345/-/ready
# "Alloy is ready"
```

### Composants actifs

```bash
docker exec common-grafana-ci wget -qO- http://common-alloy-ci:12345/-/healthy
# "All Alloy components are healthy"
```

### Logs d'un conteneur visible dans Loki

Generer un log et le rechercher :

```bash
docker exec common-jenkins-ci sh -c 'echo "TEST_ALLOY_$(date +%s)"'

# Dans Grafana Explore (Loki) :
# {container_name="common-jenkins-ci"} |= "TEST_ALLOY"
```

### Positions persistees

```bash
docker exec common-alloy-ci ls /var/lib/alloy/data
```

### Logs Alloy sains

```bash
docker logs --tail 20 common-alloy-ci | grep -i error
# Ne doit rien retourner
```

### Metrique d'ingestion

```promql
rate(loki_write_sent_entries_total[5m]) > 0
```

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| Aucun log dans Loki | Alloy ne peut pas joindre Loki | Verifier DNS / reseau : `docker exec common-alloy-ci wget -qO- http://common-loki-ci:3100/ready` |
| `context deadline exceeded` | Loki sature | Verifier ressources Loki, reduire ingestion |
| Alloy ne lit pas les logs Docker | Socket non monte ou permissions | Verifier `/var/run/docker.sock:ro` et les droits du groupe docker |
| Labels `container_name` absents | Regle de relabel KO | Verifier `discovery.relabel` dans `config.alloy` |
| Positions perdues apres restart | Volume `common-alloy-data-ci` absent | Verifier que le volume est bien monte sur `/var/lib/alloy/data` |
| Doublons de logs | Alloy demarre 2 fois | `docker ps` pour verifier une seule instance |
| Logs tres en retard | Lag de collecte | Verifier les metriques `loki_write_*` et augmenter ressources |
| `403 Forbidden` vers Loki | Auth activee sur Loki | Ajouter `tenant_id` ou `basic_auth` dans le bloc `loki.write` |
| Logs trop verbeux | Conteneurs qui spamment | Ajouter un `loki.process` avec `stage.match` / action `drop` pour filtrer le bruit |
