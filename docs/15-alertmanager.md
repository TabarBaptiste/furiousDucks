# AlertManager - Gestion des Alertes

## 1. Presentation

- **Role** : Reception, deduplication, groupement et routage des alertes generees par Prometheus
- **Canaux de notification configures** : Email (SMTP) et Mattermost (webhook
  entrant, via `slack_configs` - les webhooks Mattermost sont compatibles
  Slack). PagerDuty n'est pas configure (exemple d'extension en 4.5).
- **Port** : `9093` (interne uniquement, accessible par Prometheus)
- **Pas de sous-domaine et aucun port hote** (`traefik.enable=false`) :
  l'administration passe exclusivement par `amtool` dans le conteneur
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
| `critical` | Email + Mattermost         | `720h`          |
| `warning`  | Email                      | `720h`          |

Le `repeat_interval` est volontairement fixe tres haut (30 jours) : chaque
alerte notifie **une fois quand elle se declenche et une fois quand elle se
resout** (`send_resolved: true`), sans rappel periodique entre les deux.

## 2. Configuration

### Image et conteneur

| Parametre | Valeur |
|-----------|--------|
| Image | `prom/alertmanager:v0.33.0` |
| Container | `common-alertmanager-ci` |
| Port interne | `9093` (aucun port hote) |
| Reseau | `ci_network` + `monitoring_network` |
| Secrets | `./alertmanager/secrets` monte en lecture seule sur `/etc/alertmanager/secrets` |

### Fichier de configuration

Chemin : `infrastructure/alertmanager/alertmanager.yml`

Sections principales :

- **global** : SMTP (`smtp_smarthost`, `smtp_from`, `smtp_auth_username`), le
  mot de passe etant lu depuis un **fichier monte**
  (`smtp_auth_password_file: /etc/alertmanager/secrets/smtp_password`)
- **route** : arbre de routage (default + child routes par severity)
- **receivers** :
  - `default-receiver` : email warning
  - `critical-receiver` : email + Mattermost (`slack_configs` avec
    `api_url_file: /etc/alertmanager/secrets/mattermost_webhook`)
- **inhibit_rules** : `critical` inhibe `warning` du meme `alertname`

Aucun secret n'est en dur ni passe par variable d'environnement : les deux
secrets sont des fichiers dans `infrastructure/alertmanager/secrets/`
(non committes, avec un `.example` chacun comme modele).

### Parametres de groupement

| Parametre | Valeur | Role |
|-----------|--------|------|
| `group_by` | `[alertname, severity]` | Regroupe par nom et severite |
| `group_wait` | `30s` | Delai d'attente avant premier envoi |
| `group_interval` | `5m` | Delai entre alertes d'un meme groupe |
| `repeat_interval` | `720h` (les deux severites) | Pas de rappel periodique : une notification au firing, une au resolved |

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

**Email** : le destinataire et l'expediteur sont fixes dans `alertmanager.yml`
(`smtp_from` / `to`), le relai SMTP est `smtp.gmail.com:587`. Seul le mot de
passe vient d'un fichier secret.

**Mattermost** : webhook entrant vers le canal `devops-alerts`, l'URL etant
lue depuis `infrastructure/alertmanager/secrets/mattermost_webhook`.
(A ne pas confondre avec `MATTERMOST_WEBHOOK_URL` du `.env`, qui alimente
Jenkins et les scripts de backup, pas AlertManager.)

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
6. L'ecrire dans le fichier secret (jamais dans le `.env`, jamais committe) :

```bash
cd /opt/furious-ducks/infrastructure
cp alertmanager/secrets/mattermost_webhook.example alertmanager/secrets/mattermost_webhook
printf '%s' 'https://chat.wk-archi-o24a-4-5-g1.fr/hooks/abcdef1234567890' \
  > alertmanager/secrets/mattermost_webhook
chmod 600 alertmanager/secrets/mattermost_webhook
```

### 3.3 Configurer le SMTP

Le smarthost, l'expediteur et le destinataire sont dans
`infrastructure/alertmanager/alertmanager.yml` (a editer directement). Seul le
mot de passe est un secret monte :

```bash
cd /opt/furious-ducks/infrastructure
cp alertmanager/secrets/smtp_password.example alertmanager/secrets/smtp_password
printf '%s' '<mot-de-passe-app-smtp>' > alertmanager/secrets/smtp_password
chmod 600 alertmanager/secrets/smtp_password
```

Les deux fichiers etant montes en lecture seule, tout changement demande un
`docker compose -f docker-compose.monitoring.yml restart alertmanager`.

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

# Ou via API, depuis un conteneur du meme reseau (le port 9093 n'est pas
# publie sur l'hote)
docker exec common-prometheus-ci wget -qO- http://common-alertmanager-ci:9093/api/v2/alerts
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

Recharger Prometheus (sans redemarrer). Le port 9090 n'est pas publie sur
l'hote :

```bash
docker exec common-prometheus-ci wget --post-data='' -qO- http://localhost:9090/-/reload
```

### 4.4 Modifier la configuration AlertManager

Editer `infrastructure/alertmanager/alertmanager.yml`. Verifier la syntaxe
**avant** d'appliquer :

```bash
cd /opt/furious-ducks
docker run --rm -v $(pwd)/infrastructure/alertmanager/alertmanager.yml:/tmp/a.yml \
  prom/alertmanager:v0.33.0 amtool check-config /tmp/a.yml
```

Puis appliquer. Le fichier est monte en lecture seule et AlertManager n'est pas
demarre avec `--web.enable-lifecycle` : un `SIGHUP` (ou un restart) est requis,
et un restart est de toute facon necessaire si un fichier de `secrets/` a
change.

```bash
docker kill -s HUP common-alertmanager-ci
# ou, plus sur :
docker compose -f docker-compose.monitoring.yml restart alertmanager
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
2. **AlertManager** : `amtool alert` (pas d'UI web : le service n'est pas expose)
3. **Logs AlertManager** :

```bash
docker logs --tail 50 common-alertmanager-ci
```

4. **SMTP** : erreur de connexion ? Verifier firewall sortant port 587.
5. **Mattermost** : webhook encore valide ? Tester :

```bash
docker exec common-alertmanager-ci sh -c \
  'wget -qO- --post-data="{\"text\":\"Test\"}" \
     --header="Content-Type: application/json" \
     "$(cat /etc/alertmanager/secrets/mattermost_webhook)"'
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
3. Verifier la boite du destinataire declare dans `alertmanager.yml`

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
| `authentication failed` (SMTP) | Mauvais user/password | Revalider `smtp_auth_username` dans `alertmanager.yml` et le fichier `secrets/smtp_password` |
| `connection refused` webhook Mattermost | URL incorrecte | Verifier `alertmanager/secrets/mattermost_webhook` |
| Alertes en doublon | Mauvais `group_by` | Ajouter des labels communs dans `group_by` |
| Alertes trop frequentes | `repeat_interval` trop court | Valeur actuelle : `720h` (aucun rappel periodique) |
| Alertes jamais envoyees mais `Firing` | Mauvais routage | `amtool config show` pour verifier les routes |
| `config reload failed` | Syntaxe YAML invalide | `amtool check-config` avant reload |
| Silences disparaissent au restart | Volume non persistant | Verifier `alertmanager-data:/alertmanager` |
| Notifications Mattermost sans couleur | Config obsolete | Verifier le format `attachments` dans le template |
