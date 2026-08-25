# Trivy - Scan de Vulnerabilites des Conteneurs

## 1. Presentation

- **Role** : Scanner de vulnerabilites pour images Docker, dependances et configurations
- **Integration** : Dans le pipeline Jenkins (stage dedie)
- **Pas de port expose** (CLI uniquement, execute dans les agents Jenkins)
- **Base de donnees** de vulnerabilites mise a jour quotidiennement
- **Bloque le pipeline** si vulnerabilites CRITICAL detectees
- **Scan egalement integre dans Harbor** pour chaque push d'image

### Architecture

```
Pipeline Jenkins
+--------------------------------------------------+
| Build -> Tests -> Trivy Scan -> Deploy           |
|                       |                          |
|                  +----+----+                     |
|                  | CRITICAL|--> Pipeline BLOQUE  |
|                  | HIGH    |--> Avertissement    |
|                  | MEDIUM  |--> Info             |
|                  +---------+                     |
+--------------------------------------------------+

Harbor Registry
+--------------------------------------------------+
| Push image -> Trivy scan automatique             |
|                       |                          |
|              Rapport de vulnerabilites           |
+--------------------------------------------------+
```

Trivy detecte les CVE dans les packages systeme et dans les dependances applicatives. Il peut aussi detecter des erreurs de configuration et des secrets exposes dans les images.

## 2. Configuration

### Execution dans le pipeline

Trivy est execute via `docker run` dans le pipeline Jenkins, sans necessiter de conteneur permanent :

```groovy
stage('Security Scan') {
    steps {
        sh '''
            docker run --rm \
                -v /var/run/docker.sock:/var/run/docker.sock \
                -v trivy-cache:/root/.cache/ \
                aquasec/trivy image \
                --severity CRITICAL,HIGH \
                --exit-code 1 \
                --format json \
                --output /tmp/trivy-report.json \
                ${IMAGE_NAME}:${IMAGE_TAG}
        '''
    }
    post {
        always {
            archiveArtifacts artifacts: 'trivy-report.json', allowEmptyArchive: true
        }
    }
}
```

### Commandes type

| Commande | Description |
|----------|-------------|
| `trivy image IMAGE` | Scan basique d'une image |
| `trivy image --severity CRITICAL,HIGH --exit-code 1 IMAGE` | Scan avec seuil bloquant |
| `trivy image --format json -o report.json IMAGE` | Rapport JSON |
| `trivy image --format table IMAGE` | Rapport console lisible |
| `trivy fs --security-checks vuln,config .` | Scan du filesystem (dependances + config) |

### Format de rapport

| Format  | Usage                                  |
|---------|----------------------------------------|
| `table` | Sortie console lisible dans les logs Jenkins |
| `json`  | Archivage dans les artefacts Jenkins   |
| `sarif` | Integration avec des outils tiers      |

### Seuils de severite

| Severite     | Action                          |
|-------------|----------------------------------|
| **CRITICAL** | Bloquant - pipeline en echec    |
| **HIGH**     | Avertissement - log visible     |
| **MEDIUM**   | Information - log uniquement    |
| **LOW**      | Ignore                          |

### Cache partage

Un volume Docker `trivy-cache` est partage entre les executions pour eviter de retelecharger la base de vulnerabilites a chaque scan. La base est mise a jour automatiquement si elle date de plus de 12 heures.

### Integration Harbor

Harbor dispose d'un scanner Trivy integre. Chaque image poussee dans le registre est automatiquement scannee. Les resultats sont visibles dans l'interface Harbor sur la page de chaque image.

## 3. Mise en route

Trivy ne necessite **aucune installation manuelle** : il est execute via `docker run` dans les pipelines Jenkins. Si tu utilises Harbor avec `--with-trivy` lors de l'install (voir `04-harbor.md` section 3.2), Harbor a son propre Trivy integre.

### 3.1 Prerequis

- Docker est disponible sur les agents Jenkins (deja le cas avec notre Dockerfile Jenkins).
- Le socket Docker est monte dans Jenkins (`/var/run/docker.sock`).
- Connexion Internet pour telecharger la BDD de vulnerabilites (~500 MB).

### 3.2 Pre-pull de l'image Trivy

Pour eviter le pull au premier build :

```bash
docker pull aquasec/trivy:latest
```

### 3.3 Telecharger la BDD de vulnerabilites une premiere fois

```bash
docker run --rm \
  -v trivy-cache:/root/.cache/ \
  aquasec/trivy image --download-db-only
# ~30 secondes a 2 minutes selon la connexion
```

### 3.4 Premier scan de test

Sur n'importe quelle image deja sur le serveur :

```bash
docker pull alpine:3.18
docker run --rm \
  -v trivy-cache:/root/.cache/ \
  aquasec/trivy image alpine:3.18
```

Tu vas voir un tableau listant les CVE trouvees (peu en general sur Alpine).

