# Gitleaks - Detection de Secrets

## 1. Presentation

- **Role** : Detection de secrets (mots de passe, tokens, cles API) commits accidentellement
- **Integration** : Stage Jenkins sur toutes les branches **sauf les tags** (`feature/*`, `release/*`, `hotfix/*`, `develop`, `main`), **bloquant** (pipeline en echec si secret detecte)
- **Pas de port expose** (CLI uniquement)
- **Faible taux de faux positifs** grace a des regexes precises et detection d'entropie
- **Complement** de la politique "aucun secret dans Git"
- **Hook pre-commit** disponible pour detection en amont (cote developpeur)

### Architecture

```
Pipeline Jenkins (toute branche sauf tag)
+------------------------------------------------------------+
|                                                            |
|  Lint -> Tests -> Gitleaks -> ... -> Docker Build -> ...   |
|                       |                                    |
|             Secret detecte = ARRET                         |
|                       |                                    |
|             Rapport JSON archive                           |
+------------------------------------------------------------+
Gitleaks passe AVANT tout build d'image : un secret n'atteint jamais Harbor.
```

### Types de secrets detectes

| Type                       | Exemples                              |
|----------------------------|---------------------------------------|
| Cles AWS                   | `AKIA...`, `AWS_SECRET_ACCESS_KEY`    |
| Tokens GitHub/GitLab       | `ghp_...`, `glpat-...`                |
| Cles API generiques        | `api_key=...`, `apikey=...`           |
| Mots de passe en dur       | `password=...`                        |
| Cles privees               | `-----BEGIN RSA PRIVATE KEY-----`     |
| Tokens JWT                 | `eyJ...`                              |
| URLs avec credentials      | `mysql://user:pass@host`              |
| Cles Stripe / SendGrid     | `sk_live_...`, `SG.`                  |

## 2. Configuration

### Execution dans le pipeline

Gitleaks est execute via `docker run` par la shared library, **avant le build de l'image**, sur toutes les branches sauf les tags `vX.Y.Z` (un tag promeut un commit deja scanne sur `main`). Il tourne des les branches de travail : un secret pousse sur une `feature/*` est deja compromis, donc la valeur du controle tient a sa precocite. Resultat **bloquant** : si un secret est detecte, le pipeline echoue et aucune image n'est construite ni deployee. Voir le stage **"Gitleaks"** dans `infrastructure/jenkins/shared-library/vars/ciPipeline.groovy`.

### Commandes type

