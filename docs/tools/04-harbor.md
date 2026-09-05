# Harbor - Registre Docker

## 1. Presentation

- **Role** : Stockage des images Docker construites par la CI, scan de vulnerabilites, politiques de retention
- Registre unique pour tous les projets de l'agence Furious Ducks
- **Port** : `443` (HTTPS via Traefik)
- **Sous-domaine** : `registry.wk-archi-o24a-4-5-g1.fr`
- **Convention de nommage** :
  ```
  registry.wk-archi-o24a-4-5-g1.fr/<projet>/<service>:<tag>
  ```
- **Tags** :
  - Semver : `v1.2.3` (releases)
  - Commit SHA : `abc1234` (builds intermediaires)
  - `latest` : uniquement en environnement DEV
- **Promotion d'images** : DEV -> PREPROD -> PROD (la meme image est promue sans reconstruction)

## 2. Configuration

### Installation

- Installation via **Harbor installer officiel** (script `install.sh`)
- Configuration du fichier `harbor.yml` avec les parametres du domaine et du stockage

### Integration Traefik

- Harbor est expose en HTTPS via Traefik
- Le TLS est gere par Traefik (Harbor ecoute en HTTP en interne)
- Labels Docker pour le routage automatique vers `registry.wk-archi-o24a-4-5-g1.fr`

### Scan de vulnerabilites

- **Trivy** integre pour scanner chaque image a chaque push
- Scan automatique declenche apres chaque push d'image
- Resultats visibles dans l'interface Harbor pour chaque tag
- Politique : blocage du pull si vulnerabilites critiques detectees (configurable par projet)

### RBAC

- Gestion des permissions par projet Harbor
- Roles : Project Admin, Maintainer, Developer, Guest
- Chaque projet de l'agence a son propre espace dans Harbor

### Politiques de retention

- Images de travail (`develop-*`, `main-*`) : garder les **10 plus recentes**
- Images de release (`v*`) : **conservees indefiniment** (dependance de
  `Promote Image` et de `scripts/rollback.sh`)
- Supprimer les images non taguees apres **7 jours** (garbage collection)
- Execution automatique des politiques de retention (detail en 3.9)

### Garbage collection

- Garbage collection planifiee (hebdomadaire)
- Liberation de l'espace disque apres suppression des images

## 3. Mise en route

**Important** : Harbor n'est **pas** dans le `docker-compose.yml` principal du workflow. Il a son propre installeur officiel qui genere son propre `docker-compose.yml`. Tu dois l'installer separement, puis l'integrer a Traefik.

### 3.1 Prerequis

- Traefik tourne et le DNS `registry.wk-archi-o24a-4-5-g1.fr` pointe vers le serveur.
- Le serveur a au moins **8 Go de RAM libres** (Harbor est gourmand : Postgres, Redis, Trivy, registry).
- Au moins **50 Go d'espace disque** libres (pour stocker les images).
- Les reseaux Docker `ci_network` (cree par le compose principal) et
  `workflow-network` existent.

### 3.2 Installer Harbor

```bash
# Recuperer la derniere version
cd /opt
sudo wget https://github.com/goharbor/harbor/releases/download/v2.11.0/harbor-online-installer-v2.11.0.tgz
sudo tar xvf harbor-online-installer-v2.11.0.tgz
cd harbor

# Copier le template de config
sudo cp harbor.yml.tmpl harbor.yml
sudo nano harbor.yml
```

Edite `harbor.yml` avec ces valeurs :

```yaml
hostname: registry.wk-archi-o24a-4-5-g1.fr

http:
  port: 8080      # Important : port interne, Traefik gere le 443

# IMPORTANT : commenter TOUTE la section https
# https:
#   port: 443
#   certificate: ...
#   private_key: ...

# Mot de passe initial admin (a changer apres login)
harbor_admin_password: <HARBOR_ADMIN_PASSWORD du .env>

# Mot de passe BDD interne
database:
  password: <HARBOR_DB_PASSWORD du .env>

# Chemin des donnees Harbor sur l'hote. Garder le defaut officiel de
# l'installeur Harbor (/data) : la stack de backup bind-monte /data en entier
# (voir docker-compose.backup.yml et backup-harbor.sh) pour couvrir n'importe
# quel data_volume place sous cette arborescence, sans configuration
# supplementaire.
data_volume: /data

# Stockage des images
trivy:
  ignore_unfixed: false
  skip_update: false
  insecure: false
```

