# AlertManager - Gestion des Alertes

## 1. Presentation

- **Role** : Reception, deduplication, groupement et routage des alertes generees par Prometheus
- **Canaux de notification** : Email (SMTP) + Mattermost (webhook) + PagerDuty (optionnel)
- **Port** : `9093` (interne uniquement, accessible par Prometheus)
- **Pas de sous-domaine** (service interne)
- **Groupement** par `alertname` et `severity` (evite le spam d'alertes)
- **Inhibition** : les alertes `critical` inhibent les `warning` du meme service
- **Silencing** : possibilite de mettre en pause des alertes (maintenance planifiee)

### Architecture

```
+------------+    alertes    +---------------+    notifs     +---------+
| Prometheus | ------------> | AlertManager  | ------------> | Email   |
| (rules)    |               |               |               +---------+
+------------+               | :9093         |               | Matters |
                             |               |               +---------+
                             +---------------+
                                    |
                                    | dedoublonne
                                    | groupe par alertname+severity
                                    | applique routage par severity
                                    v
```

Prometheus evalue les regles d'alerte (`rules/alerts.yml`), envoie les alertes a AlertManager, qui :

1. Dedoublonne les alertes identiques
2. Les groupe par `alertname` + `severity`
3. Attend 30s supplementaires pour grouper les alertes similaires (`group_wait`)
4. Applique le routage par `severity` (critical -> email + Mattermost, warning -> email seulement)
5. Retransmet aux canaux de notification

### Niveaux de severite

| Severite   | Canaux                     | Repeat interval |
|------------|----------------------------|-----------------|
| `critical` | Email + Mattermost         | 1 heure         |
| `warning`  | Email                      | 4 heures        |

## 2. Configuration

### Image et conteneur

| Parametre | Valeur |
|-----------|--------|
| Image | `prom/alertmanager:v0.27.0` |
| Container | `common-alertmanager-ci` |
| Port interne | `9093` |
| Reseau | `ci_network` + `monitoring_network` |

### Fichier de configuration

Chemin : `infrastructure/alertmanager/alertmanager.yml`

Sections principales :

- **global** : SMTP (smarthost, from, user, password)
- **route** : arbre de routage (default + child routes par severity)
- **receivers** :
  - `default-receiver` : email warning
  - `critical-receiver` : email + Mattermost
- **inhibit_rules** : `critical` inhibe `warning` du meme `alertname`

### Parametres de groupement

| Parametre | Valeur | Role |
|-----------|--------|------|
| `group_by` | `[alertname, severity]` | Regroupe par nom et severite |
| `group_wait` | `30s` | Delai d'attente avant premier envoi |
| `group_interval` | `5m` | Delai entre alertes d'un meme groupe |
| `repeat_interval` | `4h` (warning) / `1h` (critical) | Rappel si alerte active |

### Integration Prometheus

Dans `infrastructure/prometheus/prometheus.yml` :

```yaml
alerting:
  alertmanagers:
    - static_configs:
        - targets:
            - common-alertmanager-ci:9093
```

### Notifications configurees

**Email** : `devops@wk-archi-o24a-4-5-g1.fr` (destinataire), relai SMTP configure via `.env` (variables `SMTP_HOST`, `SMTP_USER`, `SMTP_PASSWORD`).

**Mattermost** : webhook entrant vers le canal `devops-alerts`, URL configuree via `MATTERMOST_WEBHOOK_URL`.

## 3. Mise en route

AlertManager est demarre automatiquement avec la stack monitoring.

### 3.1 Prerequis

- Stack monitoring demarree (`docker-compose.monitoring.yml up -d`)
- Regles d'alerte definies dans `infrastructure/prometheus/rules/alerts.yml`
- Serveur SMTP accessible
- Canal Mattermost `devops-alerts` cree + webhook entrant configure

### 3.2 Creer le webhook Mattermost

Sur Mattermost :

1. Menu principal > **Integrations** > **Incoming Webhooks**
2. **Add Incoming Webhook**
3. Nom : `AlertManager Furious Ducks`
4. Channel : `devops-alerts` (creer si besoin)
5. Copier l'URL du webhook
6. Coller dans `.env` :

```bash
MATTERMOST_WEBHOOK_URL=https://chat.example.com/hooks/abcdef1234567890
```

### 3.3 Configurer le SMTP dans `.env`

```bash
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USER=ci-alerts@wk-archi-o24a-4-5-g1.fr
SMTP_PASSWORD=<mot-de-passe-app-smtp>
```

### 3.4 Demarrer la stack monitoring

```bash
cd /opt/furious-ducks/infrastructure
docker compose -f docker-compose.monitoring.yml up -d alertmanager
```

### 3.5 Verifier la sante d'AlertManager

```bash
docker exec common-prometheus-ci wget -qO- http://common-alertmanager-ci:9093/-/healthy
# Reponse attendue : 200 OK / OK
```

### 3.6 Verifier la configuration chargee

```bash
docker exec common-alertmanager-ci amtool \
  --alertmanager.url=http://localhost:9093 \
  config show
```

### 3.7 Tester l'envoi d'une alerte (simulation)

```bash
docker exec common-alertmanager-ci amtool \
  --alertmanager.url=http://localhost:9093 \
  alert add alertname=TestAlert severity=critical \
  --annotation="summary=Test alerte critique"
```

Verifier :
- Email recu sur `devops@...`
- Message dans `#devops-alerts` sur Mattermost

Ensuite supprimer l'alerte test :

```bash
docker exec common-alertmanager-ci amtool \
  --alertmanager.url=http://localhost:9093 \
  silence add alertname=TestAlert --duration=5m \
  --comment="Test silence"
```

### 3.8 Verifier l'integration avec Prometheus

1. Ouvrir `https://prometheus.wk-archi-o24a-4-5-g1.fr/alerts`
2. Section **Alerting** : toutes les regles doivent etre en `Inactive` ou `Pending`/`Firing`
3. Section **Status > Runtime & Build Information** -> AlertManager listed

## 4. Utilisation au quotidien

### 4.1 Lister les alertes actives

```bash
docker exec common-alertmanager-ci amtool \
  --alertmanager.url=http://localhost:9093 alert

# Ou via API
curl -s http://localhost:9093/api/v2/alerts | jq
```

### 4.2 Creer un silence (maintenance planifiee)

Avant de redemarrer Jenkins pour maintenance :

```bash
docker exec common-alertmanager-ci amtool \
  --alertmanager.url=http://localhost:9093 \
  silence add alertname=JenkinsDown \
  --duration=30m \
  --comment="Maintenance planifiee"
```

Lister les silences actifs :

```bash
docker exec common-alertmanager-ci amtool \
  --alertmanager.url=http://localhost:9093 silence query
```

Supprimer un silence :

```bash
docker exec common-alertmanager-ci amtool \
  --alertmanager.url=http://localhost:9093 \
  silence expire <silence-id>
```

### 4.3 Ajouter une nouvelle regle d'alerte

Editer `infrastructure/prometheus/rules/alerts.yml` :

```yaml
- alert: DiskSpaceLow
  expr: (node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}) * 100 < 20
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Disque /  < 20% libre sur {{ $labels.instance }}"
    description: "Espace libre : {{ $value }}%"
```

Recharger Prometheus (sans redemarrer) :

```bash
curl -X POST http://localhost:9090/-/reload
```

### 4.4 Modifier la configuration AlertManager

Editer `infrastructure/alertmanager/alertmanager.yml`, puis recharger :

```bash
curl -X POST http://localhost:9093/-/reload
```

Verifier la syntaxe avant de recharger :

```bash
docker run --rm -v $(pwd)/infrastructure/alertmanager/alertmanager.yml:/tmp/a.yml \
  prom/alertmanager:v0.27.0 amtool check-config /tmp/a.yml
```

### 4.5 Ajouter un canal de notification (ex : PagerDuty)

Dans `alertmanager.yml`, ajouter a la liste des receivers :

```yaml
- name: pagerduty-critical
  pagerduty_configs:
    - service_key: ${PAGERDUTY_SERVICE_KEY}
      severity: critical
```

Puis router les alertes `severity=critical` vers ce receiver.

### 4.6 Deboguer une alerte non recue

1. **Prometheus** : `/alerts` -> l'alerte est-elle en `Firing` ?
2. **AlertManager** : l'alerte est-elle visible dans `/#/alerts` ?
3. **Logs AlertManager** :

```bash
docker logs --tail 50 common-alertmanager-ci
```

4. **SMTP** : erreur de connexion ? Verifier firewall sortant port 587.
5. **Mattermost** : webhook encore valide ? Tester :

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"text":"Test"}' \
  $MATTERMOST_WEBHOOK_URL
