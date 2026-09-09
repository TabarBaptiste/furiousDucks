# Guide de demarrage - Workflow CI/CD Furious Ducks

Ce document explique pas a pas comment passer d'un serveur vide a un workflow CI/CD complet et operationnel. **Lis-le en premier**, avant les autres documents `XX-outil.md`. Les docs des outils detaillent chaque brique en profondeur ; celui-ci te montre l'enchainement global.

## 0. Vue d'ensemble du workflow

Le workflow est une plateforme **conteneurisee** (Docker) composee de plusieurs services qui collaborent :

```
                                  Internet
                                     |
                                     v
                    +----------------+----------------+
                    |          Traefik (443)          |   <- point d'entree HTTPS
                    +----+----+----+----+----+----+---+
                         |    |    |    |    |    |
                  GitLab  Jenkins  Harbor  Sonar  Grafana  MinIO
                    |       |        |
                    +-------+--------+
                            |
                       Pipeline CI/CD (build, tests, scans, deploiement)
                            |
                  +---------+----------+
                  |         |          |
                 DEV     PREPROD     PROD (Blue/Green)
```

**Acteurs** :
- Tu pousses du code dans **GitLab**.
- GitLab declenche un build dans **Jenkins** via webhook.
- Jenkins construit une image Docker, la scanne (**Trivy**, **Semgrep**, **Gitleaks**), l'analyse (**SonarQube**), la pousse dans **Harbor**, puis la deploie sur DEV / PREPROD / PROD.
- **Prometheus** + **Grafana** + **Loki** monitorent l'ensemble.
- **Restic** + **MinIO** + **Ofelia** sauvegardent automatiquement.

Tout est expose en HTTPS via **Traefik** sur des sous-domaines de `wk-archi-o24a-4-5-g1.fr`.

## 1. Prerequis

### 1.1 Materiel et systeme

Cible recommandee : un (ou plusieurs) VPS Linux. Pour demarrer, **un seul serveur suffit** pour faire tourner tout le workflow et les environnements DEV/PREPROD. La PROD pourra etre isolee plus tard.

| Ressource | Minimum (1 VPS) | Recommande (production) |
|-----------|-----------------|--------------------------|
| OS        | Debian 12 / Ubuntu 22.04+ | idem |
| CPU       | 8 vCPU | 16 vCPU |
| RAM       | 16 Go | 32 Go |
| Disque    | 200 Go SSD | 500 Go SSD NVMe |

### 1.2 Logiciels a installer sur le serveur

Connecte-toi en SSH en tant que `root` ou avec sudo :

```bash
# Mise a jour du systeme
sudo apt update && sudo apt upgrade -y

# Outils de base
sudo apt install -y git curl wget htop apache2-utils

# Docker Engine + Docker Compose v2
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
# IMPORTANT : deconnecte-toi puis reconnecte-toi pour que le groupe docker prenne effet

# Verifier
docker --version          # >= 24
docker compose version    # >= v2.20
```

**Reglages systeme requis pour SonarQube** (a faire avant le premier `docker compose up`) :

```bash
sudo tee -a /etc/sysctl.conf > /dev/null <<EOF
vm.max_map_count=524288
fs.file-max=131072
EOF
sudo sysctl -p
```

### 1.3 DNS (a faire AVANT de demarrer Traefik)

Tu dois posseder le domaine `wk-archi-o24a-4-5-g1.fr` (ou adapter dans tous les fichiers). Chez ton registrar, cree un enregistrement **A** wildcard pointant vers l'IP publique de ton serveur :

```
*.wk-archi-o24a-4-5-g1.fr   A   <IP_DU_SERVEUR>
wk-archi-o24a-4-5-g1.fr     A   <IP_DU_SERVEUR>
```

Sans wildcard, tu dois creer chaque sous-domaine manuellement :

| Sous-domaine | Service |
|--------------|---------|
| `traefik.wk-archi-o24a-4-5-g1.fr` | Dashboard Traefik |
| `git.wk-archi-o24a-4-5-g1.fr` | GitLab |
| `ci.wk-archi-o24a-4-5-g1.fr` | Jenkins |
| `registry.wk-archi-o24a-4-5-g1.fr` | Harbor |
| `sonar.wk-archi-o24a-4-5-g1.fr` | SonarQube |
| `chat.wk-archi-o24a-4-5-g1.fr` | Mattermost |
| `monitor.wk-archi-o24a-4-5-g1.fr` | Grafana |
| `grafana.wk-archi-o24a-4-5-g1.fr` | Grafana (alias du meme service) |
| `prometheus.wk-archi-o24a-4-5-g1.fr` | Prometheus (basic auth) |
| `s3.wk-archi-o24a-4-5-g1.fr` | MinIO API |
| `minio.wk-archi-o24a-4-5-g1.fr` | MinIO Console |

