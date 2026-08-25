# Jenkins - Serveur d'Integration Continue (CI)

## 1. Presentation

- **Role** : Orchestration des pipelines CI/CD (build, tests, scans, packaging, deploiements)
- Centralise les rapports et historise les executions
- **Ports** :
  - `8080` : Web UI (via Traefik)
  - `50000` : JNLP agents (communication agents de build)
- **Sous-domaine** : `ci.wk-archi-o24a-4-5-g1.fr`
- **Pipeline as Code** via `Jenkinsfile` a la racine de chaque projet
- **Shared Libraries** pour standardiser les pipelines entre projets
- **RBAC** :
  - Admin CI : acces complet a l'administration Jenkins
  - Mainteneur : gestion des jobs et des credentials
  - Developpeur : lancement et consultation des pipelines
- **Agents Docker ephemeres** : chaque build s'execute dans un conteneur isole

## 2. Configuration

### Image et conteneur

- **Image** : image custom construite depuis `infrastructure/jenkins/Dockerfile`
- **Container** : `common-jenkins-ci`
- L'image custom inclut les outils necessaires (Docker CLI, Maven, Node.js, etc.)

### JCasC (Jenkins Configuration as Code)

- Chemin : `infrastructure/jenkins/casc/jenkins.yml`
- Configuration declarative de Jenkins : securite, credentials, agents, outils, vues

### Plugins

- `git` : integration Git
- `gitlab-plugin` : integration GitLab (webhooks, statut de commit)
- `docker-workflow` : support Docker dans les pipelines
- `sonar` : integration SonarQube Scanner
- `prometheus` : exposition des metriques Jenkins
- `role-strategy` : gestion RBAC fine
- Autres : pipeline, credentials, blueocean, etc.

### Credentials Store

