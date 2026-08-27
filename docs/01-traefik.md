# Traefik - Reverse Proxy & Ingress

## 1. Presentation

- **Role** : Point d'entree unique HTTP/HTTPS pour tous les services du workflow CI/CD
- **Fonctions** : routage automatique, TLS automatique (Let's Encrypt), middlewares de securite, dashboard
- **Ports publies sur l'hote** :
  - `80` : HTTP (redirection automatique vers HTTPS)
  - `443` : HTTPS (point d'entree principal)
- **Entrypoint interne** : `8082` (metriques Prometheus) - **non publie** sur l'hote,
  joignable uniquement depuis les autres conteneurs de `ci_network`.
  Le dashboard n'a pas non plus de port hote : il passe par le routeur HTTPS
  `traefik.<domaine>` (service `api@internal`).
- **Sous-domaine** : `traefik.wk-archi-o24a-4-5-g1.fr` (dashboard, protege par
  basic auth `traefik-auth` + `rate-limit`)
- **Version** : Traefik v3.7.8 (tag epingle dans `infrastructure/docker-compose.yml`)
- **Gestion des sous-domaines** :
  - `git.wk-archi-o24a-4-5-g1.fr` (GitLab)
  - `ci.wk-archi-o24a-4-5-g1.fr` (Jenkins)
  - `registry.wk-archi-o24a-4-5-g1.fr` (Harbor)
  - `sonar.wk-archi-o24a-4-5-g1.fr` (SonarQube)
  - `chat.wk-archi-o24a-4-5-g1.fr` (Mattermost)
  - `monitor.wk-archi-o24a-4-5-g1.fr` (Grafana)
  - `grafana.wk-archi-o24a-4-5-g1.fr` (Grafana, alias du meme service)
  - `prometheus.wk-archi-o24a-4-5-g1.fr` (Prometheus, basic auth)
  - `s3.wk-archi-o24a-4-5-g1.fr` (MinIO API)
  - `minio.wk-archi-o24a-4-5-g1.fr` (MinIO Console)

## 2. Configuration

### Fichier statique

- Chemin : `infrastructure/traefik/traefik.yml`
- Definition des entrypoints (`web` :80, `websecure` :443, `metrics` :8082)
- Activation du provider Docker
- Activation de l'API et du dashboard

### Configuration dynamique

- Chemin : `infrastructure/traefik/dynamic/`
- Middlewares, TLS options, routes manuelles

### Middlewares de securite

- **secure-headers** :
  - HSTS (`Strict-Transport-Security: max-age=63072000; includeSubDomains; preload`)
  - `X-Frame-Options: DENY`
  - `X-Content-Type-Options: nosniff`
  - `Content-Security-Policy` restrictive
  - `Referrer-Policy: strict-origin-when-cross-origin`
- **rate-limit** :
  - Limitation du nombre de requetes par source IP
  - Protection contre les abus et le brute-force

### TLS

- Version minimum : TLS 1.2
- Ciphersuites modernes (ECDHE, AES-GCM, ChaCha20)
- SNI strict active

### ACME (Let's Encrypt)

- Challenge HTTP-01
- Stockage des certificats dans le fichier bind-monte `traefik/acme/acme.json`
  (permissions 600 obligatoires), et non dans un volume Docker nomme
- Renouvellement automatique avant expiration

### Decouverte Docker

- Les conteneurs sont decouverts automatiquement via les labels Docker
- Chaque service definit ses propres regles de routage via labels

### Role dans le deploiement Blue/Green

- La bascule de trafic s'effectue via la modification des labels Docker
- Le routeur Traefik pointe vers le conteneur actif (blue ou green)
- Le rollback consiste a re-basculer les labels vers l'ancienne version

## 3. Mise en route

C'est le **premier service** que tu dois lancer : tous les autres dependent de lui pour etre exposes en HTTPS. Si tu n'as pas encore lu `00-getting-started.md`, fais-le d'abord.

### 3.1 Prerequis avant de demarrer Traefik

- Le DNS doit pointer vers ton serveur (sinon Let's Encrypt ne pourra pas valider le challenge HTTP-01).
  ```bash
  dig +short traefik.wk-archi-o24a-4-5-g1.fr
  # Doit afficher l'IP de ton serveur
  ```
- Les ports 80 et 443 doivent etre ouverts sur le firewall.
- Le fichier `traefik/acme/acme.json` doit exister avec les permissions **600** (sinon Traefik refuse de demarrer) :
  ```bash
  cd /opt/furious-ducks/infrastructure
  mkdir -p traefik/acme
  touch traefik/acme/acme.json
  chmod 600 traefik/acme/acme.json
  ```
- Le reseau Docker `workflow-network` doit exister :
  ```bash
  docker network create workflow-network
  ```
- Le mot de passe basic auth du dashboard doit etre genere :
  ```bash
  htpasswd -nB admin
  # Saisis un mot de passe quand demande, copie la sortie complete (ex: admin:$2y$05$ABC...)
  # Dans .env, double les $ : admin:$$2y$$05$$ABC...
  # Puis renseigne TRAEFIK_DASHBOARD_AUTH=...
  ```

### 3.2 Premier demarrage

```bash
cd /opt/furious-ducks/infrastructure
docker compose up -d traefik
```

### 3.3 Verifier que Traefik tourne

```bash
docker ps --filter name=common-traefik-ci
# Status doit etre "Up" (healthy apres 30s)

docker logs common-traefik-ci --tail 30
# Tu dois voir : "Configuration loaded from file..." et aucune erreur
```

### 3.4 Acceder au dashboard pour la premiere fois

Ouvre un navigateur sur `https://traefik.wk-archi-o24a-4-5-g1.fr`. Saisis le user/mdp definis avec `htpasswd`.

**Premier acces** : Traefik va demander automatiquement un certificat Let's Encrypt. Cela peut prendre 30 a 60 secondes. Si le navigateur affiche un certificat auto-signe puis que tu rafraichis et que tu vois le vrai cert Let's Encrypt, c'est normal.

**Si le navigateur affiche "ERR_SSL_..." ou un certificat invalide qui ne change pas** :

```bash
docker logs common-traefik-ci | grep -i acme
# Cherche les erreurs Let's Encrypt
```

Causes frequentes :
- DNS pas encore propage : attends 10 minutes et ressaie.
- Port 80 bloque par un autre service : `sudo ss -tlnp | grep :80`.
- Limite Let's Encrypt atteinte (5 echecs / heure / domaine) : attends 1h.

### 3.5 Ajouter un nouveau service derriere Traefik

Quand tu deploies un nouveau service, il suffit d'ajouter ces labels Docker pour qu'il soit automatiquement expose :

```yaml
labels:
  - "traefik.enable=true"
  - "traefik.http.routers.<NOM>.rule=Host(`<sous-domaine>.wk-archi-o24a-4-5-g1.fr`)"
  - "traefik.http.routers.<NOM>.entrypoints=websecure"
  - "traefik.http.routers.<NOM>.tls.certresolver=letsencrypt"
  - "traefik.http.services.<NOM>.loadbalancer.server.port=<PORT_INTERNE>"
  - "traefik.http.routers.<NOM>.middlewares=secure-headers@file"
```

Le service doit etre attache au reseau `workflow-network` ou `ci_network`. Aucune configuration manuelle de Traefik n'est necessaire : il detecte les labels automatiquement.

## 4. Utilisation au quotidien

### Voir les routes actives

Dashboard : `https://traefik.wk-archi-o24a-4-5-g1.fr` > onglet **HTTP > Routers**.

Ou en CLI :
```bash
docker logs common-traefik-ci | grep -i "Adding route"
```

### Voir les requetes en temps reel

`accessLog.filePath` est commente dans `traefik.yml` : les logs d'acces sortent
sur la sortie standard du conteneur.

```bash
docker logs -f common-traefik-ci
```

(Ils sont aussi collectes par Alloy et interrogeables dans Loki :
`{container_name="common-traefik-ci"}`.)

### Recharger la configuration

Les fichiers dans `traefik/dynamic/` sont rechargees automatiquement. Pour `traefik.yml` (statique), il faut redemarrer :

```bash
docker compose restart traefik
```

### Verifier qu'un certificat va expirer

Les certificats Let's Encrypt durent 90 jours. Traefik les renouvelle automatiquement 30 jours avant expiration. Pour verifier :

```bash
echo | openssl s_client -servername git.wk-archi-o24a-4-5-g1.fr \
  -connect git.wk-archi-o24a-4-5-g1.fr:443 2>/dev/null | \
  openssl x509 -noout -dates
```

### Forcer le renouvellement d'un certificat

```bash
# Sauvegarder l'acme.json actuel
cp traefik/acme/acme.json traefik/acme/acme.json.bak
# Vider le cert pour le domaine concerne (le plus simple est de re-creer acme.json)
echo "{}" > traefik/acme/acme.json
chmod 600 traefik/acme/acme.json
docker compose restart traefik
```

### Bloquer une IP

Ajoute un middleware dans `traefik/dynamic/middlewares.yml` :
```yaml
http:
  middlewares:
    block-ip:
      ipAllowList:
        sourceRange:
          - "0.0.0.0/0"
        ipStrategy:
          excludedIPs:
            - "1.2.3.4"
```

## 5. Tests de validation

### Verifier le dashboard

```bash
curl -f -u admin:<mdp> https://traefik.wk-archi-o24a-4-5-g1.fr
```

Resultat attendu : code HTTP 200, page du dashboard Traefik.

### Verifier la redirection HTTP vers HTTPS

```bash
curl -I http://wk-archi-o24a-4-5-g1.fr
```

Resultat attendu : code HTTP 301 ou 308 avec header `Location: https://wk-archi-o24a-4-5-g1.fr/`.

### Verifier les certificats TLS

```bash
openssl s_client -connect wk-archi-o24a-4-5-g1.fr:443 -servername wk-archi-o24a-4-5-g1.fr
```

Resultat attendu : certificat valide emis par Let's Encrypt, chaine de confiance complete.

### Verifier les headers de securite

```bash
curl -I https://git.wk-archi-o24a-4-5-g1.fr
```

Resultat attendu : presence des headers `Strict-Transport-Security`, `X-Frame-Options`, `X-Content-Type-Options`, `Content-Security-Policy`.

### Verifier les metriques

Les metriques sont exposees sur l'entrypoint interne `metrics` (:8082), pas sur
le port du dashboard :

```bash
docker exec common-prometheus-ci wget -qO- http://common-traefik-ci:8082/metrics | head
```

Resultat attendu : metriques au format Prometheus (lignes `traefik_*`).

### Verifier les routes actives

```bash
docker logs common-traefik-ci 2>&1 | grep -i "router"
```

Resultat attendu : logs montrant les routes enregistrees pour chaque service, aucune erreur de configuration.

## 6. Depannage

| Symptome | Cause probable | Solution |
|----------|----------------|----------|
| Container ne demarre pas | acme.json mauvaises permissions | `chmod 600 traefik/acme/acme.json` |
| 404 sur tous les services | Reseau Docker manquant | `docker network create workflow-network` |
| 502 Bad Gateway | Service backend down | `docker ps`, verifier le service cible |
| Cert auto-signe persiste | DNS non propage / port 80 bloque | `dig`, `ss -tlnp \| grep :80` |
| Rate limit Let's Encrypt | Trop d'echecs | Attendre 1 semaine, ou utiliser staging |