Le domaine `dsp5-archi-o24a-4-5-g1.fr` est distinct : c'est celui des **sites
clients en PROD** (`<projet>.dsp5-...`). Il n'a pas de wildcard TLS, aussi les
API restent sur la zone `wk` (voir `how/01-environnements-dev-preprod-prod.md`).

**Verifier que le DNS est propage** (peut prendre quelques minutes a quelques heures) :

```bash
dig +short git.wk-archi-o24a-4-5-g1.fr
# Doit renvoyer l'IP de ton serveur
```

### 1.4 Ports a ouvrir sur le firewall

```bash
sudo ufw allow 22/tcp      # SSH
sudo ufw allow 80/tcp      # HTTP (redirige vers HTTPS par Traefik)
sudo ufw allow 443/tcp     # HTTPS
sudo ufw allow 2222/tcp    # SSH GitLab (clone/push Git)
sudo ufw enable
```

## 2. Recuperer le code et configurer

### 2.1 Cloner le depot

```bash
sudo mkdir -p /opt/furious-ducks
sudo chown $USER:$USER /opt/furious-ducks
cd /opt/furious-ducks
git clone <URL_DU_DEPOT> .
cd infrastructure
```

### 2.2 Generer le fichier `.env`

```bash
cp .env.example .env
chmod 600 .env
```

**Ouvre `.env` et remplace TOUTES les valeurs `changeme`** par des mots de passe forts. Tu peux en generer avec :

```bash
openssl rand -base64 32
```

Variables critiques a renseigner :
- `GITLAB_ROOT_PASSWORD` : mot de passe initial root GitLab
- `JENKINS_ADMIN_PASSWORD` : mot de passe initial admin Jenkins
- `SONARQUBE_DB_PASSWORD` : mot de passe BDD SonarQube
- `GF_SECURITY_ADMIN_PASSWORD` : mot de passe Grafana
- `HARBOR_ADMIN_PASSWORD` : mot de passe admin Harbor
- `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` : credentials MinIO
- `RESTIC_PASSWORD` : cle de chiffrement des backups (si tu la perds, tu perds les backups - **conserve-la dans un coffre**)
- `ACME_EMAIL` : ton email pour Let's Encrypt

**Generer le hash basic auth pour le dashboard Traefik** :

```bash
htpasswd -nB admin
# Saisis un mot de passe quand demande
# Copie la sortie complete (ex: admin:$2y$05$ABC...)
# Dans .env, double les $ : admin:$$2y$$05$$ABC...
# Mets cette valeur dans TRAEFIK_DASHBOARD_AUTH
```

### 2.3 Initialiser le fichier ACME de Traefik

```bash
mkdir -p traefik/acme
touch traefik/acme/acme.json
chmod 600 traefik/acme/acme.json
```

Sans `chmod 600`, Traefik refusera de demarrer.

### 2.4 Creer le reseau Docker partage

```bash
docker network create workflow-network
```

## 3. Premier demarrage (l'ordre est important)

Tu peux soit lancer le script qui fait tout, soit y aller etape par etape. Je recommande l'etape par etape la premiere fois pour comprendre.

### Option A - Script automatique

```bash
cd /opt/furious-ducks/infrastructure/scripts
chmod +x init-infrastructure.sh
./init-infrastructure.sh
```

### Option B - Etape par etape (recommande la premiere fois)

```bash
cd /opt/furious-ducks/infrastructure

# 1. Stack principal CI/CD (Traefik, GitLab, Jenkins, SonarQube + sa BDD,
#    Mattermost + sa BDD, et les deux postgres-exporter)
docker compose up -d

# Attends 5-10 minutes : GitLab demarre lentement (initialisation BDD interne)
# Suis l'avancement avec :
docker compose logs -f gitlab

# Quand tu vois "GitLab is running", appuie sur Ctrl+C pour quitter les logs

# 2. Stack monitoring
docker compose -f docker-compose.monitoring.yml up -d

# 3. Stack backup
docker compose -f docker-compose.backup.yml up -d
```