### 3.5 Verifier le seuil bloquant

```bash
docker run --rm \
  -v trivy-cache:/root/.cache/ \
  aquasec/trivy image \
    --severity CRITICAL \
    --exit-code 1 \
    alpine:3.18
echo "Exit code: $?"
# 0 si pas de CRITICAL, 1 si CRITICAL trouvee
```

### 3.6 Activer le scan automatique dans Harbor

(Si Harbor a ete installe avec `--with-trivy`.)

1. Console Harbor > **Configuration > Vulnerability**
2. Coche **Scan all images automatically when pushed**
3. Schedule : Daily (re-scan des images existantes)
4. **Save**

A partir de maintenant, chaque image poussee sera scannee.

### 3.7 Verifier l'integration dans le Jenkinsfile

Notre `infrastructure/jenkins/shared-library/vars/ciPipeline.groovy` integre deja Trivy au stage **"Trivy Scan"**. Verifier qu'il est present :

```bash
grep -A 10 "Trivy Scan" /opt/furious-ducks/infrastructure/jenkins/shared-library/vars/ciPipeline.groovy
```

## 4. Utilisation au quotidien

### 4.1 Scanner manuellement une image avant production

```bash
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v trivy-cache:/root/.cache/ \
  aquasec/trivy image \
    --severity CRITICAL,HIGH \
    --format table \
    registry.wk-archi-o24a-4-5-g1.fr/furious-ducks/thetiptop:v1.2.3
```

### 4.2 Scanner les dependances d'un projet (filesystem)

Tres utile en local avant de commit :

```bash
cd /chemin/vers/projet
docker run --rm \
  -v $(pwd):/project \
  -v trivy-cache:/root/.cache/ \
  aquasec/trivy fs \
    --security-checks vuln,config,secret \
    /project
```

### 4.3 Generer un rapport SBOM (Software Bill of Materials)

```bash
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy image \
    --format cyclonedx \
    --output sbom.json \
    nginx:latest
```

### 4.4 Voir les vulnerabilites d'une image dans Harbor

1. Console Harbor > Project > Repository > tag
2. Onglet **Vulnerabilities**
3. Filtrer par severite, voir le package, la version, la version corrective

### 4.5 Ignorer une CVE (faux positif)

Cree un fichier `.trivyignore` a la racine du projet :

```
# .trivyignore
CVE-2023-12345
CVE-2023-67890   # Faux positif : ne s'applique pas a notre usage
```

Trivy ignorera ces CVE.

### 4.6 Mettre a jour la BDD de vulnerabilites manuellement

```bash
docker run --rm \
  -v trivy-cache:/root/.cache/ \
  aquasec/trivy image --download-db-only
```

(Auto-update toutes les 12h par defaut.)

### 4.7 Comparer deux images

```bash
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v trivy-cache:/root/.cache/ \
  aquasec/trivy image \
    --format json \
    -o /tmp/v1.json \
    image:v1.0

# Idem pour v2, puis diff manuel ou outils comme jq
```

## 5. Tests de validation

### Scan d'une image de test

```bash
docker run --rm aquasec/trivy image alpine:latest
docker run --rm aquasec/trivy image --format table alpine:latest
```

### Scan avec seuil bloquant

```bash
docker run --rm aquasec/trivy image \
  --severity CRITICAL \
  --exit-code 1 \
  alpine:latest
```

### Tester avec une image vulnerable

```bash
docker run --rm aquasec/trivy image \
  --severity CRITICAL,HIGH \
  nginx:1.19
echo "Exit code: $?"
```

### Verification dans le pipeline Jenkins

1. Ouvrir Jenkins
2. Selectionner un pipeline recent
3. Verifier que le stage **"Trivy Scan"** apparait
4. Cliquer sur le stage pour voir les logs du scan
5. Verifier que le rapport de vulnerabilites est affiche

### Verification des artefacts

1. Dans le build Jenkins, aller dans **"Artefacts"**
2. Verifier la presence du fichier `trivy-report.json`
3. Telecharger et inspecter le contenu du rapport

### Scan du filesystem (dependances)

```bash
docker run --rm \
    -v $(pwd):/project \
    aquasec/trivy fs \
    --security-checks vuln \
    /project
```

### Verification de la base de vulnerabilites

```bash
docker run --rm \
    -v trivy-cache:/root/.cache/ \
    aquasec/trivy image \
    --download-db-only
```

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| Scan tres lent | BDD pas en cache | Premier scan = telechargement BDD (~30s) |
| `Failed to download vulnerability DB` | Pas de connexion Internet | Verifier acces github.com |
| Faux positif persistant | CVE non applicable | `.trivyignore` |
| Pipeline echoue sur image officielle | Image obsolete | Mettre a jour l'image de base |
| Trivy in Harbor ne scanne pas | Service trivy-adapter down | `docker logs harbor-trivy-adapter` |