```

### 4.7 Historique des notifications envoyees

AlertManager ne persiste pas l'historique par defaut. Pour un audit :

- **Mattermost** : historique du canal `devops-alerts`
- **Email** : archive email du compte recepteur
- **Loki** : si les logs AlertManager sont collectes, rechercher `{container_name="common-alertmanager-ci"} |= "notify"`

## 5. Tests de validation

### Sante

```bash
docker exec common-prometheus-ci wget -qO- http://common-alertmanager-ci:9093/-/healthy
```

### Configuration valide

```bash
docker exec common-alertmanager-ci amtool check-config /etc/alertmanager/alertmanager.yml
```

### Simuler une alerte

Voir section 3.7.

### Verifier l'email

1. Envoyer une alerte test
2. Attendre 30s (group_wait)
3. Verifier la boite `devops@wk-archi-o24a-4-5-g1.fr`

### Verifier Mattermost

1. Canal `#devops-alerts`
2. Message du webhook AlertManager avec le contenu de l'alerte

### Tester le silencing

```bash
# Silencer une alerte de test
docker exec common-alertmanager-ci amtool \
  silence add --duration=5m alertname=TestAlert

# Declencher l'alerte -> AUCUNE notification ne doit partir
```

### Verifier l'inhibition

Declencher une `critical` + une `warning` avec meme `alertname` : seule la `critical` doit remonter.

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| Aucune alerte recue | SMTP KO | `docker logs common-alertmanager-ci | grep -i smtp` |
| `authentication failed` (SMTP) | Mauvais user/password | Revalider les credentials dans `.env` |
| `connection refused` webhook Mattermost | URL incorrecte | Verifier `MATTERMOST_WEBHOOK_URL` |
| Alertes en doublon | Mauvais `group_by` | Ajouter des labels communs dans `group_by` |
| Alertes trop frequentes | `repeat_interval` trop court | Passer a 4h (warning) / 1h (critical) |
| Alertes jamais envoyees mais `Firing` | Mauvais routage | `amtool config show` pour verifier les routes |
| `config reload failed` | Syntaxe YAML invalide | `amtool check-config` avant reload |
| Silences disparaissent au restart | Volume non persistant | Verifier `alertmanager-data:/alertmanager` |
| Notifications Mattermost sans couleur | Config obsolete | Verifier le format `attachments` dans le template |
