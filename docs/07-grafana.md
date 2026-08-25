# Grafana - Visualisation et Dashboards

## 1. Presentation

- **Role** : Plateforme de visualisation des metriques et des logs
- **Datasources** : Prometheus (PromQL) et Loki (LogQL)
- **Port** : 3000 (via Traefik)
- **Sous-domaine** : `monitor.wk-archi-o24a-4-5-g1.fr`
- **Dashboards standardises** : Node Monitoring, Docker Monitoring, CI/CD Monitoring
- **Alerting integre** (complementaire a AlertManager)

### Architecture

```
Prometheus -(PromQL)-> Grafana <-(LogQL)- Loki
                          |
                     Port 3000
                          |
                      Traefik
                          |
             monitor.wk-archi-o24a-4-5-g1.fr
```

## 2. Configuration

### Image et conteneur

| Parametre       | Valeur                  |
|-----------------|-------------------------|
| Image           | `grafana/grafana:13.1.0` |
| Container       | `common-grafana-ci`     |
| Port interne    | 3000                    |
| Reseau Docker   | `ci-network`            |

### Provisioning automatique

```
infrastructure/grafana/provisioning/
|-- datasources/
|   `-- datasources.yml        # Prometheus + Loki
`-- dashboards/
    |-- dashboards.yml          # Configuration du provider
    |-- node-monitoring.json
    |-- docker-monitoring.json
    `-- cicd-monitoring.json
```

### Dashboards preconfigures

| Dashboard          | Description                                             |
|--------------------|---------------------------------------------------------|
| Node Monitoring    | Metriques systeme : CPU, memoire, disque, reseau        |
| Docker Monitoring  | Etat des conteneurs, consommation ressources par service |
| CI/CD Monitoring   | Statistiques pipelines Jenkins, durees, taux de succes  |

### Variables d'environnement

| Variable                        | Description                          |
|---------------------------------|--------------------------------------|
| `GF_SECURITY_ADMIN_USER`       | Nom de l'utilisateur administrateur  |
| `GF_SECURITY_ADMIN_PASSWORD`   | Mot de passe administrateur          |
| `GF_SERVER_ROOT_URL`           | URL publique (via Traefik)           |

## 3. Mise en route

### 3.1 Prerequis

- Le stack monitoring tourne (`docker-compose.monitoring.yml up -d`).
- Prometheus et Loki sont reachable depuis Grafana (memes reseaux Docker).
- `GF_SECURITY_ADMIN_USER` et `GF_SECURITY_ADMIN_PASSWORD` sont definis dans `.env`.

### 3.2 Demarrer Grafana

```bash
cd /opt/furious-ducks/infrastructure
docker compose -f docker-compose.monitoring.yml up -d grafana
```

```bash
docker logs common-grafana-ci --tail 30
# Attendre : "HTTP Server Listen" sur port 3000
```

### 3.3 Premier login

1. Ouvre `https://monitor.wk-archi-o24a-4-5-g1.fr`
2. User : valeur de `GF_SECURITY_ADMIN_USER` (par defaut `admin`)
3. Password : `GF_SECURITY_ADMIN_PASSWORD`
4. Grafana ne te demandera **pas** de changer le mot de passe au premier login (configure via env vars). Mais c'est une bonne pratique de le faire via Profile.

### 3.4 Verifier les datasources

1. **Configuration (icone engrenage) > Data Sources**
2. Tu dois voir **Prometheus** et **Loki** auto-provisionnes
3. Clique sur chacun > **Save & Test** : tu dois voir **Data source is working**

Si erreur :
- Verifie que les conteneurs Prometheus et Loki tournent : `docker ps`
- Verifie que Grafana est sur le meme reseau Docker (`ci_network`)
- L'URL doit etre `http://common-prometheus-ci:9090` et `http://common-loki-ci:3100` (DNS interne Docker)

### 3.5 Verifier les dashboards provisionnes

1. **Dashboards (icone 4 carres) > Browse**
2. Tu dois voir un dossier **Furious Ducks** contenant :
   - Node Monitoring
   - Docker Monitoring
   - CI/CD Monitoring
3. Ouvre chaque dashboard : tu dois voir des donnees (CPU, memoire, conteneurs, requetes Traefik)

Si les panels affichent "No data" :
- Verifier que les targets Prometheus correspondants sont UP (voir `06-prometheus.md`)
- Verifier la duree affichee (en haut a droite : "Last 1 hour" par defaut)

### 3.6 Configurer un canal de notification (Contact Point)