### Verifier que tout tourne

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

Tu dois voir tous les conteneurs `common-*-ci` en etat `Up` ou `Up (healthy)`.

### Verifier que les certificats TLS sont generes

Traefik demande automatiquement un certificat Let's Encrypt pour chaque sous-domaine au premier acces. Ouvre un navigateur :

- https://traefik.wk-archi-o24a-4-5-g1.fr (login: admin / le mdp choisi avec htpasswd)

Si le navigateur affiche un cadenas vert, le TLS marche. Sinon, vois la section **Depannage**.

## 4. Configuration initiale de chaque service

L'infrastructure tourne, mais les services ont besoin de quelques reglages avant utilisation.

### 4.1 GitLab - https://git.wk-archi-o24a-4-5-g1.fr

1. Login : `root` / `GITLAB_ROOT_PASSWORD` (du `.env`).
2. **Change le mot de passe** dans Profil > Edit profile > Password.
3. Cree ton compte personnel : Admin > Users > New user.
4. Ajoute ta cle SSH : User Settings > SSH Keys > colle ta cle publique.
5. Test : `ssh -T -p 2222 git@git.wk-archi-o24a-4-5-g1.fr` doit repondre `Welcome to GitLab, @ton_user`.
6. **Cree un Personal Access Token** pour Jenkins : User Settings > Access Tokens > nom `jenkins-token`, scopes `api` + `read_repository` + `write_repository`. **Note le token, il ne sera plus affiche ensuite**.

Plus de details : `tools/02-gitlab-ce.md`.

### 4.2 Jenkins - https://ci.wk-archi-o24a-4-5-g1.fr

1. Login : `admin` / `JENKINS_ADMIN_PASSWORD` (du `.env`).
2. Va dans **Manage Jenkins > Credentials > System > Global credentials > Add Credentials** et cree :
   - `gitlab-api-token` (Secret text) : le token GitLab cree a l'etape precedente
   - `harbor-credentials` (Username/Password) : `admin` / `HARBOR_ADMIN_PASSWORD`
   - `sonarqube-token` (Secret text) : a creer apres l'etape SonarQube ci-dessous
3. Verifie que la connexion Jenkins -> GitLab fonctionne : **Manage Jenkins > System > GitLab > Test Connection**.

Plus de details : `tools/03-jenkins.md`.

### 4.3 SonarQube - https://sonar.wk-archi-o24a-4-5-g1.fr

1. Login : `admin` / `admin` (mot de passe par defaut SonarQube).
2. SonarQube te demande immediatement de **changer le mot de passe**.
3. Le quality gate applique est le gate integre **"Sonar way"** (deja "Set as Default", aucune creation manuelle necessaire) : Coverage on New Code >= 80%, 0 new issue, duplicated lines on new code <= 3%, 100% des security hotspots revus.
4. **Genere un token Jenkins** : My Account > Security > Generate Token, nom `jenkins`. **Note le token**.
5. Retourne dans Jenkins et ajoute le token dans le credential `sonarqube-token` cree precedemment.

Plus de details : `tools/05-sonarqube.md`.

### 4.4 Harbor - https://registry.wk-archi-o24a-4-5-g1.fr

Harbor n'est **pas** dans le `docker-compose.yml` principal (il a son propre installeur). A installer separement :

```bash
cd /opt
wget https://github.com/goharbor/harbor/releases/download/v2.11.0/harbor-online-installer-v2.11.0.tgz
tar xvf harbor-online-installer-v2.11.0.tgz
cd harbor
cp harbor.yml.tmpl harbor.yml
# Edite harbor.yml :
#   - hostname: registry.wk-archi-o24a-4-5-g1.fr
#   - http port: 8080 (Traefik gere le HTTPS)
#   - commente toute la section https
#   - harbor_admin_password: <HARBOR_ADMIN_PASSWORD du .env>
sudo ./install.sh --with-trivy
```

Apres installation, attache le service `proxy` de Harbor aux reseaux
`ci_network` et `workflow-network`, ajoute-lui les labels Traefik et commente
sa section `ports:` — procedure complete dans `tools/04-harbor.md` sections 3.3 et
3.4, et dans l'en-tete de `infrastructure/docker-compose.yml`. Attention :
re-executer `./prepare` efface ces modifications.