| Commande | Description |
|----------|-------------|
| `gitleaks detect --source .` | Scan du repertoire courant |
| `gitleaks detect --source . --report-path report.json --report-format json` | Avec rapport JSON |
| `gitleaks detect --source . --no-git` | Scan des fichiers seulement (pas l'historique) |
| `gitleaks protect --staged` | Mode pre-commit (fichiers stages uniquement) |
| `gitleaks detect --source . --verbose` | Avec details |

### Fichier de configuration `.gitleaks.toml`

A placer a la racine d'un projet pour personnaliser :

```toml
title = "Furious Ducks - Gitleaks Configuration"

[allowlist]
  # Repertoires ignores
  paths = [
    '''vendor/''',
    '''node_modules/''',
    '''\.env\.example''',
    '''tests/fixtures/'''
  ]
  # Commits historiques connus (faux positifs)
  commits = [
    "abc123def456..."
  ]
  # Regexes de faux positifs
  regexes = [
    '''EXAMPLE_KEY''',
    '''AKIAIOSFODNN7EXAMPLE'''
  ]
```

## 3. Mise en route

Gitleaks ne necessite **aucune installation manuelle** sur le serveur : il est execute via `docker run` dans Jenkins.

### 3.1 Prerequis

- Docker disponible sur les agents Jenkins (deja le cas).
- Pour le hook pre-commit : Docker installe sur le poste des developpeurs.

### 3.2 Pre-pull de l'image Gitleaks

```bash
docker pull zricethezav/gitleaks:latest
```

### 3.3 Premier scan d'un projet

```bash
cd /chemin/vers/projet
docker run --rm -v $(pwd):/repo \
  zricethezav/gitleaks detect \
    --source /repo \
    --verbose
```

Si aucun secret : `no leaks found`. Sinon, liste detaillee.

### 3.4 Tester avec un faux secret pour valider la detection

```bash
mkdir -p /tmp/gitleaks-test
echo 'AWS_SECRET_ACCESS_KEY="AKIAIOSFODNN7EXAMPLE12345"' > /tmp/gitleaks-test/secret.txt

docker run --rm -v /tmp/gitleaks-test:/repo \
  zricethezav/gitleaks detect \
    --source /repo \
    --no-git \
    --verbose

# Cleanup
rm -rf /tmp/gitleaks-test
```

Gitleaks doit remonter le secret AWS.

### 3.5 Verifier l'integration dans le Jenkinsfile

```bash
grep -A 15 "Gitleaks" \
  /opt/furious-ducks/infrastructure/jenkins/shared-library/vars/ciPipeline.groovy
```

### 3.6 Tester le blocage du pipeline

1. Cree une branche de test : `git checkout -b test/gitleaks`
2. Ajoute un faux secret dans un fichier (ex : `aws_key = "AKIAIOSFODNN7EXAMPLE"`)
3. Commit et push : `git push origin test/gitleaks`
4. Merge vers `main` (en environnement de test uniquement !)
5. Verifier que le stage **"Gitleaks"** bloque le pipeline.
6. Important : ne JAMAIS pusher de vrai secret.

### 3.7 Installer le hook pre-commit (recommande pour les developpeurs)

Sur chaque poste developpeur, dans le repertoire du projet :

```bash
cat > .git/hooks/pre-commit <<'EOF'
#!/bin/bash
docker run --rm -v $(pwd):/repo zricethezav/gitleaks \
  protect --source /repo --staged --verbose
if [ $? -ne 0 ]; then
  echo "ERREUR: Secrets detectes dans les fichiers stages."
  echo "Annule avec 'git reset HEAD <fichier>' et corriger."
  exit 1
fi
EOF

chmod +x .git/hooks/pre-commit
```

Maintenant, chaque `git commit` declenche un scan automatique des fichiers stages.

## 4. Utilisation au quotidien

### 4.1 Scanner manuellement avant push

```bash
cd /chemin/vers/projet
docker run --rm -v $(pwd):/repo zricethezav/gitleaks \
  detect --source /repo
```

### 4.2 Scanner uniquement les fichiers stages

```bash
docker run --rm -v $(pwd):/repo zricethezav/gitleaks \
  protect --source /repo --staged
```

### 4.3 Scanner l'historique complet (audit)

```bash
docker run --rm -v $(pwd):/repo zricethezav/gitleaks \
  detect --source /repo --log-opts="--all"
```

### 4.4 Generer un rapport JSON

```bash
docker run --rm -v $(pwd):/repo zricethezav/gitleaks \
  detect --source /repo \
    --report-path /repo/gitleaks-report.json \
    --report-format json
```

### 4.5 Que faire si un vrai secret a ete commite ?

**Important** : un secret pousse sur GitLab est compromis, meme apres suppression du commit. Procedure :

1. **Revoque immediatement le secret** chez le fournisseur (AWS, GitHub, Stripe, etc.).
2. **Genere un nouveau secret**.
3. **Met a jour le secret** dans Jenkins Credentials Store ou le vault concerne.
4. (Optionnel mais recommande) Reecris l'historique avec `git filter-repo` ou `BFG Repo-Cleaner`. Coordonne avec l'equipe car cela force-push.
5. Documente l'incident dans le post-mortem.

### 4.6 Ajouter un faux positif a `.gitleaks.toml`

Si Gitleaks remonte un faux positif (cle de test, exemple), ajoute-le a l'allowlist :

```toml
[allowlist]
  regexes = [
    '''sk_test_aaaaaaaaaaaaaaaaaaaaaaaa''',
  ]
```

### 4.7 Consulter les rapports Jenkins

1. Jenkins -> projet -> build
2. Build Artifacts -> `gitleaks-report.json`
3. Parser :

```bash
jq '.[] | {desc: .Description, file: .File, line: .StartLine, secret: .Secret}' \
  gitleaks-report.json
```

## 5. Tests de validation

### Scan d'un depot

```bash
docker run --rm -v $(pwd):/repo zricethezav/gitleaks detect --source /repo
```

### Tester la detection d'un faux secret

Voir section 3.4 ci-dessus.

### Verifier que le pipeline bloque

Voir section 3.6 ci-dessus (en environnement de test uniquement).

### Verifier le rapport archive

```bash
# Apres un build Jenkins
jq '. | length' gitleaks-report.json
```

### Verifier le hook pre-commit

```bash
# Tenter de commit un faux secret
echo 'API_KEY="sk_live_abcdef123456"' > test.txt
git add test.txt
git commit -m "test"
# Doit etre bloque
git reset HEAD test.txt && rm test.txt
```

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| `no leaks found` mais secret evident | Secret hors regexes connues | Ajouter une regle custom dans `.gitleaks.toml` |
| Pipeline bloque sur faux positif | Regex trop large | Ajouter le pattern dans `[allowlist]` de `.gitleaks.toml` |
| Hook pre-commit ne s'execute pas | `chmod +x` oublie | `chmod +x .git/hooks/pre-commit` |
| Hook tres lent | Scan complet a chaque commit | Utiliser `protect --staged` (fichiers stages uniquement) |
| Vrai secret detecte en CI | Commit accidentel | Voir section 4.5 (revoquer + roter le secret) |
| Rapport JSON `null` | Aucun secret detecte | Comportement normal, pas d'erreur |
| Permission denied sur `/repo` | Volume mal monte | Verifier `-v $(pwd):/repo` |