Pour recevoir les alertes Grafana (independantes d'AlertManager) :

1. **Alerting (icone cloche) > Contact points > + Add contact point**
2. Name : `mattermost`
3. Type : Webhook
4. URL : ton Mattermost incoming webhook URL
5. **Test** : envoie un message test
6. **Save**

### 3.7 Importer un dashboard depuis grafana.com

Pour ajouter un dashboard pre-fait (ex: dashboard Docker officiel #893) :

1. **Dashboards > Import**
2. Saisis l'ID (ex: `893`) ou colle le JSON
3. Selectionne la datasource Prometheus
4. Import

## 4. Utilisation au quotidien

### 4.1 Naviguer dans un dashboard

- **Time picker (haut a droite)** : choisir la duree (5m, 1h, 6h, 24h, 7j, custom)
- **Refresh interval** : auto-refresh (5s, 30s, 1m, 5m...)
- **Variables (haut)** : filtrer par instance, par service, etc.
- **Panel** : clic gauche pour zoomer, clic droit pour menu (View, Edit, Inspect, More)

### 4.2 Explorer (Ad-hoc queries)

**Explore (icone boussole)** : ecrire des requetes PromQL ou LogQL en direct.

Exemples :
```promql
# CPU par instance
100 - (avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)
```

```logql
# Toutes les erreurs des conteneurs sur 1h
{job="docker"} |= "error" | json
```

### 4.3 Creer un dashboard custom

1. **Dashboards > New Dashboard > Add Visualization**
2. Selectionne datasource (Prometheus ou Loki)
3. Saisis la requete
4. Configure le visuel (graph, table, gauge, stat...)
5. **Apply > Save dashboard**

### 4.4 Creer une alerte Grafana

1. Sur un panel : **Edit > Alert > Create alert rule**
2. Define query : Prometheus / Loki query
3. Expressions : ex. `WHEN avg() OF query(A,5m,now) IS ABOVE 80`
4. Folder + Group + Evaluation interval
5. Notifications : selectionne ton contact point
6. **Save and exit**

### 4.5 Gerer les utilisateurs

**Server Admin (icone bouclier en bas) > Users > New user**.

Roles :
- **Viewer** : voir les dashboards
- **Editor** : creer / modifier des dashboards
- **Admin** : tout, y compris gestion users et datasources

### 4.6 Sauvegarder un dashboard

Si tu modifies un dashboard manuellement (pas via provisioning), pour le sauvegarder dans le code :

1. Sur le dashboard : **Share > Export > Save to file**
2. Telecharge le JSON
3. Place-le dans `infrastructure/grafana/provisioning/dashboards/json/`
4. Commit dans Git

### 4.7 Mettre a jour Grafana

```bash
# Editer docker-compose.monitoring.yml et changer le tag
docker compose -f docker-compose.monitoring.yml pull grafana
docker compose -f docker-compose.monitoring.yml up -d grafana
```

Le volume `common-grafana-data-ci` est preserve (dashboards custom, users, alertes).

## 5. Tests de validation

### Verification de l'acces web

```bash
curl -f https://monitor.wk-archi-o24a-4-5-g1.fr/api/health
# Reponse attendue : {"commit":"...","database":"ok","version":"13.1.0"}
```

### Verification des datasources

1. Se connecter a `https://monitor.wk-archi-o24a-4-5-g1.fr`
2. Aller dans **Configuration > Data Sources**
3. Verifier que **Prometheus** et **Loki** sont presents et en statut **"Working"**
4. Cliquer sur **"Test"** pour chaque datasource

### Verification des dashboards

1. Aller dans **Dashboard > Browse > Furious Ducks**
2. Verifier la presence des 3 dashboards : Node, Docker, CI/CD Monitoring
3. Ouvrir chaque dashboard et verifier que les panneaux affichent des donnees

### Test d'alerting

1. Aller dans **Alerting > Alert Rules**
2. Creer une alerte test (ex : CPU > 90%)
3. Configurer un contact point (email ou webhook)
4. Verifier que l'alerte se declenche correctement

### Verification des logs via Loki

1. Aller dans **Explore**
2. Selectionner la datasource **Loki**
3. Executer une requete LogQL : `{job="docker"} |= "error"`
4. Verifier que les logs s'affichent correctement avec les labels attendus

### Verification via CLI

```bash
docker ps --filter name=common-grafana-ci
docker logs common-grafana-ci --tail 50
docker exec common-grafana-ci ls /etc/grafana/provisioning/datasources/
docker exec common-grafana-ci ls /etc/grafana/provisioning/dashboards/
```

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| Datasource Prometheus en "Error" | Mauvais hostname interne | Verifier URL `http://common-prometheus-ci:9090` |
| Dashboards "No data" | Targets Prometheus DOWN | Voir Prometheus > Targets |
| 503 sur /api/health | Grafana pas demarre | `docker logs`, attendre 30s |
| Alertes ne partent pas | Contact point mal configure | Tester via "Send test notification" |
| Dashboards custom disparus apres restart | Provisioning ecrase | Sauvegarder en JSON et provisionner |
| `Forbidden` au login | User inexistant | Recreer via Server Admin > Users |