- Token Harbor (push/pull d'images Docker)
- Token SonarQube (declenchement des scans)
- Token GitLab (acces API, statut de commit)
- Credentials S3/MinIO (stockage des artefacts et backups)

### Shared Library

- Chemin : `infrastructure/jenkins/shared-library/`
- Fonctions partagees : `buildDockerImage()`, `pushToHarbor()`, `runSonarScan()`, `deployBlueGreen()`, `notifyGitLab()`
- Chargement automatique dans tous les pipelines

### Integrations

- **SonarQube** : scan de qualite a chaque build, verification du quality gate
- **Harbor** : build et push des images Docker taguees
- **GitLab** : reception des webhooks, mise a jour du statut de commit/MR

## 3. Mise en route

Jenkins doit etre configure **apres** GitLab et **avant** SonarQube/Harbor (car il a besoin des credentials de chacun pour fonctionner).

### 3.1 Prerequis

- Traefik et GitLab tournent.
- Tu as cree un Personal Access Token GitLab (voir `02-gitlab-ce.md` section 3.6).
- Tu as renseigne `JENKINS_ADMIN_PASSWORD` dans `.env`.
- Le socket Docker `/var/run/docker.sock` est accessible (necessaire pour les builds Docker-in-Docker).

### 3.2 Construire et demarrer Jenkins

L'image est custom (Dockerfile + plugins.txt + JCasC). Build et demarrage :

```bash
cd /opt/furious-ducks/infrastructure
docker compose build jenkins
docker compose up -d jenkins
```

Suivre l'initialisation (~2 minutes la premiere fois pour installer les plugins) :

```bash
docker logs -f common-jenkins-ci
# Attendre : "Jenkins is fully up and running"
```

### 3.3 Premier login

1. Ouvre `https://ci.wk-archi-o24a-4-5-g1.fr`
2. User : `admin`
3. Password : la valeur de `JENKINS_ADMIN_PASSWORD` dans `.env`

Grace au JCasC, **le wizard d'installation est skippe** et l'admin est deja cree.

### 3.4 Verifier les plugins

**Manage Jenkins > Plugins > Installed plugins**. Tu dois voir tous les plugins de `infrastructure/jenkins/plugins.txt` (git, gitlab-plugin, docker-workflow, sonar, prometheus, role-strategy, blueocean, etc.).

### 3.5 Configurer les credentials (etape critique)

**Manage Jenkins > Credentials > System > Global credentials > Add Credentials**.

Cree les credentials suivants :

| ID Jenkins | Type | Contenu |
|------------|------|---------|
| `gitlab-api-token` | Secret text | Le token GitLab cree dans `02-gitlab-ce.md` |
| `gitlab-ssh-key` | SSH Username with private key | username `git`, ta cle SSH privee |
| `harbor-credentials` | Username with password | `admin` / `HARBOR_ADMIN_PASSWORD` |
| `sonarqube-token` | Secret text | Le token SonarQube (a creer ensuite, voir `05-sonarqube.md` section 3.4) |
| `mattermost-webhook` | Secret text | URL du webhook Mattermost (optionnel) |

### 3.6 Verifier la connexion GitLab

**Manage Jenkins > System** > section **GitLab** :
- Connection name : GitLab
- GitLab host URL : `https://git.wk-archi-o24a-4-5-g1.fr`
- Credentials : `gitlab-api-token`
- Clique **Test Connection** : doit afficher **Success**.

### 3.7 Verifier la connexion SonarQube

(A faire apres avoir configure SonarQube et cree le credential `sonarqube-token`.)

**Manage Jenkins > System** > section **SonarQube servers** :
- Name : SonarQube
- Server URL : `http://common-sonarqube-ci:9000` (ou public via Traefik)
- Credentials : `sonarqube-token`

### 3.8 Configurer les Shared Libraries

**Manage Jenkins > System** > section **Global Pipeline Libraries** > Add :
- Name : `furious-ducks-shared-library`
- Default version : `main`
- Retrieval method : Modern SCM > Git
- Project Repository : URL d'un depot GitLab contenant le contenu de `infrastructure/jenkins/shared-library/`
- Credentials : `gitlab-ssh-key`

(Tu peux aussi pousser le contenu du dossier shared-library dans un nouveau depot GitLab dedie.)

### 3.9 Tester avec un pipeline minimal

1. **New Item > "test-pipeline" > Pipeline > OK**
2. Dans Pipeline > Definition : "Pipeline script"
3. Colle :
   ```groovy
   pipeline {
       agent any
       stages {
           stage('Hello') {
               steps {
                   echo 'Jenkins fonctionne !'
                   sh 'docker --version'
               }
           }
       }
   }
   ```
4. Save > **Build Now**.
5. **Console Output** : doit afficher "Jenkins fonctionne !" et la version Docker.

## 4. Utilisation au quotidien

### 4.1 Creer un job pour un projet GitLab (Multibranch)

Recommande pour les projets reels.

1. **New Item > "<nom-projet>" > Multibranch Pipeline**
2. **Branch Sources > Add source > GitLab Project** :
   - Server : GitLab
   - Owner : groupe ou user GitLab
   - Project : nom du projet
   - Credentials : `gitlab-api-token`
3. **Build Configuration > Mode** : by Jenkinsfile, Script Path : `Jenkinsfile`
4. **Scan Multibranch Pipeline Triggers** : coche "Periodically if not otherwise run", 1 hour
5. Save > Jenkins scanne le depot et detecte les branches contenant un `Jenkinsfile`

### 4.2 Lancer un build manuellement

1. Ouvrir le job > selectionner la branche > **Build Now**
2. Suivre l'avancement dans **Build History > Console Output** ou **Open Blue Ocean** pour la vue moderne

### 4.3 Comprendre l'echec d'un build

1. Cliquer sur le build rouge
2. **Console Output** : log complet
3. Pour les pipelines : **Pipeline Steps** : voir quelle etape a echoue
4. **Blue Ocean** : vue visuelle des stages

### 4.4 Approuver un deploiement PROD

Quand le pipeline atteint le stage "Approval PROD" (sur un tag semver) :

1. Le build se met en pause avec un bouton **Proceed**
2. Verifie que tout est OK (tests, recette PREPROD, etc.)
3. Clique **Proceed** pour autoriser le deploiement Blue/Green
4. Ou **Abort** pour annuler

### 4.5 Voir les rapports de tests et coverage

Sur la page d'un build :
- **Test Result** : detail des tests JUnit
- **Coverage Report** : couverture de code (Cobertura)
- **Artifacts** : telecharger les rapports JSON (Trivy, Semgrep, Gitleaks, K6)

### 4.6 Gerer les utilisateurs et roles

**Manage Jenkins > Manage and Assign Roles** :
- **Manage Roles** : verifier que les roles `admin`, `maintainer`, `developer` existent (definis dans JCasC)
- **Assign Roles** : ajouter les utilisateurs au groupe correspondant

### 4.7 Modifier la configuration

**Edite directement** `infrastructure/jenkins/casc/jenkins.yml`, puis :

```bash
docker compose restart jenkins
```

Le JCasC sera rechargee au demarrage.

### 4.8 Mettre a jour Jenkins

```bash
# Editer infrastructure/jenkins/Dockerfile (ligne FROM)
docker compose build jenkins
docker compose up -d jenkins
```

Verifier la compatibilite des plugins via **Manage Jenkins > Plugins > Updates** apres le redemarrage.

## 5. Tests de validation

### Acces web

```bash
curl -f https://ci.wk-archi-o24a-4-5-g1.fr/login
```

Resultat attendu : code HTTP 200, page de login Jenkins.

### Verifier les plugins

1. Naviguer vers **Manage Jenkins > Plugins**
2. Verifier que tous les plugins requis sont installes et actifs
3. Verifier qu'aucune mise a jour de securite n'est en attente

### Creer un job de test pipeline

1. Creer un nouveau job de type **Pipeline**
2. Configurer un `Jenkinsfile` minimal (checkout, build, test)
3. Lancer le build et verifier que toutes les etapes s'executent

### Verifier le declenchement par webhook GitLab

1. Effectuer un push sur un depot GitLab configure avec un webhook
2. Verifier que Jenkins declenche automatiquement le pipeline correspondant
3. Verifier que le statut du commit est mis a jour dans GitLab

### Verifier le RBAC

1. Se connecter avec un compte **Developpeur**
2. Verifier que l'acces a l'administration est refuse
3. Verifier que le lancement et la consultation des pipelines sont autorises

### Verifier les metriques

```bash
curl -f https://ci.wk-archi-o24a-4-5-g1.fr/prometheus
```

Resultat attendu : metriques au format Prometheus (lignes `jenkins_*`, `default_jenkins_*`).

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| Build echoue sur `docker: command not found` | Dockerfile mal build | Verifier que docker.io est installe dans Dockerfile |
| Build echoue sur `permission denied: /var/run/docker.sock` | User jenkins pas dans groupe docker | Verifier dans Dockerfile |
| Webhook GitLab arrive mais ne declenche rien | Job pas en Multibranch | Recreer en Multibranch |
| `Sonar Server unreachable` | Mauvais host dans config Sonar | Utiliser `http://common-sonarqube-ci:9000` (interne) |
| Pipeline bloque sur "Quality Gate" | SonarQube ne repond pas | Verifier SonarQube up, token valide |
| `unauthorized: authentication required` sur push Harbor | Mauvais credentials | Verifier `harbor-credentials` |