Lance l'installeur **avec Trivy** :

```bash
sudo ./install.sh --with-trivy
```

L'installation prend 5-10 minutes (telechargement et demarrage des conteneurs).

### 3.3 Integrer Harbor a Traefik

Apres installation, Harbor genere son propre `docker-compose.yml` dans `/opt/harbor`. Il faut :

1. **Ajouter les reseaux ci_network et workflow-network** au service `nginx` (ou `proxy`) de Harbor — le meme pattern que tous les autres services routes par Traefik dans le socle (traefik, gitlab, jenkins, sonarqube, mattermost), qui rejoignent systematiquement les deux reseaux.
2. **Ajouter les labels Traefik**, avec `traefik.docker.network=ci_network` pour lever l'ambiguite puisque Traefik lui-meme vit sur `ci_network` (comme pour le service `sonarqube` du `docker-compose.yml` principal).

```bash
sudo nano /opt/harbor/docker-compose.yml
```

Repere le service `proxy` (nginx) et modifie-le :

```yaml
services:
  proxy:
    # ... config existante ...
    networks:
      - harbor                # reseau interne Harbor (existant)
      - ci_network             # AJOUTER
      - workflow-network      # AJOUTER
    labels:
      - "traefik.enable=true"
      - "traefik.docker.network=ci_network"
      - "traefik.http.routers.harbor.rule=Host(`registry.wk-archi-o24a-4-5-g1.fr`)"
      - "traefik.http.routers.harbor.entrypoints=websecure"
      - "traefik.http.routers.harbor.tls.certresolver=letsencrypt"
      - "traefik.http.services.harbor.loadbalancer.server.port=8080"
      - "traefik.http.routers.harbor.middlewares=secure-headers@file"

networks:
  harbor:
    external: false
  ci_network:
    external: true
  workflow-network:
    external: true
```

### 3.4 Metriques Prometheus

Pour que Prometheus (job `harbor` dans `infrastructure/prometheus/prometheus.yml`) puisse scraper Harbor :

1. Active les metriques dans `harbor.yml` avant l'installation (ou reconfigure via `./install.sh` apres modification) :

```yaml
metric:
  enabled: true
  port: 9090
  path: /metrics
```

2. Le nginx de Harbor (service `proxy`, **container_name `nginx`** — convention
   Harbor, pas `harbor-nginx`) agrege les metriques des composants internes
   (core, jobservice, registry, exporter) sur le port **9090**. C'est ce
   conteneur que Prometheus scrape ; il est deja rattache a `ci_network` par
   l'etape 3.3. **Ne publie pas 9090 sur l'hote** : commente aussi
   `- 9090:9090` dans la section `ports:` du service `proxy`, au meme titre que
   `- 80:8080`.

   La cible correspondante dans `infrastructure/prometheus/prometheus.yml`
   (job `harbor`) est donc `nginx:9090`.

3. Redemarre Harbor :

```bash
cd /opt/harbor
sudo docker compose down
sudo docker compose up -d
```

Tant que ces etapes ne sont pas faites, la cible `nginx:9090` remonte "down"
dans Prometheus — c'est attendu si Harbor n'est pas encore deploye.

> **Attention** : re-executer `./prepare` (par exemple apres avoir modifie
> `harbor.yml`) **regenere `docker-compose.yml` de zero** et efface les
> modifications de 3.3 et 3.4. Il faut les re-appliquer puis relancer
> `docker compose up -d` a chaque fois.

### 3.5 Premier login

1. Ouvre `https://registry.wk-archi-o24a-4-5-g1.fr`
2. User : `admin`
3. Password : `HARBOR_ADMIN_PASSWORD` du `.env`
4. Change le mot de passe via **Profile > Change Password**

### 3.6 Creer un projet Harbor

Un "projet Harbor" = un namespace pour tes images.

1. **Projects > + New Project**
2. Project Name : `furious-ducks` (ou un nom par client/projet)
3. Access Level : Private (recommande), Public si tu veux que les images soient pull sans login
4. Storage Quota : Unlimited (ou definir une quota)
5. **OK**

### 3.7 Tester un push depuis le serveur

