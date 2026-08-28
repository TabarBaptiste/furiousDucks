# Backup — comment ça marche

Cette page explique, sans rentrer dans les détails techniques, ce que fait le système de sauvegarde de la plateforme (`infrastructure/backup/`). L'objectif est de comprendre la structure et le comportement global, pas de documenter chaque ligne de script.

## Vue d'ensemble

Le backup repose sur trois briques (définies dans `infrastructure/docker-compose.backup.yml`) :

- **MinIO** : un espace de stockage (compatible S3) où sont rangées les sauvegardes.
- **Backup Manager** : un conteneur (construit à partir de `infrastructure/backup/Dockerfile`) qui contient les scripts de sauvegarde et l'outil **Restic**, chargé de faire les sauvegardes et de les envoyer vers MinIO.
- **Ofelia** : un planificateur (façon "cron") qui déclenche les scripts du Backup Manager à heures fixes, sans intervention humaine.

Le Backup Manager ne fait tourner aucun service en continu : il reste en veille (`tail -f /dev/null`) et attend qu'Ofelia lui donne un script à exécuter.

## Le Dockerfile

Construit une image simple : Restic + quelques outils (bash, curl, coreutils, client PostgreSQL), et copie les scripts dans le conteneur en les rendant exécutables. Rien de plus — c'est une boîte à outils, pas une application.

## Ce qui est sauvegardé

Le Backup Manager reçoit, en lecture seule, tous les volumes de données de la plateforme (GitLab, Jenkins, Prometheus, Grafana, SonarQube, Mattermost) ainsi que les données de Harbor (le registre d'images). "Lecture seule" garantit que le processus de sauvegarde ne peut jamais modifier ou corrompre les données de production par erreur.

## Les 4 scripts

### `backup-all.sh` — la sauvegarde quotidienne (02h00)

C'est le script principal. À chaque exécution :

1. Il vérifie que le dépôt de sauvegarde (sur MinIO) existe, et l'initialise si c'est la toute première fois.
2. Il fait un **export propre** des bases de données de SonarQube et Mattermost (via `pg_dump`) avant de les sauvegarder — une copie "à froid" d'une base en cours d'utilisation pourrait être incohérente, alors qu'un export logique capture un état stable.
3. Il sauvegarde ensuite, un par un, tous les volumes listés plus haut (GitLab, Jenkins, Prometheus, Grafana, SonarQube, Mattermost, plus les exports de bases de données), en étiquetant chaque sauvegarde avec son nom et le tag `daily`.
4. Si un volume est absent, il est simplement ignoré (pas d'échec bloquant).
5. Le résultat (succès ou nombre d'échecs) est écrit dans un fichier lu par Prometheus, pour que les alertes de supervision (« la sauvegarde a échoué », « la dernière sauvegarde est trop vieille ») puissent se déclencher automatiquement.
6. En cas d'échec, un message est envoyé sur Mattermost via un webhook.

À noter : GitLab embarque sa propre base PostgreSQL, non accessible directement — elle est donc sauvegardée au niveau du volume complet plutôt que par export logique.

### `backup-harbor.sh` — la sauvegarde de Harbor (dimanche 01h00)

Sauvegarde séparément les données du registre d'images Harbor, car Harbor n'est pas déployé via les mêmes volumes Docker que le reste (il utilise un dossier monté directement sur le serveur). Contrairement au reste, cette sauvegarde est **hebdomadaire** et non quotidienne : les images critiques ne changent qu'au rythme des releases, et Harbor a déjà sa propre politique de nettoyage interne. Si Harbor n'est pas installé sur la machine, le script le détecte et s'arrête proprement sans erreur.

### `retention.sh` — le nettoyage des anciennes sauvegardes (1er du mois, 04h00)

Sans nettoyage, l'espace de stockage grossirait indéfiniment. Ce script applique une politique classique dite "Grandfather-Father-Son" :

- les 7 dernières sauvegardes quotidiennes,
- les 4 dernières hebdomadaires,
- les 6 dernières mensuelles,
- les 2 dernières annuelles,

...sont conservées ; le reste est supprimé. Un contrôle d'intégrité du dépôt est relancé juste après le nettoyage, pour s'assurer que rien n'a été corrompu pendant l'opération. Un mode "simulation" (`DRY_RUN`) existe pour prévisualiser ce qui serait supprimé sans rien effacer réellement.

### `verify-integrity.sh` — le contrôle de santé (dimanche 03h00)

Une sauvegarde qui échoue silencieusement ne sert à rien. Ce script :

1. Vérifie que le dépôt de sauvegarde n'est pas corrompu.
2. Liste les dernières sauvegardes.
3. Vérifie qu'au moins une sauvegarde a été faite dans les dernières 26 heures — sinon, une alerte est envoyée sur Mattermost. Les 26h (au lieu de 24h) laissent une petite marge si le job quotidien a un peu de retard.

### `restore.sh` — la restauration manuelle (à la demande)

Le seul script qui ne tourne pas automatiquement — il est prévu pour être lancé à la main en cas de besoin. Trois usages :

- Sans argument : affiche la liste des 10 dernières sauvegardes disponibles.
- Avec un identifiant de sauvegarde et un dossier de destination : restaure l'intégralité de la sauvegarde.
- Avec en plus l'option `--volume` : restaure seulement une donnée précise (par exemple juste Jenkins).

Le script demande une confirmation avant d'écraser quoi que ce soit (sauf si on l'utilise dans un contexte automatisé/non-interactif), puis vérifie après coup que les fichiers restaurés sont bien présents.

## Planning automatique (résumé)

| Quand | Script | Rôle |
|---|---|---|
| Tous les jours à 02h00 | `backup-all.sh` | Sauvegarde complète |
| Dimanche à 01h00 | `backup-harbor.sh` | Sauvegarde du registre d'images |
| Dimanche à 03h00 | `verify-integrity.sh` | Contrôle de santé des sauvegardes |
| 1er du mois à 04h00 | `retention.sh` | Nettoyage des anciennes sauvegardes |

Ce planning est défini dans `infrastructure/docker-compose.backup.yml`, via le service Ofelia.

## Comportement commun aux scripts

- Chaque exécution écrit un fichier de log horodaté (conservé dans un volume dédié, pour garder un historique consultable même après redémarrage du conteneur).
- Toute erreur envoie automatiquement une notification sur Mattermost (via un webhook), pour que l'équipe soit prévenue sans avoir à surveiller les logs en continu.
- Tous les scripts exigent que l'adresse et le mot de passe du dépôt de sauvegarde soient configurés (variables d'environnement) — sans ça, ils s'arrêtent immédiatement plutôt que de risquer un comportement incorrect.