1. Login : `admin` / `HARBOR_ADMIN_PASSWORD`.
2. Cree un **projet Harbor** : Projects > + New Project, nom `furious-ducks` (ou le nom de ton projet), Public ou Private selon ton besoin.
3. Test login Docker depuis le serveur :
   ```bash
   echo "$HARBOR_ADMIN_PASSWORD" | docker login registry.wk-archi-o24a-4-5-g1.fr -u admin --password-stdin
   ```
   Tu dois voir `Login Succeeded`.

Plus de details : `tools/04-harbor.md`.

### 4.5 Grafana - https://monitor.wk-archi-o24a-4-5-g1.fr

1. Login : `admin` / `GF_SECURITY_ADMIN_PASSWORD` (du `.env`).
2. Verifie les datasources : **Configuration > Data Sources** > "Prometheus" et "Loki" doivent etre verts (auto-provisionnes).
3. Verifie les dashboards : **Dashboards > Browse > Furious Ducks** > tu dois voir 4 dashboards (Node, Docker, CI/CD Monitoring, DORA Metrics).
4. (Optionnel) Configure un canal de notification : **Alerting > Contact points > Add contact point** (email, Slack, Mattermost).

Plus de details : `tools/07-grafana.md`.

### 4.6 MinIO - https://minio.wk-archi-o24a-4-5-g1.fr

1. Login : `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`.
2. **Cree le bucket `furious-ducks-backups`** : Buckets > Create Bucket > nom `furious-ducks-backups`.
3. **Lance le premier backup manuel pour initialiser le depot Restic** :
   ```bash
   docker exec common-backup-manager-ci /scripts/backup-all.sh
   ```
4. Retourne dans MinIO Console et verifie que le bucket contient des donnees (sous `furious-ducks-backups/`).

Plus de details : `tools/09-restic-minio.md`.

### 4.7 Mattermost - https://chat.wk-archi-o24a-4-5-g1.fr

Mattermost fait partie du `docker-compose.yml` principal (avec sa propre BDD
PostgreSQL) : il demarre avec le reste du socle. Il sert de canal de
notification pour AlertManager, Jenkins et les scripts de backup.

1. Cree le compte admin initial et l'equipe au premier acces sur
   `https://chat.wk-archi-o24a-4-5-g1.fr`.
2. **Integrations > Incoming Webhooks > Add**, choisis le canal
   `devops-alerts`, copie l'URL du webhook.
3. Cette URL est consommee a **deux endroits distincts** :
   - **AlertManager** : dans un fichier secret monte, **pas** dans le `.env` :
     ```bash
     cd /opt/furious-ducks/infrastructure
     printf '%s' '<URL_WEBHOOK>' > alertmanager/secrets/mattermost_webhook
     chmod 600 alertmanager/secrets/mattermost_webhook
     ```
     (Faire de meme pour `alertmanager/secrets/smtp_password`.)
   - **Jenkins et les scripts de backup** : via `MATTERMOST_WEBHOOK_URL` dans
     le `.env`.
4. Redemarre les services concernes :
   ```bash
   docker compose -f docker-compose.monitoring.yml restart alertmanager
   docker compose restart jenkins
   docker compose -f docker-compose.backup.yml restart backup-manager
   ```

## 5. Configurer ton premier projet (Thé Tip Top)

Une fois l'infrastructure prete, voici comment integrer un projet dans le workflow.

### 5.1 Creer le projet dans GitLab

1. https://git.wk-archi-o24a-4-5-g1.fr > **New Project > Create blank project**.
2. Nom : `thetiptop`. Visibility : Internal ou Private.
3. Decoche "Initialize with README" (tu vas pousser ton code existant).
4. Recupere l'URL SSH du depot : `ssh://git@git.wk-archi-o24a-4-5-g1.fr:2222/<ton_user>/thetiptop.git`.

### 5.2 Pousser le code avec le `Jenkinsfile`

Sur ta machine de dev :

```bash
cd /chemin/vers/thetiptop
# Copie le Jenkinsfile template dans ton projet
cp /chemin/vers/infrastructure/jenkins-pipelines/Jenkinsfile.thetiptop ./Jenkinsfile
git add Jenkinsfile
git commit -m "ci: add Jenkinsfile for Furious Ducks CI/CD"

# Initialise GitFlow
git checkout -b develop
git push -u origin develop
git checkout -b main
git push -u origin main

# Protege main et develop dans GitLab :
# Settings > Repository > Protected Branches
#   - main : Maintainers only push, allow merge from Maintainers, require 1 approval
#   - develop : same
```

