# Semgrep - Analyse Statique de Securite (SAST)

## 1. Presentation

- **Role** : Analyse statique de securite du code source (SAST), detection de patterns vulnerables
- **Multi-langages** : PHP, JavaScript/TypeScript, Python, Ruby, Java, Go...
- **Integration** : Dans le pipeline Jenkins (stage dedie en PREPROD sur branche `main`)
- **Pas de port expose** (CLI uniquement, execute dans les agents Jenkins)
- **Non bloquant** (marque le pipeline comme `unstable`, mais laisse passer le deploiement PREPROD)
- **Regles personnalisables** + regles communautaires
- **Complement de SonarQube** pour l'aspect securite specifique

### Architecture

```
Pipeline Jenkins (branche main)
+----------------------------------------------------+
|                                                    |
|  Build -> Tests -> Trivy -> Semgrep SAST -> Deploy |
|                                |                   |
|                         +------+------+            |
|                         | Code source |            |
|                         | analyse SAST|            |
|                         +------+------+            |
|                                |                   |
|                        Rapport JSON archive        |
+----------------------------------------------------+
```

Semgrep detecte des patterns de code vulnerables avec des regles declaratives : injections SQL, XSS, deserialisation non securisee, utilisation de fonctions dangereuses, etc. Contrairement a SonarQube qui couvre la qualite generale, Semgrep est specialise securite.

### Couverture de langages

| Langage               | Support    |
|-----------------------|------------|
| PHP                   | Complet    |
| JavaScript/TypeScript | Complet    |
| Python                | Complet    |
| Java                  | Complet    |
| Go                    | Complet    |
| Ruby                  | Complet    |
| C/C++                 | Beta       |

## 2. Configuration

### Execution dans le pipeline

Semgrep est execute via `docker run` dans le Jenkinsfile, uniquement sur la branche `main` (avant promotion en PREPROD). Voir `infrastructure/jenkins/shared-library/vars/ciPipeline.groovy`.

### Commandes type

| Commande | Description |
|----------|-------------|
| `semgrep scan --config auto .` | Scan avec regles recommandees |
| `semgrep scan --config p/owasp-top-ten .` | Regles OWASP Top 10 |
| `semgrep scan --config auto --error .` | Scan bloquant si trouvaille |
| `semgrep scan --config auto --json -o report.json .` | Rapport JSON |

### Rulesets disponibles

| Ruleset              | Description                              |
|----------------------|------------------------------------------|
| `auto`               | Regles recommandees automatiques         |
| `p/owasp-top-ten`    | Vulnerabilites OWASP Top 10              |
| `p/security-audit`   | Audit de securite complet                |
| `p/ci`               | Regles optimisees pour CI/CD             |
| `p/php`, `p/javascript`, `p/python` | Regles specifiques par langage |

### Format de rapport

- `json` : archivage dans artefacts Jenkins (utilise par defaut dans notre pipeline)
- `sarif` : integration avec outils tiers
- `text` : sortie console lisible

## 3. Mise en route

Semgrep ne necessite **aucune installation manuelle** : il est execute via `docker run` dans les pipelines Jenkins.

### 3.1 Prerequis

- Docker disponible sur les agents Jenkins (deja le cas).
- Le socket Docker monte dans Jenkins.
- Connexion Internet pour telecharger les regles communautaires.

### 3.2 Pre-pull de l'image Semgrep

Pour eviter le pull au premier build :

```bash
docker pull semgrep/semgrep:latest
```

### 3.3 Premier scan de test en local

Sur un projet clone localement :

```bash
cd /chemin/vers/projet
docker run --rm -v $(pwd):/src semgrep/semgrep \
  semgrep scan --config auto /src
```

Tu obtiendras un rapport avec les trouvailles (le cas echeant) et un resume final.

### 3.4 Tester la detection avec un faux vulnerable

Cree un fichier test avec une injection SQL :

```bash
mkdir -p /tmp/semgrep-test
cat > /tmp/semgrep-test/vuln.php <<'EOF'
<?php
$user = $_GET['user'];
$query = "SELECT * FROM users WHERE name = '$user'";
mysqli_query($conn, $query);
EOF

docker run --rm -v /tmp/semgrep-test:/src semgrep/semgrep \
  semgrep scan --config auto /src
```

Semgrep doit remonter la SQL injection. Nettoyer :

