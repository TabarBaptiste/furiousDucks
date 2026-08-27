# SonarQube - Analyse Qualite du Code

## 1. Presentation

- **Role** : Analyse statique de la qualite du code (dette technique, duplications, complexite, couverture)
- Quality gates automatiques dans le pipeline CI/CD
- **Port** : `9000` (via Traefik)
- **Sous-domaine** : `sonar.wk-archi-o24a-4-5-g1.fr`
- **Quality gate par defaut** :
  - Couverture de code >= 70%
  - 0 vulnerabilite critique
  - Rating A sur fiabilite, securite et maintenabilite
- **Suivi de la dette technique** par projet dans le temps (evolution visible dans le dashboard)

## 2. Configuration

### Image et conteneur

- **Image** : `sonarqube:<version>-community` (edition Community Build ; version exacte epinglee dans `infrastructure/docker-compose.yml`)
- **Container** : `common-sonarqube-ci`

### Base de donnees

- **PostgreSQL dediee** dans le conteneur `common-sonarqube-db-ci`
- Base de donnees isolee pour les donnees SonarQube (resultats de scans, metriques, configuration)

### Integration Jenkins

- **Plugin SonarQube Scanner** installe dans Jenkins ; serveur `SonarQube`
  (`http://common-sonarqube-ci:9000`) declare par le JCasC
- Token d'authentification stocke dans le Credentials Store de Jenkins
  (`sonarqube-token`)
- Les projets clients n'ecrivent pas ces stages eux-memes : la shared library
  (`ciPipeline.groovy`) fournit `SonarQube Analysis` puis `Quality Gate`.

**Comportement reel du Quality Gate** (`waitForQualityGateResult()` dans
`ciPipeline.groovy`) - le gate est appele avec `abortPipeline: false`, mais son
verdict est ensuite exploite selon l'environnement cible :

| `DEPLOY_ENV` | Gate en echec | Effet |
|---|---|---|
| `dev` (branche `develop`) | non bloquant | build marque `UNSTABLE`, le deploiement DEV continue |
| `preprod` (branche `main`) | **bloquant** | `error()`, le pipeline s'arrete avant deploiement |
| `prod` (tag `vX.Y.Z`) | **bloquant** | idem (le tag herite en pratique du gate passe sur `main`) |

Un timeout de 5 minutes sur l'attente du gate passe le build en `UNSTABLE`
sans l'interrompre.

### Quality Gate "Furious Ducks"

- Quality gate personnalise nomme "Furious Ducks"
- Conditions :
  - Couverture sur le nouveau code >= 70%
  - 0 nouvelle vulnerabilite critique ou bloquante
  - 0 nouveau bug critique ou bloquant
  - Rating de fiabilite : A
  - Rating de securite : A
  - Rating de maintenabilite : A
  - Ratio de duplication sur le nouveau code < 3%

### Projets

- Les projets sont crees automatiquement lors du premier scan
- Chaque projet est associe a son depot GitLab
- Les resultats sont historises pour suivre l'evolution de la qualite

## 3. Mise en route

### 3.1 Prerequis

- Le sysctl `vm.max_map_count` doit etre >= 524288 (sinon SonarQube refuse de demarrer) :
  ```bash
  sudo sysctl -w vm.max_map_count=524288
  echo "vm.max_map_count=524288" | sudo tee -a /etc/sysctl.conf
  ```
- Le serveur a au moins 2 Go de RAM libres pour SonarQube + 1 Go pour sa BDD.
- Tu as renseigne `SONARQUBE_DB_PASSWORD` dans `.env`.

### 3.2 Demarrer SonarQube

```bash
cd /opt/furious-ducks/infrastructure
docker compose up -d sonarqube-db sonarqube
```

Demarrage long la premiere fois (~3 minutes) :

```bash
docker logs -f common-sonarqube-ci
# Attendre : "SonarQube is operational"
```

### 3.3 Premier login

1. Ouvre `https://sonar.wk-archi-o24a-4-5-g1.fr`
2. User : `admin`
3. Password : `admin` (mot de passe par defaut Sonar)
4. SonarQube te demande **immediatement de changer le mot de passe**. Choisis-en un fort et note-le.

### 3.4 Generer le token Jenkins