### 5.3 Configurer le webhook GitLab vers Jenkins

Dans GitLab, sur le projet `thetiptop` :
1. **Settings > Webhooks > Add new webhook**
2. URL : `https://ci.wk-archi-o24a-4-5-g1.fr/project/thetiptop`
3. Triggers : coche **Push events** + **Merge request events** + **Tag push events**
4. Decoche "Enable SSL verification" uniquement si tu utilises un cert auto-signe (sinon laisse coche)
5. Add webhook
6. Ne teste pas pour le moment, le job Jenkins n'existe pas encore. Tu testeras après l'étape 5.4.

### 5.4 Creer le job dans Jenkins

1. Dans Jenkins : **New Item > "thetiptop" > Multibranch Pipeline**.
2. **Branch Sources > Add source > GitLab Project** :
   - Server : GitLab (deja configure)
   - Owner : ton user GitLab
   - Project : thetiptop
   - Credentials : `gitlab-api-token`
3. **Build Configuration > Mode** : by Jenkinsfile, Script Path : `Jenkinsfile`.
4. Save.

Jenkins va scanner les branches et detecter `develop` et `main`.

### 5.5 Lancer le premier build

1. Dans Jenkins, ouvre le job `thetiptop`.
2. Clique sur la branche `develop`.
3. **Build Now**.
4. Suis l'avancement dans **Console Output**.

Le pipeline va :
- Cloner le code
- Installer les dependances
- Lancer linter, tests unitaires
- Analyser avec SonarQube et attendre le quality gate
- Builder l'image Docker
- La scanner avec Trivy
- La pousser dans Harbor
- La deployer en DEV (`thetiptop-dev.wk-archi-o24a-4-5-g1.fr`)

### 5.6 Verifier le deploiement

```bash
curl -I https://thetiptop-dev.wk-archi-o24a-4-5-g1.fr
# HTTP/2 200 attendu
```

## 6. Workflow de developpement quotidien

### 6.1 Developper une fonctionnalite

```bash
# Toujours partir de develop a jour
git checkout develop
git pull

# Creer une branche feature
git checkout -b feature/nom-de-la-feature

# Coder, commiter (Conventional Commits !)
git commit -m "feat(panier): ajouter le bouton supprimer"
git push -u origin feature/nom-de-la-feature
```

Dans GitLab : **Create Merge Request** vers `develop`. Demander une approbation. Une fois mergee, le pipeline DEV se relance et redeploie.

### 6.2 Preparer une release vers PROD

```bash
git checkout main
git pull
git merge --no-ff develop
git push

# Creer un tag semver
git tag -a v1.2.0 -m "Release 1.2.0"
git push origin v1.2.0
```

Le merge dans `main` declenche automatiquement le pipeline PREPROD. Le push d'un tag `vX.Y.Z` declenche directement le pipeline PROD, qui s'arrete sur une **approbation manuelle dans Jenkins** avant le deploiement en Blue/Green.

### 6.3 Faire un rollback en cas de probleme

```bash
ssh root@<serveur>
cd /opt/furious-ducks/infrastructure/scripts
./rollback.sh thetiptop prod v1.1.0
```

Voir `infrastructure/scripts/rollback.sh --help` pour les options.

## 7. Maintenance courante

| Operation | Frequence | Commande |
|-----------|-----------|----------|
| Verifier l'etat des services | Quotidien | `docker ps`, dashboards Grafana |
| Verifier les backups | Quotidien | https://minio.wk-archi-o24a-4-5-g1.fr |
| Test d'integrite Restic | Hebdomadaire (auto) | declenche par Ofelia le dimanche a 03h00 |
| Backup du registre Harbor | Hebdomadaire (auto) | Ofelia, dimanche 01h00 |
| Test de restauration | Trimestriel | `docker exec common-backup-manager-ci /scripts/restore.sh` |
| Mise a jour images | Mensuel | `docker compose pull && docker compose up -d` |
| Renouvellement TLS | Auto (Traefik) | rien a faire |
| Nettoyage Docker | Mensuel | `docker system prune -af --volumes=false` |

## 8. Depannage

### Le navigateur affiche un certificat invalide / TLS

