# GitLab CE - Gestion de Code Source (SCM)

## 1. Presentation

- **Role** : Centralisation du code source, gestion des branches, merge requests, webhooks vers Jenkins
- **"Source of truth"** de l'agence Furious Ducks
- **Ports** :
  - `443` : HTTPS (via Traefik)
  - `2222` : SSH (acces Git direct)
- **Sous-domaine** : `git.wk-archi-o24a-4-5-g1.fr`
- **Strategie GitFlow** :
  - `main` : branche de production, protegee
  - `develop` : branche d'integration, protegee
  - `feature/*` : branches de fonctionnalites
  - `release/*` : branches de preparation de release
  - `hotfix/*` : branches de correction urgente
- **Conventional Commits** : format `type(scope): description` (feat, fix, docs, chore, refactor, test)
- **Semantic Versioning** : `vMAJOR.MINOR.PATCH` (ex: v1.2.3)
- **Merge Request obligatoire** avec 1 approbation minimum
- **Protection** des branches `main` et `develop` (pas de push direct)

## 2. Configuration

### Image et conteneur

- **Image** : `gitlab/gitlab-ce:17.4.0-ce.0`
- **Container** : `common-gitlab-ci`

### Volumes

- `gitlab-config` : configuration GitLab (`/etc/gitlab`)
- `gitlab-data` : donnees GitLab (`/var/opt/gitlab`)
- `gitlab-logs` : logs GitLab (`/var/log/gitlab`)

### GITLAB_OMNIBUS_CONFIG

- `external_url` : `https://git.wk-archi-o24a-4-5-g1.fr`
- Nginx configure en mode "behind reverse proxy" (Traefik gere le TLS)
- `gitlab_rails['gitlab_shell_ssh_port'] = 2222`

### Webhooks

- Configures vers Jenkins pour les evenements :
  - **Push events** : declenchement de build sur push
  - **Merge request events** : declenchement de pipeline de validation
- URL cible : `https://ci.wk-archi-o24a-4-5-g1.fr/project/<nom-projet>`

### Templates Merge Request

- Checklist Definition of Done (DoD) integree dans le template MR
- Sections : description, tests, documentation, review

### RBAC

- Groupes et permissions par projet
- Roles : Owner, Maintainer, Developer, Reporter, Guest
- Acces SSH par cle publique

## 3. Mise en route

GitLab est le 2eme service a configurer apres Traefik. Il met **5 a 10 minutes a demarrer la premiere fois** car il initialise sa BDD interne.

### 3.1 Prerequis

- Traefik est deja en route et expose les services en HTTPS.
- Le DNS `git.wk-archi-o24a-4-5-g1.fr` pointe vers le serveur.
- Le port 2222 est ouvert sur le firewall (pour SSH Git).
- Tu as renseigne `GITLAB_ROOT_PASSWORD` dans `.env`.
- Le serveur a au moins **4 Go de RAM libre** dedies a GitLab.

### 3.2 Demarrer GitLab

Si Traefik tourne deja :

```bash
cd /opt/furious-ducks/infrastructure
docker compose up -d gitlab
```

Suivre l'avancement (long la premiere fois) :

```bash
docker logs -f common-gitlab-ci
# Attendre la ligne : "GitLab is up. Use 'gitlab-ctl restart' to restart"
# Cela peut prendre 5 a 10 minutes
```

Verification rapide :
```bash
docker exec common-gitlab-ci gitlab-ctl status
# Tous les services doivent etre "run"
```

### 3.3 Premier login

1. Ouvre `https://git.wk-archi-o24a-4-5-g1.fr` dans un navigateur.
2. User : `root`
3. Password : la valeur de `GITLAB_ROOT_PASSWORD` dans ton `.env`
4. **Premiere chose a faire** : changer le mot de passe via **User Settings > Password**.

### 3.4 Creer ton compte personnel

Le compte `root` est reserve aux operations admin.

1. **Admin > Users > New user**
2. Nom, email, username
3. Apres creation, clique sur **Edit > Set password** pour definir un mot de passe initial
4. Login avec ton compte perso, change le mot de passe

### 3.5 Ajouter ta cle SSH

Pour pouvoir cloner / pousser via SSH :

1. Sur ta machine de dev, recupere ta cle publique :
   ```bash
   cat ~/.ssh/id_ed25519.pub
   # Si elle n'existe pas : ssh-keygen -t ed25519 -C "ton@email.com"
   ```
2. Dans GitLab : **User Settings > SSH Keys > Add new key**, colle le contenu.
3. Test :
   ```bash
   ssh -T -p 2222 git@git.wk-archi-o24a-4-5-g1.fr
   # Doit repondre : "Welcome to GitLab, @ton_user!"
   ```

### 3.6 Creer un Personal Access Token pour Jenkins

Jenkins a besoin d'un token pour interagir avec l'API GitLab (mettre a jour le statut des commits, creer des MR, etc.).

1. **User Settings > Access Tokens > Add new token**
2. Token name : `jenkins-token`
3. Expiration date : 1 an (a renouveler)
4. Scopes : coche **api**, **read_repository**, **write_repository**
5. **Create personal access token**
6. **Copie immediatement le token** (il ne sera plus jamais affiche). Tu vas le coller dans Jenkins (voir `03-jenkins.md`).