```bash
# Login
echo "<HARBOR_ADMIN_PASSWORD>" | docker login registry.wk-archi-o24a-4-5-g1.fr -u admin --password-stdin
# Doit afficher : Login Succeeded

# Tag d'une image existante
docker pull alpine:latest
docker tag alpine:latest registry.wk-archi-o24a-4-5-g1.fr/furious-ducks/test:1.0.0

# Push
docker push registry.wk-archi-o24a-4-5-g1.fr/furious-ducks/test:1.0.0

# Verifier dans l'UI Harbor : projet "furious-ducks" > image "test" > tag "1.0.0"
```

### 3.8 Configurer le scan de vulnerabilites

1. **Configuration > Vulnerability** : coche "Scan all images automatically when pushed"
2. Schedule : Daily (re-scan quotidien pour detecter nouvelles CVE)
3. **Save**

### 3.9 Configurer la politique de retention

Deux regles complementaires, a creer dans cet ordre. Harbor evalue toutes les
regles et **conserve une image des qu'au moins une regle la retient** : la regle
2 protege donc les releases meme quand la regle 1 ne les garderait plus.

**Regle 1 - images de travail (DEV / PREPROD)**

1. Sur le projet > **Tag Retention** > **+ Add Rule**
2. Repository : **all repositories**
3. Tag : **matching** `develop-*,main-*`
4. Retention : **retain the most recent 10 tags**
5. **Save**

**Regle 2 - releases de production**

1. **+ Add Rule**
2. Repository : **all repositories**
3. Tag : **matching** `v*`
4. Retention : **retain always**
5. **Save**

**Images non taguees**

Sur la meme page, activer **"allow deletion of untagged artifacts"** : les
images orphelines (laissees par un retag) sont alors purgees. Le CST fixe ce
delai a 7 jours, applique par la garbage collection planifiee (voir 3.10).

**Planification**

Schedule de la politique : **Weekly (Saturday 05:00)**, soit **avant** la
sauvegarde hebdomadaire Restic du registre (dimanche 01:00) - le contenu
sauvegarde est ainsi deja purge du superflu (justification CST 7.5). La
garbage collection s'intercale entre les deux (samedi 07:00, voir 3.10), de
sorte que l'espace est reellement libere avant que Restic ne lise `/data`.

**Etat applique (2026-09-05)** : la politique existe (`retention_id = 1` sur le
projet `furious-ducks`) et a ete appliquee une premiere fois. Avant :
130 artefacts sur `thetiptop`, 38 sur `thetiptop-api` ; apres : 29 et 12. Les
quatre tags de release (`v1.1.2`, `v1.1.4`, `v1.2.0`, `v1.2.1`), le tag
`latest` et les images alors deployees (`develop-eed5703`, `main-89fa256`) ont
ete conserves - controle explicite avant et apres execution.

**Verifier / rejouer depuis la ligne de commande**

L'interface web reste la voie normale ; l'API permet de verifier l'etat sans
ouvrir un navigateur, et d'executer **a blanc** avant de supprimer quoi que ce
soit.

```bash
set -a && . ./.env && set +a
H=https://registry.wk-archi-o24a-4-5-g1.fr/api/v2.0

# La politique est-elle attachee au projet ? (cherche "retention_id")
curl -s -u "admin:$HARBOR_ADMIN_PASSWORD" "$H/projects/furious-ducks/metadatas"

# La lire
curl -s -u "admin:$HARBOR_ADMIN_PASSWORD" "$H/retentions/1" | python3 -m json.tool

# L'executer A BLANC (ne supprime rien, renseigne total/retained par depot)
curl -s -u "admin:$HARBOR_ADMIN_PASSWORD" -X POST -H 'Content-Type: application/json' \
  -d '{"dry_run":true}' "$H/retentions/1/executions"
curl -s -u "admin:$HARBOR_ADMIN_PASSWORD" "$H/retentions/1/executions?page_size=1" | python3 -m json.tool
```