```bash
rm -rf /tmp/semgrep-test
```

### 3.5 Creer un fichier .semgrep.yml personnalise (optionnel)

Place a la racine du projet pour des regles custom ou des exclusions :

```yaml
# .semgrep.yml
rules:
  - id: custom-sql-injection
    patterns:
      - pattern: $DB->query($INPUT)
    message: "Utiliser des requetes preparees"
    languages: [php]
    severity: ERROR
```

### 3.6 Verifier l'integration dans le Jenkinsfile

```bash
grep -A 15 "Semgrep SAST" \
  /opt/furious-ducks/infrastructure/jenkins/shared-library/vars/ciPipeline.groovy
```

### 3.7 Declencher un premier pipeline avec Semgrep

1. Sur un projet configure avec le pipeline, pousse une modif sur `main`.
2. Jenkins lance le pipeline -> le stage **"Semgrep SAST"** s'execute.
3. Verifier que le rapport `semgrep-report.json` est archive dans les artefacts.

## 4. Utilisation au quotidien

### 4.1 Scanner son code en local avant de commit

```bash
cd /chemin/vers/projet
docker run --rm -v $(pwd):/src semgrep/semgrep \
  semgrep scan --config auto /src
```

### 4.2 Scanner uniquement les fichiers modifies

```bash
# Liste des fichiers modifies dans la branche courante
CHANGED_FILES=$(git diff --name-only main...HEAD)

docker run --rm -v $(pwd):/src semgrep/semgrep \
  semgrep scan --config auto $CHANGED_FILES
```

### 4.3 Generer un rapport lisible pour la relecture

```bash
docker run --rm -v $(pwd):/src semgrep/semgrep \
  semgrep scan --config auto --output /src/semgrep.txt /src
```

### 4.4 Exclure un repertoire (node_modules, vendor)

```bash
docker run --rm -v $(pwd):/src semgrep/semgrep \
  semgrep scan --config auto \
    --exclude vendor \
    --exclude node_modules \
    /src
```

### 4.5 Ignorer une alerte (faux positif)

Ajoute un commentaire inline dans le code :

```python
# nosemgrep: python.lang.security.audit.eval-detected
eval(user_input)  # Utilise dans un contexte sandbox
```

### 4.6 Utiliser un ruleset specifique pour audit OWASP

```bash
docker run --rm -v $(pwd):/src semgrep/semgrep \
  semgrep scan --config p/owasp-top-ten /src
```

### 4.7 Consulter les rapports de pipeline

1. Jenkins -> pipeline du projet
2. Selectionner un build recent
3. **Build Artifacts** -> `semgrep-report.json`
4. Parser avec `jq` :

```bash
jq '.results | length' semgrep-report.json
jq '.results[] | {file: .path, rule: .check_id, msg: .extra.message}' \
  semgrep-report.json
```

## 5. Tests de validation

### Scan d'un projet

```bash
docker run --rm -v $(pwd):/src semgrep/semgrep \
  semgrep scan --config auto /src
```

### Scan avec ruleset OWASP

```bash
docker run --rm -v $(pwd):/src semgrep/semgrep \
  semgrep scan --config p/owasp-top-ten /src
```

### Verifier la detection d'une vulnerabilite connue

Creer un fichier PHP avec SQL injection (voir section 3.4) et confirmer que Semgrep la detecte.

### Verifier dans le pipeline Jenkins

1. Jenkins > pipeline du projet > build recent sur `main`
2. Verifier le stage **"Semgrep SAST"**
3. Verifier la presence de `semgrep-report.json` dans les artefacts

### Parser le rapport

```bash
jq '.results | length' semgrep-report.json
```

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| `config auto` ne charge pas les regles | Pas de connexion Internet | Verifier `curl -I https://semgrep.dev` |
| Scan tres lent sur gros projet | Trop de fichiers scannes | Ajouter `--exclude` pour node_modules, vendor |
| Faux positifs systematiques | Regle trop stricte | Ajouter `# nosemgrep: rule-id` sur la ligne concernee |
| Pipeline echoue sur `--error` | Trouvailles detectees | Revoir les findings, corriger ou ignorer explicitement |
| Rapport JSON vide | Aucun langage detecte | Verifier que `--config auto` cible les bons fichiers |
| Erreur "rule not found" | Ruleset inexistant | Verifier le nom sur semgrep.dev/r |
