# Jenkins - Serveur d'Integration Continue (CI)

## 1. Presentation

- **Role** : Orchestration des pipelines CI/CD (build, tests, scans, packaging, deploiements)
- Centralise les rapports et historise les executions
- **Ports** :
  - `8080` : Web UI (interne au conteneur, expose via Traefik - aucun port hote)
  - `50000` : JNLP agents - **non publie** sur l'hote ; les agents Docker
    ephemeres se connectent depuis le reseau Docker
- **Sous-domaine** : `ci.wk-archi-o24a-4-5-g1.fr`
- **Pipeline as Code** via `Jenkinsfile` a la racine de chaque projet
- **Shared Libraries** pour standardiser les pipelines entre projets
- **RBAC** :
  - Admin CI : acces complet a l'administration Jenkins
  - Mainteneur : gestion des jobs et des credentials
  - Developpeur : lancement et consultation des pipelines
- **Agents Docker ephemeres** : chaque build s'execute dans un conteneur isole

> Ce document couvre l'installation et l'administration de Jenkins. Pour le
> detail stage par stage du pipeline (`ciPipeline.groovy`), la matrice
> branche x stage et sa justification, voir `../how/02-pipeline-ci-cd.md`. Pour le
> mapping branche -> environnement et les differences DEV/PREPROD/PROD, voir
> `../how/01-environnements-dev-preprod-prod.md`.

## 2. Configuration

### Image et conteneur

- **Image** : image custom construite depuis `infrastructure/jenkins/Dockerfile`
- **Container** : `common-jenkins-ci`
- L'image custom inclut les outils necessaires (Docker CLI, Maven, Node.js, etc.)

### JCasC (Jenkins Configuration as Code)

- Chemin : `infrastructure/jenkins/casc/jenkins.yml`
- Configuration declarative de Jenkins : securite (comptes locaux + RBAC
  `role-strategy`), agents Docker (`docker-agent`, `docker-agent-node`),
  connexions globales (SonarQube, GitLab, Mattermost, email), outils
  (SonarScanner)
- **Ne contient pas de credentials** : le store de credentials reste a remplir
  a la main (voir 3.5), aucun secret n'est committe
- Cree deux comptes locaux : `admin` (`JENKINS_ADMIN_PASSWORD`) et
  `svc-thetiptop-webhook` (`JENKINS_WEBHOOK_THETIPTOP_PASSWORD`), ce dernier
  restreint par role d'item au seul job `thetiptop`

### Plugins

- `git` : integration Git
- `gitlab-plugin` : integration GitLab (webhooks, statut de commit)
- `docker-workflow` : support Docker dans les pipelines
- `sonar` : integration SonarQube Scanner
- `prometheus` : exposition des metriques Jenkins
- `role-strategy` : gestion RBAC fine
- Autres : pipeline, credentials, etc.

### Credentials Store

- `harbor-credentials` (push/pull d'images Docker)
- `sonarqube-token` (declenchement des scans)
- `gitlab-api-token` (acces API, statut de commit)
- `infrastructure-shared-library-deploy-key` (checkout SCM de la shared
  library : deploy key GitLab en **lecture seule** du depot `infrastructure`)
- Par projet client : une deploy key GitLab en lecture seule pour le checkout
  du depot (`thetiptop-deploy-key`, et `thetiptop-k6-deploy-key` pour le job
  k6), credentials BDD (`<projet>-db-credentials`) et secrets applicatifs
  (`apiSecretEnv`)

### Shared Library

- Chemin : `infrastructure/jenkins/shared-library/`
- Trois points d'entree (`vars/`) :
  - `ciPipeline.groovy` : pipeline complet build / tests / scans / deploiement
  - `deployBlueGreen.groovy` : bascule Blue/Green PROD + monitoring 15 min
  - `k6Pipeline.groovy` : job de tests de charge planifie (voir `13-k6.md`)
- Chargee explicitement par chaque Jenkinsfile via
  `@Library('furious-ducks-shared-library') _`

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

**Manage Jenkins > Plugins > Installed plugins**. Tu dois voir tous les plugins de `infrastructure/jenkins/plugins.txt` (git, gitlab-plugin, docker-workflow, sonar, prometheus, role-strategy, etc.).

### 3.5 Configurer les credentials (etape critique)

**Manage Jenkins > Credentials > System > Global credentials > Add Credentials**.

Cree les credentials suivants :

| ID Jenkins | Type | Contenu |
|------------|------|---------|
| `gitlab-api-token` | Secret text | Le token GitLab cree dans `02-gitlab-ce.md` |
| `infrastructure-shared-library-deploy-key` | SSH Username with private key | username `git`, cle privee d'une **deploy key GitLab en lecture seule** du depot `infrastructure` (utilisee par la shared library, voir 3.8) |
| `harbor-credentials` | Username with password | `admin` / `HARBOR_ADMIN_PASSWORD` |
| `sonarqube-token` | Secret text | Le token SonarQube (a creer ensuite, voir `05-sonarqube.md` section 3.4) |

Le webhook Mattermost n'est **pas** un credential Jenkins : il est injecte par
variable d'environnement (`MATTERMOST_WEBHOOK_URL` / `MATTERMOST_CHANNEL` dans
`.env`) et consomme par le bloc `mattermostNotifier` du JCasC.

### 3.6 Verifier la connexion GitLab

La connexion GitLab est deja declaree par le JCasC (`gitLabConnectionConfig`,
nom `GitLab`) ; il reste a verifier qu'elle fonctionne une fois le credential
`gitlab-api-token` cree.

**Manage Jenkins > System** > section **GitLab** :
- Connection name : GitLab
- GitLab host URL : `https://git.wk-archi-o24a-4-5-g1.fr`
- Credentials : `gitlab-api-token`
- Clique **Test Connection** : doit afficher **Success**.

### 3.7 Verifier la connexion SonarQube

(Le serveur `SonarQube` est deja declare par le JCasC. A verifier apres avoir
configure SonarQube et cree le credential `sonarqube-token`.)

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
- Credentials : `infrastructure-shared-library-deploy-key`

Chaque projet client a de la meme facon sa propre deploy key en lecture seule
(`thetiptop-deploy-key` pour le job multibranch `thetiptop`) : une cle compromise
ne donne acces qu'a un seul depot, en lecture.

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
2. Suivre l'avancement dans **Build History > Console Output**

### 4.3 Comprendre l'echec d'un build

1. Cliquer sur le build rouge
2. **Console Output** : log complet
3. Pour les pipelines : **Pipeline Steps** : voir quelle etape a echoue

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
