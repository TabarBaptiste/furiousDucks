# Loki - Agregation de Logs

## 1. Presentation

- **Role** : Systeme d'agregation de logs, integre a l'ecosysteme Grafana
- **Collecte** : Logs de tous les conteneurs Docker et du systeme
- **Port** : 3100 (interne uniquement, pas d'exposition externe)
- **Pas de sous-domaine** (service interne, `traefik.enable=false`)
- **Interrogation** : Via LogQL dans Grafana
- **Retention** : **90 jours** (`retention_period: 2160h`), conformement a
  l'exigence CST de conservation des logs d'audit >= 90 jours
- **Leger et adapte** a l'echelle de l'agence (alternative plus legere qu'ELK)

### Architecture

```
Conteneurs Docker --(logs)--> Alloy --> Loki <--(LogQL)-- Grafana
                                            |
                                       Port 3100
                                  (interne uniquement)
                                            |
                                  Stockage filesystem local
```

Loki n'indexe que les **labels** (metadonnees) et non le contenu des logs : c'est tres leger en RAM et disque, mais les recherches plein texte se font au moment de la requete (LogQL `|=`).

## 2. Configuration

### Image et conteneur

| Parametre       | Valeur                 |
|-----------------|------------------------|
| Image           | `grafana/loki:3.7.3`   |
| Container       | `common-loki-ci`       |
| Port interne    | 3100 (HTTP), 9095 (gRPC) |
| Reseau Docker   | `ci_network` + `monitoring_network` |

### Fichier de configuration

`infrastructure/loki/loki-config.yml` :
- Mode single-tenant (`auth_enabled: false`)
- Schema TSDB v13, index par 24h
- Stockage filesystem local (`/loki/chunks`, `/loki/rules`, `/loki/compactor`)
- Retention : `2160h` (90 jours), `max_query_length: 2161h`, nettoyage
  automatique par le **compacteur** (`retention_enabled: true`)
- Rejet des samples plus vieux que 7 jours (`reject_old_samples_max_age: 168h`)

### Structure de stockage

```
/loki/
|-- chunks/          # Donnees compressees + index TSDB
|-- rules/
`-- compactor/       # Espace de travail du compacteur (retention)
```

(Il n'y a plus de repertoire `boltdb-shipper/` : le schema en vigueur est
`tsdb` depuis `2024-01-01`.)

### Integration avec Grafana

Loki est automatiquement provisionne comme datasource dans Grafana via `infrastructure/grafana/provisioning/datasources/datasources.yml`. **Aucune action manuelle**.

### Labels disponibles

| Label             | Description                   |
|-------------------|-------------------------------|
| `job`             | Source du log (`docker`, `syslog`) |
| `container_name`  | Nom du conteneur Docker (flux `docker` uniquement) |

Ce sont les **seuls** labels produits par la configuration Alloy actuelle
(`infrastructure/alloy/config.alloy`). Pour en ajouter (ex. `compose_service`
depuis `__meta_docker_container_label_com_docker_compose_service`), il faut
completer le bloc `discovery.relabel` - voir `16-alloy.md` section 4.3.

## 3. Mise en route

Loki et Alloy sont demarres ensemble dans le stack monitoring. Loki ne necessite **aucune configuration manuelle** apres le demarrage : il est immediatement utilisable.

### 3.1 Prerequis

- Le stack monitoring est demarre.
- Alloy est correctement configure (voir `16-alloy.md`).
- Le volume `common-loki-data-ci` existe.

### 3.2 Verifier que Loki tourne

```bash
docker ps --filter name=common-loki-ci
docker logs common-loki-ci --tail 30
# Tu dois voir : "Loki started"
```

### 3.3 Verifier l'ingestion de logs

Loki recoit les logs depuis Alloy. Pour verifier, depuis n'importe quel autre conteneur du reseau :

```bash
docker exec common-grafana-ci wget -qO- http://common-loki-ci:3100/ready
# Reponse : "ready"
```

### 3.4 Faire ta premiere requete LogQL

1. Ouvre Grafana : `https://monitor.wk-archi-o24a-4-5-g1.fr`
2. **Explore (boussole) > Datasource: Loki**
3. Saisis dans la barre de requete :
   ```
   {job="docker"}
   ```
4. **Run query** : tu dois voir des lignes de logs.

Si **aucune log n'apparait** :
- Verifie que Alloy tourne : `docker logs common-alloy-ci`
- Verifie que les conteneurs generent des logs : `docker logs common-jenkins-ci`
- Verifie l'acces au socket Docker : `docker exec common-alloy-ci ls /var/run/docker.sock`

### 3.5 (Optionnel) Configurer la duree de retention

La retention configuree est de **90 jours**. Le `table_manager` est deprecie et
**supprime en Loki 3.x** : la retention se regle dans `limits_config` et est
appliquee par le `compactor`. Pour modifier, edite
`infrastructure/loki/loki-config.yml` :

```yaml
limits_config:
  retention_period: 1440h   # 60 jours
  max_query_length: 1441h   # garder >= retention_period

compactor:
  retention_enabled: true   # doit rester actif, sinon rien n'est purge
```

Attention : reduire la retention sous 90 jours sort du cadre CST (conservation
des logs d'audit).

Puis :

```bash
docker compose -f docker-compose.monitoring.yml restart loki
```

## 4. Utilisation au quotidien

### 4.1 Requetes LogQL utiles

```logql
# Toutes les logs d'un conteneur specifique
{container_name="common-jenkins-ci"}

# Filtrer les erreurs
{job="docker"} |= "error"

# Insensible a la casse
{job="docker"} |~ "(?i)error"

# Exclure
{container_name="common-gitlab-ci"} != "DEBUG"

# Parser JSON et filtrer un champ
{container_name="common-jenkins-ci"} | json | level="ERROR"

# Logs Loki avec format key=value
{container_name="common-traefik-ci"} | logfmt | status="500"

# Logs des 15 dernieres minutes uniquement
{container_name="common-gitlab-ci"} | json | __timestamp__ > now() - 15m

# Compter les erreurs par conteneur sur 1h (transforme logs en metriques)
sum by (container_name) (count_over_time({job="docker"} |= "error" [1h]))

# Top 5 conteneurs qui loggent le plus
topk(5, sum by (container_name) (rate({job="docker"}[5m])))
```

### 4.2 Faire une recherche cross-conteneurs

```logql
{job="docker"} |= "exception" | line_format "{{.container_name}}: {{.__line__}}"
```

### 4.3 Creer un dashboard avec Loki

1. Grafana > Dashboards > New > Add Visualization
2. Datasource : Loki
3. Type : **Logs panel** ou **Time series** (avec une requete metrique)
4. Saisis la requete LogQL
5. **Apply > Save**

### 4.4 Telecharger des logs

Dans Explore : **Inspector > Data > Download CSV** ou **Logs > More > Download**.

### 4.5 Sauvegarder Loki

Le volume `common-loki-data-ci` est sauvegarde par Restic (voir `09-restic-minio.md`). Tu peux exclure les chunks anciens si l'espace est un probleme (mais les logs sont generalement compressibles).

### 4.6 Mettre a jour Loki

```bash
docker compose -f docker-compose.monitoring.yml pull loki
docker compose -f docker-compose.monitoring.yml up -d loki
```

**Attention** : verifier la compatibilite de schema avant tout saut de version.
La configuration est deja en `tsdb`/`v13` ; c'est le passage a un futur schema
qui necessiterait d'ajouter une nouvelle entree datee dans `schema_config`
(sans jamais modifier les entrees existantes).

## 5. Tests de validation

### Verification du service

```bash
docker exec common-grafana-ci wget -qO- http://common-loki-ci:3100/ready
# Reponse attendue : "ready"

docker ps --filter name=common-loki-ci
```

### Verification de l'ingestion

1. Ouvrir Grafana : `https://monitor.wk-archi-o24a-4-5-g1.fr`
2. Aller dans **Explore**
3. Selectionner la datasource **Loki**
4. Executer la requete : `{job="docker"}`
5. Verifier que des logs apparaissent

### Verification des metriques Loki

```bash
docker exec common-grafana-ci wget -qO- http://common-loki-ci:3100/metrics | head -20
```

### Recherche de logs par conteneur

Voir les exemples LogQL en section 4.1.

### Verification de la retention

```bash
docker exec common-loki-ci ls /loki/chunks
docker exec common-loki-ci du -sh /loki/
docker logs common-loki-ci 2>&1 | grep -i compactor
```

### Verification de la sante globale

```bash
docker logs common-loki-ci --tail 50
docker logs common-loki-ci 2>&1 | grep -i "error\|panic\|fatal"
docker exec common-grafana-ci wget -qO- http://common-loki-ci:3100/metrics | grep loki_ingester
```

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| Aucun log dans Grafana | Alloy down | `docker logs common-alloy-ci` |
| `entry too far behind` | Logs trop vieux (> 7j) | Normal a l'init, ignorer |
| Disque Loki sature | Retention trop longue | Reduire `retention_period` |
| Requete LogQL tres lente | Pas de label discriminant | Ajouter un label fort comme `container_name=` |
| `too many outstanding requests` | Limites depassees | Augmenter `max_outstanding_requests_per_tenant` |