1. **My Account (haut a droite) > Security > Generate Tokens**
2. Name : `jenkins`
3. Type : User Token
4. Expires in : No expiration (ou 1 an a renouveler)
5. **Generate** : copie immediatement le token (jamais re-affiche)
6. Va dans Jenkins > Credentials, et met le token dans le credential `sonarqube-token`

### 3.5 Creer le Quality Gate "Furious Ducks"

1. **Quality Gates > Create**
2. Name : `Furious Ducks`
3. **Save**
4. Avec le quality gate selectionne, clique **Add Condition** plusieurs fois pour ajouter :

| Metric | Operator | Threshold | On |
|--------|----------|-----------|-----|
| Coverage | is less than | 70% | New Code |
| Duplicated Lines (%) | is greater than | 3% | New Code |
| Maintainability Rating | is worse than | A | New Code |
| Reliability Rating | is worse than | A | New Code |
| Security Rating | is worse than | A | New Code |
| Security Hotspots Reviewed | is less than | 100% | New Code |
| Bugs | is greater than | 0 | New Code (severite Critical/Blocker) |
| Vulnerabilities | is greater than | 0 | New Code (severite Critical/Blocker) |

5. Une fois les conditions ajoutees : **Set as Default** > clique le bouton.

### 3.6 Configurer la connexion Jenkins -> SonarQube

Dans Jenkins (`03-jenkins.md` section 3.7), aller dans **Manage Jenkins > System > SonarQube servers** :
- Name : `SonarQube`
- Server URL : `http://common-sonarqube-ci:9000` (utilise le DNS Docker, pas le sous-domaine public)
- Server authentication token : `sonarqube-token`
- Save

### 3.7 Tester l'analyse depuis Jenkins

Dans n'importe quel pipeline, ajoute :

```groovy
stage('SonarQube Analysis') {
    steps {
        withSonarQubeEnv('SonarQube') {
            sh '''
                sonar-scanner \
                    -Dsonar.projectKey=test-sonar \
                    -Dsonar.sources=.
            '''
        }
    }
}
stage('Quality Gate') {
    steps {
        timeout(time: 5, unit: 'MINUTES') {
            waitForQualityGate abortPipeline: true
        }
    }
}
```

Lance le pipeline. Va voir le projet `test-sonar` dans SonarQube.

## 4. Utilisation au quotidien

### 4.1 Configurer un projet pour SonarQube

A la racine de ton projet, cree un fichier `sonar-project.properties` :

```properties
sonar.projectKey=thetiptop
sonar.projectName=The Tip Top
sonar.projectVersion=1.0

sonar.sources=src
sonar.tests=tests
sonar.exclusions=**/node_modules/**,**/vendor/**,**/dist/**

# Pour Node.js
sonar.javascript.lcov.reportPaths=coverage/lcov.info

# Pour PHP
sonar.php.coverage.reportPaths=coverage.xml
sonar.php.tests.reportPath=phpunit.xml

# Pour Python
sonar.python.coverage.reportPaths=coverage.xml
```

### 4.2 Consulter les resultats d'un projet

1. **Projects > <ton projet>**
2. **Overview** : vue d'ensemble (quality gate, bugs, vulnerabilities, code smells, coverage, duplications)
3. **Issues** : liste detaillee des problemes avec severite et localisation
4. **Security Hotspots** : points de vigilance securite (a reviewer manuellement)
5. **Measures** : metriques completes (complexite, taille, etc.)
6. **Activity** : evolution dans le temps (historique des analyses)

### 4.3 Reviewer un Security Hotspot

Les hotspots ne sont pas des bugs averes mais des points qui meritent une revue humaine.

1. Pour chaque hotspot : **Review** > status :
   - **Safe** : code OK, pas de risque
   - **Acknowledged** : risque accepte
   - **Fixed** : a corriger

### 4.4 Marquer une issue en "False Positive"

Si un bug detecte n'en est pas un :
1. Ouvre l'issue
2. Status > **Resolve as False Positive** ou **Won't Fix**
3. Justifie dans le commentaire

### 4.5 Modifier le Quality Gate

Si "Furious Ducks" est trop strict ou trop laxiste :