> **Pourquoi deux regles et pas une seule `all tags` ?**
> Une regle unique "retain the most recent 10" sur `all tags` supprimerait les
> tags `v*` des que 10 images `develop-*`/`main-*` plus recentes existent - soit
> quelques jours. Or le stage **Promote Image** du pipeline retague l'image
> `main-<sha>` en `v1.2.3` sans jamais reconstruire, et `scripts/rollback.sh`
> revient sur une release anterieure en la retirant de Harbor. Perdre un tag
> `v*`, c'est perdre la capacite de rollback ET la promesse de reproductibilite
> (CST 2.4 : "la meme image construite une fois peut etre reutilisee a
> l'identique a travers les environnements sans reconstruction").

### 3.10 Configurer la garbage collection

La politique de retention **supprime les references** aux images ; la garbage
collection **libere reellement l'espace disque** des couches devenues
orphelines. Sans elle, `/data` continue de grossir malgre la retention.

1. **Administration > Garbage Collection**
2. Cocher **"Delete Untagged Artifacts"**
3. Schedule : **Weekly (Saturday 07:00)** - apres la politique de retention de
   samedi 05:00, et avant la sauvegarde du registre du dimanche 01:00
4. **Save**

**Etat applique (2026-09-05)** : planifiee (`0 0 7 * * 6`,
`delete_untagged: true`) et executee une premiere fois dans la foulee de la
retention. `/data/registry` est passe de **3,5 Go a 870 Mo**.

> **A savoir avant de lancer une GC manuelle** : Harbor bascule le registre en
> **lecture seule** pendant l'operation. Un `docker push` d'un pipeline en cours
> echouerait. La lancer hors des heures de build (c'est la raison du creneau du
> samedi matin).

```bash
# Planification enregistree
curl -s -u "admin:$HARBOR_ADMIN_PASSWORD" "$H/system/gc/schedule" | python3 -m json.tool

# Declencher une GC manuelle
curl -s -u "admin:$HARBOR_ADMIN_PASSWORD" -X POST -H 'Content-Type: application/json' \
  -d '{"schedule":{"type":"Manual"},"parameters":{"delete_untagged":true,"workers":1}}' \
  "$H/system/gc/schedule"

# Suivre
curl -s -u "admin:$HARBOR_ADMIN_PASSWORD" "$H/system/gc?page_size=1" | python3 -m json.tool
```

### 3.11 Configurer le compte Jenkins

Cree un user dedie pour Jenkins (mieux que d'utiliser admin) :

1. **Administration > Users > + New User**
2. Username : `jenkins`
3. Password : <generer un mot de passe fort>
4. **OK**
5. Sur le projet > **Members > + User** > ajouter `jenkins` avec role **Maintainer**
6. Met a jour le credential `harbor-credentials` dans Jenkins avec ces creds.

## 4. Utilisation au quotidien

### 4.1 Push depuis un pipeline (ce que fait Jenkins)

```bash
docker build -t registry.wk-archi-o24a-4-5-g1.fr/furious-ducks/thetiptop:v1.2.3 .
docker login registry.wk-archi-o24a-4-5-g1.fr -u jenkins -p <token>
docker push registry.wk-archi-o24a-4-5-g1.fr/furious-ducks/thetiptop:v1.2.3
```

### 4.2 Pull une image (ce que font les serveurs de deploiement)

```bash
docker login registry.wk-archi-o24a-4-5-g1.fr -u jenkins -p <token>
docker pull registry.wk-archi-o24a-4-5-g1.fr/furious-ducks/thetiptop:v1.2.3
```

### 4.3 Voir les vulnerabilites d'une image

1. **Projects > furious-ducks > thetiptop**
2. Cliquer sur un tag
3. Onglet **Vulnerabilities** : liste des CVE classees Critical / High / Medium / Low
4. Cliquer sur une CVE pour voir le package, la version vulnerable, la version correctrice

### 4.4 Promouvoir une image entre environnements

Tu n'as **pas besoin** de re-builder l'image. Elle est juste re-deployee avec le meme tag :

```bash
# Ce que fait le pipeline en PROD :
docker pull registry.wk-archi-o24a-4-5-g1.fr/furious-ducks/thetiptop:v1.2.3
# (la meme image deja deployee en PREPROD)
```

### 4.5 Supprimer une image

1. **Projects > furious-ducks > thetiptop**
2. Cocher les tags a supprimer > **Action > Delete**
3. Pour liberer l'espace disque, lancer le garbage collection : **Administration > Garbage Collection > GC Now**

### 4.6 Sauvegarder Harbor

Harbor stocke ses donnees dans `/data` (defaut officiel de l'installeur,
configurable via `data_volume` dans `harbor.yml`) : `registry/`, `database/`,
`redis/`, `job_logs/`, `secret/`, `trivy-adapter/`, `ca_download/`.

**Sauvegarde automatique** : `backup-harbor.sh` (stack `docker-compose.backup.yml`) monte ce chemin en lecture seule dans le conteneur `backup-manager` et sauvegarde via Restic vers MinIO chaque dimanche a 01h00 (avant le backup quotidien general et la verification d'integrite hebdomadaire). Voir CST section "7.5 Pourquoi un backup hebdomadaire pour Harbor plutot que quotidien ?".

**Important** : `docker-compose.backup.yml` bind-monte `/data` en entier — pas
seulement un sous-repertoire — c'est ce qui permet de couvrir n'importe quel
`data_volume` place sous cette arborescence. Si `data_volume` sort de `/data`
(chemin custom), il faut mettre a jour ce bind mount (service `backup-manager`).

**Limite connue** : `/data/database/` est un repertoire de donnees
PostgreSQL **vivant**. La sauvegarde est donc une copie a chaud ; pour une
restauration Harbor pleinement coherente, arreter Harbor avant le backup.

**Backup manuel ponctuel** (hors planification automatique) :

```bash
docker exec common-backup-manager-ci /scripts/backup-harbor.sh
```

**Restauration** : `restore.sh <snapshot_id> <target_path> --volume harbor-registry` (voir `backup/scripts/restore.sh`).

### 4.7 Mettre a jour Harbor

Harbor se met a jour via son installeur (PAS via `docker pull`) :

```bash
cd /opt
sudo wget https://github.com/goharbor/harbor/releases/download/v<NEW>/harbor-online-installer-v<NEW>.tgz
sudo tar xvf harbor-online-installer-v<NEW>.tgz -C harbor-new
# Copier l'ancien harbor.yml
sudo cp /opt/harbor/harbor.yml /opt/harbor-new/harbor/harbor.yml
# Stopper l'ancien
cd /opt/harbor && sudo docker compose down
# Lancer le nouveau (qui migre la BDD)
cd /opt/harbor-new/harbor && sudo ./install.sh --with-trivy
```

## 5. Tests de validation

### Acces web

```bash
curl -f https://registry.wk-archi-o24a-4-5-g1.fr
```

Resultat attendu : code HTTP 200, page d'accueil Harbor.

### Login Docker

```bash
docker login registry.wk-archi-o24a-4-5-g1.fr
```

Resultat attendu : authentification reussie avec les credentials configures.

### Push et pull d'une image de test

```bash
docker tag hello-world:latest registry.wk-archi-o24a-4-5-g1.fr/test/hello:latest
docker push registry.wk-archi-o24a-4-5-g1.fr/test/hello:latest
docker pull registry.wk-archi-o24a-4-5-g1.fr/test/hello:latest
```

Resultat attendu : push et pull reussis sans erreur.

### Verifier le scan Trivy

1. Apres le push d'une image, naviguer vers le projet dans l'interface Harbor
2. Selectionner le tag de l'image pushee
3. Verifier que le scan Trivy s'est execute automatiquement
4. Consulter le rapport de vulnerabilites (nombre de critiques, hautes, moyennes, basses)

### Verifier les politiques de retention

1. Naviguer vers **Projects > (nom du projet) > Configuration > Tag Retention**
2. Verifier la presence des **deux** regles (voir 3.9) :
   - `develop-*,main-*` > retain the most recent 10 tags
   - `v*` > retain always
3. Verifier que **"allow deletion of untagged artifacts"** est coche
4. Utiliser **Dry Run** : la simulation doit lister des images `develop-*`/`main-*`
   a supprimer, et **aucune** image `v*`. Si un tag `v*` apparait, la regle 2 est
   mal configuree - le rollback et la promotion d'image sont alors compromis.
5. **Administration > Garbage Collection** : verifier la planification hebdomadaire

## 6. Depannage

| Symptome | Cause | Solution |
|----------|-------|----------|
| `unauthorized: authentication required` au push | User pas Maintainer du projet | Ajouter le role |
| `denied: requested access to the resource is denied` | Projet inexistant | Creer le projet dans Harbor |
| Push lent (timeout) | Bande passante limitee | Tag par couches plus petites |
| Scan Trivy en "Not Scanned" | Trivy down | `docker logs harbor-trivy-adapter` |
| Espace disque sature | Trop d'images | Lancer le garbage collection |
| Harbor inaccessible apres reboot | Reseau workflow-network manquant | Recreer + redemarrer Harbor |
