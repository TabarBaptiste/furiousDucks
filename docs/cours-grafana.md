# Cours : comprendre et utiliser Grafana dans le workflow Furious Ducks

> Ce document est un cours d'introduction, pense pour quelqu'un qui n'a jamais utilise Grafana. Pour la reference technique complete (configuration, depannage), voir [`07-grafana.md`](07-grafana.md).

---

## 1. C'est quoi, Grafana ?

Grafana est un outil de **visualisation de donnees**. Il ne collecte rien lui-meme : il se connecte a des sources de donnees externes (qu'on appelle des **datasources**) et les affiche sous forme de graphiques, tableaux, jauges, etc., dans des **dashboards** (tableaux de bord).

Une image simple :

```
Prometheus         Loki
(mesure des        (stocke des
 chiffres           lignes de
 dans le temps)     texte/logs)
     |                  |
     +--------+---------+
              |
          Grafana
     (affiche tout ca dans
      des jolis graphiques)
```

Grafana lui-meme ne sait pas ce qu'est un CPU, une erreur ou un pipeline Jenkins. Il sait juste **poser une question** a une datasource ("donne-moi la valeur de X entre telle et telle heure") et **dessiner la reponse**.

## 2. Pourquoi Grafana existe dans le workflow Furious Ducks ?

Rappel important (deja aborde dans nos echanges precedents) : dans ce projet, Grafana **surveille la plateforme CI/CD elle-meme** (les serveurs, Docker, Jenkins, GitLab, Traefik, etc.) — pas les applications des clients comme Thé Tip Top. C'est l'outil qui repond au point 3.e de l'objectif n°1 du cahier des charges : "une ou plusieurs solutions de metriques".

Concretement, il sert a repondre a des questions comme :

- Le serveur va-t-il manquer de RAM ou de disque bientot ?
- Un conteneur consomme-t-il anormalement de CPU ?
- Pourquoi Jenkins a-t-il plante hier a 3h du matin ? (recherche dans les logs)
- Combien de pipelines ont echoue cette semaine ?
- Traefik route-t-il correctement le trafic ?

Sans cet outil, il faudrait se connecter en SSH sur le serveur et lire des fichiers de logs bruts a la main, sans historique ni vue d'ensemble.

## 3. Les trois briques derriere Grafana

Grafana ne fait que l'affichage. Trois autres services font le travail de collecte, dans notre infrastructure :

| Service | Role | Analogie |
|---|---|---|
| **Prometheus** | Collecte des **metriques** (des chiffres dans le temps : % CPU, Mo de RAM, nombre de requetes...) | Un compteur electrique qui releve des valeurs toutes les X secondes |
| **Alloy** | Collecte les **logs** (le texte que crachent les applications) sur chaque serveur et les envoie a Loki | Un facteur qui ramasse le courrier (les logs) de chaque conteneur |
| **Loki** | Stocke et indexe les logs recus d'Alloy, pour qu'on puisse les rechercher | Les archives ou le courrier est range |

Grafana vient se brancher sur Prometheus (langage de requete : **PromQL**) et sur Loki (langage de requete : **LogQL**) pour tout afficher au meme endroit.

> Pour aller plus loin sur chaque brique : [`06-prometheus.md`](06-prometheus.md), [`08-loki.md`](08-loki.md), [`16-alloy.md`](16-alloy.md).

## 4. Vocabulaire de base

Quatre mots reviennent tout le temps dans Grafana, autant les fixer des le depart :

- **Datasource** : une source de donnees connectee a Grafana (ici : Prometheus et Loki). Configuree une seule fois, ensuite invisible au quotidien.
- **Dashboard** : une page qui regroupe plusieurs graphiques lies a un theme (ex : "Node Monitoring" regroupe CPU/RAM/disque/reseau d'un serveur).
- **Panel** : un graphique individuel a l'interieur d'un dashboard (ex : le panel "CPU Usage").
- **Query** (requete) : la question posee a la datasource pour remplir un panel. En PromQL pour Prometheus, en LogQL pour Loki.

## 5. Se connecter

1. Ouvrir `https://monitor.wk-archi-o24a-4-5-g1.fr`
2. Identifiants : ceux definis dans `.env` (`GF_SECURITY_ADMIN_USER` / `GF_SECURITY_ADMIN_PASSWORD`)

Une fois connecte, le menu a gauche est le point de depart pour tout :

| Icone / entree | A quoi ca sert |
|---|---|
| **Dashboards** | Voir les tableaux de bord existants |
| **Explore** | Poser une question ponctuelle, sans creer de dashboard (le mode "recherche libre") |
| **Alerting** | Configurer des alertes automatiques |
| **Connections > Data sources** | Voir/configurer les connexions a Prometheus et Loki |

## 6. Visite guidee des 4 dashboards deja en place

Ils sont provisionnes automatiquement (dossier **Furious Ducks** dans **Dashboards**), pas besoin de les creer :

### Node Monitoring
Sante du serveur lui-meme : CPU, RAM, disque, reseau, charge systeme (`load average`). C'est le premier reflexe si "le serveur rame".

### Docker Monitoring
Consommation de ressources par conteneur (via cAdvisor). Utile pour reperer un conteneur qui fuit en memoire ou qui monopolise le CPU.

### CI/CD Monitoring
Statistiques sur les pipelines Jenkins (duree, taux de succes/echec). Utile pour suivre la sante du processus d'integration continue dans le temps, pas juste build par build.

### DORA Metrics
Les quatre indicateurs DORA (frequence de deploiement, lead time, taux d'echec des changements, MTTR). Ils sont calcules quotidiennement par un script dedie (`infrastructure/dora/`) qui interroge l'API GitLab et ecrit le resultat dans les fichiers texte lus par Node Exporter, puis scrapes par Prometheus.

**Pour naviguer dans un dashboard :**
- En haut a droite : la **plage de temps** ("Last 1 hour", "Last 24 hours"...) — c'est la cause n°1 d'un dashboard qui semble vide ("No data") : la plage ne couvre pas la periode qui t'interesse.
- A cote : l'**auto-refresh** (rafraichissement automatique, ex. toutes les 30s).
- En haut du dashboard, parfois une **variable** (menu deroulant, ex. `$instance`) pour filtrer sur un serveur en particulier.
- Clic-gauche + glisser sur un graphique : zoom sur une periode.

## 7. Explore : le mode "je cherche quelque chose"

C'est l'outil le plus utile au quotidien pour du depannage ponctuel — pas besoin de construire un dashboard pour une question unique.

1. Menu **Explore**
2. Choisir la datasource en haut (Prometheus ou Loki)
3. Ecrire une requete, cliquer **Run query**

### Exemples PromQL (metriques, datasource Prometheus)

```promql
# Charge CPU moyenne par serveur
100 - (avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)

# Est-ce qu'un service Prometheus est down ?
up == 0
```

### Exemples LogQL (logs, datasource Loki)

```logql
# Tous les logs d'un conteneur
{container_name="common-jenkins-ci"}

# Chercher un mot-cle dans les logs Docker
{job="docker"} |= "error"

# Combiner : erreurs d'un conteneur precis, insensible a la casse
{container_name="common-gitlab-ci"} |~ "(?i)error"
```

**Cas d'usage typique** : un pipeline Jenkins echoue sans raison evidente dans l'interface Jenkins -> Explore -> Loki -> `{container_name="common-jenkins-ci"}` -> chercher autour de l'heure de l'echec pour voir la stack trace complete.

## 8. Cas d'usage concrets dans le workflow

| Situation | Ou aller |
|---|---|
| "Le site est lent, c'est cote serveur ?" | Dashboard **Node Monitoring** -> verifier CPU/RAM/disque |
| "Un conteneur semble avoir plante" | Dashboard **Docker Monitoring**, puis **Explore > Loki** sur ce conteneur |
| "Pourquoi ce build Jenkins a echoue ?" | **Explore > Loki** -> `{container_name="common-jenkins-ci"}` |
| "Le disque risque-t-il de se remplir cette semaine ?" | **Node Monitoring**, panel Disk Usage, plage 7 jours |
| "Combien de pipelines ont echoue ce mois-ci ?" | Dashboard **CI/CD Monitoring** |
| "Je veux etre alerte automatiquement si le CPU depasse 90%" | **Alerting > Alert rules** (voir section suivante) |

## 9. Aller plus loin (quand tu seras a l'aise)

- **Creer une alerte** : sur un panel, `Edit > Alert > Create alert rule`, definir un seuil (ex. CPU > 90% pendant 5 min), puis un canal de notification (Mattermost). Detail complet dans [`07-grafana.md` §3.6 et §4.4](07-grafana.md).
- **Creer son propre dashboard** : `Dashboards > New > Add Visualization`, choisir une datasource, ecrire une requete, choisir un type de visuel (graph, jauge, table...).
- **Importer un dashboard tout fait** depuis [grafana.com/grafana/dashboards](https://grafana.com/grafana/dashboards) via son ID (ex. le dashboard officiel Docker/cAdvisor).

## 10. Ce que Grafana n'est PAS

Pour eviter les malentendus frequents chez les debutants :

- Ce n'est **pas** un outil de deploiement (ca, c'est Jenkins).
- Ce n'est **pas** la ou sont stockees les metriques (ca, c'est Prometheus) ni les logs (ca, c'est Loki) — Grafana ne fait qu'afficher.
- Il ne surveille **pas** Thé Tip Top ou d'autres projets clients dans la configuration actuelle — uniquement l'infrastructure Furious Ducks (voir la discussion sur `objectifs.md` : c'est la plateforme qui est instrumentee, pas les applications deployees dessus).
- Une alerte Grafana ne "corrige" rien automatiquement — elle notifie, c'est tout. L'action reste humaine (ou scriptee ailleurs, ex. `rollback.sh`).

---

**Pour la reference technique complete** (configuration precise, variables d'environnement, procedures de mise a jour, depannage detaille) : voir [`07-grafana.md`](07-grafana.md), [`06-prometheus.md`](06-prometheus.md), [`08-loki.md`](08-loki.md) et [`16-alloy.md`](16-alloy.md).