- Verifie que le DNS pointe bien vers le serveur : `dig +short git.wk-archi-o24a-4-5-g1.fr`.
- Verifie que les ports 80 et 443 sont ouverts (Let's Encrypt utilise le port 80 pour le challenge HTTP-01).
- Regarde les logs Traefik : `docker logs common-traefik-ci | grep -i acme`.
- Verifie que `acme.json` a bien les permissions 600 : `ls -la traefik/acme/acme.json`.
- Si tu as fait beaucoup de tentatives infructueuses, Let's Encrypt peut t'avoir rate-limit pour 1 semaine.

### GitLab ne demarre pas / boucle de demarrage

GitLab peut prendre 5 a 10 minutes a demarrer la premiere fois (initialisation BDD).

```bash
docker logs -f common-gitlab-ci
# Attendre "Configuration Configured by GitLab"
```

Si crash :
- Verifie la RAM disponible : `free -h` (GitLab demande 4 Go minimum).
- Verifie l'espace disque : `df -h`.

### Jenkins ne recoit pas les webhooks GitLab

- Test le webhook depuis GitLab : Settings > Webhooks > Test.
- Verifie que le credential `gitlab-api-token` dans Jenkins est correct.
- Verifie les logs : `docker logs common-jenkins-ci | grep -i webhook`.

### SonarQube ne demarre pas

C'est presque toujours un probleme de `vm.max_map_count` :

```bash
sysctl vm.max_map_count
# Doit retourner 524288. Sinon :
sudo sysctl -w vm.max_map_count=524288
```

### Harbor : push refuse (denied / unauthorized)

```bash
docker logout registry.wk-archi-o24a-4-5-g1.fr
docker login registry.wk-archi-o24a-4-5-g1.fr
# Verifie que le projet existe dans Harbor et que le user a les droits push.
```

### Backup Restic en echec

```bash
docker logs common-backup-manager-ci
# Verifier que MinIO est accessible :
docker exec common-backup-manager-ci curl -f http://common-minio-ci:9000/minio/health/live
```

### Pipeline Jenkins en echec sur "Quality Gate"

- Ouvre SonarQube et regarde le rapport du projet.
- Le quality gate "Sonar way" exige Coverage >= 80% sur le nouveau code, 0 new issue, duplication <= 3%, 100% des hotspots revus.
- Rappel du comportement reel : un gate en echec est **bloquant** sur `main`
  (PREPROD) et sur un tag (PROD), mais seulement **`UNSTABLE`** sur `develop`
  (DEV). Voir `tools/05-sonarqube.md` section 2.
- Soit tu corriges le code, soit tu ajustes temporairement le quality gate (deconseille en PROD).

## 9. Pour aller plus loin

- Configuration approfondie de chaque outil : voir les fichiers `XX-outil.md`
- Procedures completes (deploiement, restauration) : voir le **Cahier des Specifications Techniques** chapitre "Procedures"
- Architecture detaillee : voir le **Diagramme d'infrastructure complet du workflow** dans le cahier des specs

## 10. Checklist de demarrage (a cocher)

- [x] Serveur Linux provisionne, Docker installe, sysctl regles
- [x] DNS configure (wildcard ou tous les sous-domaines)
- [x] Ports 22, 80, 443, 2222 ouverts
- [x] Depot clone dans `/opt/furious-ducks`
- [x] `.env` cree, tous les `changeme` remplaces, `RESTIC_PASSWORD` sauvegarde dans un coffre
- [x] `traefik/acme/acme.json` cree avec chmod 600
- [x] Reseau Docker `workflow-network` cree
- [x] `docker compose up -d` reussi pour les 3 stacks
- [x] Tous les conteneurs `common-*-ci` en etat `Up (healthy)`
- [x] Acces HTTPS a tous les sous-domaines du tableau 1.3 avec certificat valide
- [x] GitLab : root login OK, mot de passe change, cle SSH ajoutee
- [x] Jenkins : admin login OK, credentials gitlab/harbor/sonar configures
- [x] SonarQube : mot de passe change, quality gate "Sonar way" verifie (defaut), token Jenkins genere
- [x] Harbor : installe, projet cree, login Docker OK
- [x] Grafana : datasources Prometheus/Loki vertes, dashboards visibles
- [x] MinIO : bucket `furious-ducks-backups` cree
- [x] Premier backup manuel execute avec succes
- [ ] Premier projet (thetiptop) cree dans GitLab, webhook configure, job Jenkins cree
- [ ] Premier pipeline lance avec succes, deploiement DEV verifie

Quand tout est coche, ton workflow est operationnel.