1. **Quality Gates > Furious Ducks**
2. Ajouter / supprimer / modifier des conditions
3. **Save**
4. La nouvelle config s'applique aux **prochains scans**, pas aux anciens.

### 4.6 Voir l'evolution de la dette technique

1. **Projects > <projet> > Activity**
2. Selectionne la metrique "Technical Debt" dans le selecteur en haut
3. Le graphe montre l'evolution dans le temps

### 4.7 Mettre a jour SonarQube

SonarQube Community Build ne supporte pas forcement un saut direct entre deux versions : une mise a jour qui traverse une annee civile doit passer par la derniere release de decembre (ou son remplacement si celle-ci a un probleme connu) avant d'atteindre la cible. Verifier le chemin de mise a jour requis dans la documentation officielle Sonar avant de planifier les etapes.

```bash
# Editer infrastructure/docker-compose.yml et changer le tag de l'image sonarqube
docker compose pull sonarqube
docker compose up -d sonarqube

# La migration de BDD n'est PAS automatique : declencher manuellement une fois
# que /api/system/status renvoie DB_MIGRATION_NEEDED
docker exec common-sonarqube-ci curl -fsS -X POST http://localhost:9000/api/system/migrate_db

# Puis attendre que le statut repasse a UP
watch -n5 'docker exec common-sonarqube-ci curl -fsS http://localhost:9000/api/system/status'
```

Repeter ces etapes pour chaque palier intermediaire requis.

Verifier la compatibilite des plugins apres mise a jour : **Administration > Marketplace > Updates Only**.

**Marge memoire** : le premier demarrage post-migration re-enregistre toutes les regles/profils qualite en memoire et peut etre plus gourmand qu'un demarrage normal. Si `web.log` montre un `OutOfMemoryError: Java heap space` pendant `RulesRegistrant`, augmenter `SONAR_WEB_JAVAOPTS` (et au besoin `mem_limit` du service) dans `infrastructure/docker-compose.yml`.

## 5. Tests de validation

### Acces web

```bash
curl -f https://sonar.wk-archi-o24a-4-5-g1.fr/api/system/status
```

Resultat attendu : code HTTP 200, reponse JSON avec `"status": "UP"`.

### Verifier le quality gate

1. Naviguer vers **Quality Gates**
2. Verifier que le quality gate "Furious Ducks" existe
3. Verifier que les conditions sont conformes (couverture >= 70%, 0 vulnerabilite critique, rating A)

### Lancer un scan de test

```bash
docker run --rm \
  -v "$(pwd):/usr/src" \
  -e SONAR_HOST_URL=https://sonar.wk-archi-o24a-4-5-g1.fr \
  -e SONAR_TOKEN=<ton-token> \
  sonarsource/sonar-scanner-cli \
  -Dsonar.projectKey=test
```

Resultat attendu : scan execute avec succes, resultats visibles dans le dashboard SonarQube.

### Verifier l'integration Jenkins

1. Lancer un pipeline Jenkins contenant un stage SonarQube
2. Verifier que le scan s'execute correctement dans les logs Jenkins
3. Verifier que le quality gate est evalue et que le pipeline reagit en consequence (succes ou echec)

### Verifier les metriques d'un projet

1. Naviguer vers le projet dans le dashboard SonarQube
2. Verifier la presence des metriques : couverture, duplications, dette technique, bugs, vulnerabilites, code smells
3. Verifier l'historique des scans et l'evolution des metriques dans le temps

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| SonarQube ne demarre pas | `vm.max_map_count` trop bas | `sudo sysctl -w vm.max_map_count=524288` |
| `Failed to connect to database` | BDD pas demarree | `docker compose up -d sonarqube-db` puis attendre 30s |
| Pipeline bloque sur `waitForQualityGate` | Pas de webhook configure | Sonar > Administration > Webhooks > add `http://common-jenkins-ci:8080/sonarqube-webhook/` |
| Quality gate FAIL alors que code OK | Mauvais paths de coverage | Verifier `sonar.*.coverage.reportPaths` |
| Token invalide | Expire ou regenere | Regenerer dans My Account > Security |
| Memoire saturee | JVM trop gourmande | Ajouter `SONAR_WEB_JAVAOPTS=-Xmx1g` dans docker-compose |