### 3.7 Configurer un projet (workflow standard)

Quand tu crees un nouveau projet :

1. **New project > Create blank project**
2. Coche "Initialize with README" si tu pars de zero, sinon decoche.
3. Visibility : Internal (visible par tous les users connectes) ou Private.
4. Une fois cree, va dans **Settings > Repository > Protected Branches** :
   - **main** : Allowed to merge = Maintainers, Allowed to push = No one, Code owner approval = optionnel
   - **develop** : Allowed to merge = Developers + Maintainers, Allowed to push = No one
5. **Settings > Merge requests** : coche "Pipelines must succeed", "All discussions must be resolved", "Approvals required: 1".
6. **Settings > Webhooks** : ajoute le webhook Jenkins (voir 4.4 ci-dessous).

## 4. Utilisation au quotidien

### 4.1 Cloner un projet

```bash
# Via SSH (recommande)
git clone ssh://git@git.wk-archi-o24a-4-5-g1.fr:2222/<group>/<project>.git

# Via HTTPS (necessite mot de passe ou token)
git clone https://git.wk-archi-o24a-4-5-g1.fr/<group>/<project>.git
```

### 4.2 Workflow GitFlow en pratique

```bash
# Demarrer une feature
git checkout develop
git pull
git checkout -b feature/ma-feature
# coder
git add .
git commit -m "feat(panier): ajouter bouton supprimer"
git push -u origin feature/ma-feature

# Sur GitLab, creer une Merge Request vers develop
# Faire reviewer, attendre approbation, merger via l'UI

# Creer une release
git checkout main
git pull
git merge --no-ff develop -m "chore(release): v1.2.0"
git push
git tag -a v1.2.0 -m "Release 1.2.0"
git push origin v1.2.0
```

### 4.3 Configurer un webhook vers Jenkins

Pour qu'un push declenche automatiquement un pipeline :

1. Dans le projet : **Settings > Webhooks > Add new webhook**
2. URL : `https://ci.wk-archi-o24a-4-5-g1.fr/project/<nom-du-job-jenkins>`
3. Triggers : coche **Push events**, **Merge request events**, **Tag push events**
4. SSL verification : laisse coche
5. **Add webhook**
6. Test : clique sur **Test > Push events**, tu dois voir HTTP 200

### 4.4 Gerer les utilisateurs et permissions

- **Admin > Users** pour creer/desactiver
- Sur un projet : **Project information > Members > Invite members**
  - Guest : voir le code
  - Reporter : commenter, creer issues
  - Developer : push sur branches non protegees
  - Maintainer : push partout, gerer settings
  - Owner : tout, supprimer le projet

### 4.5 Sauvegarder GitLab

Les backups sont automatises par Restic (volumes `gitlab-config`, `gitlab-data`). Pour un backup manuel applicatif GitLab :

```bash
docker exec common-gitlab-ci gitlab-backup create
# Le backup est dans /var/opt/gitlab/backups dans le conteneur
```

### 4.6 Mettre a jour GitLab

GitLab necessite des **mises a jour incrementales** (pas de saut de plus d'une version majeure) :

```bash
# Verifier la version actuelle
docker exec common-gitlab-ci cat /opt/gitlab/version-manifest.txt | head -1

# Editer docker-compose.yml et changer le tag de l'image
# Puis :
docker compose pull gitlab
docker compose up -d gitlab
```

## 5. Tests de validation

### Acces web

```bash
curl -f https://git.wk-archi-o24a-4-5-g1.fr/-/health
```

Resultat attendu : code HTTP 200, reponse indiquant que GitLab est operationnel.

### Clone SSH

```bash
git clone ssh://git@git.wk-archi-o24a-4-5-g1.fr:2222/group/project.git
```

Resultat attendu : clone reussi du depot via SSH sur le port 2222.

### Verifier les webhooks

1. Naviguer vers **Settings > Webhooks** dans le projet GitLab
2. Cliquer sur **Test** a cote du webhook Jenkins
3. Verifier que Jenkins recoit l'evenement et declenche un build

### Verifier les protections de branches

1. Naviguer vers **Settings > Repository > Protected Branches**
2. Verifier que `main` et `develop` sont listees comme protegees
3. Verifier que le push direct est interdit (uniquement merge via MR)

### Verifier le GitFlow

1. Creer une branche `feature/test-gitflow` depuis `develop`
2. Effectuer un commit et pousser la branche
3. Creer une Merge Request vers `develop`
4. Verifier que l'approbation est requise avant le merge
5. Supprimer la branche feature apres le merge

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| GitLab boucle au demarrage | Manque de RAM | `free -h`, ajouter de la RAM |
| 502 Bad Gateway via Traefik | GitLab pas encore demarre | Attendre 10 min, voir logs |
| `ssh: connection refused` | Port 2222 pas ouvert | `sudo ufw allow 2222/tcp` |
| Webhook ne declenche pas Jenkins | URL incorrecte ou cred manquant | Test depuis l'UI webhook |
| Mot de passe root oublie | - | `docker exec -it common-gitlab-ci gitlab-rake "gitlab:password:reset[root]"` |
