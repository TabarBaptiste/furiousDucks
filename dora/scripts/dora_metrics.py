#!/usr/bin/env python3
# =============================================================================
# Metriques DORA - thetiptop_v0
# =============================================================================
# Calcule les 4 metriques DORA a partir de l'historique GitLab (aucune donnee
# Jenkins requise) et les publie via le textfile collector de node-exporter,
# deja utilise par le backup (voir backup/scripts/backup-all.sh).
#
# Proxies retenus, faute d'outil de gestion d'incidents dedie :
#   - Deploiement       = creation d'un tag semver (vX.Y.Z). Le pipeline cree
#                         ces tags automatiquement a la fusion d'une branche
#                         release/* ou hotfix/*, avant l'approbation manuelle
#                         PROD dans Jenkins : un tag peut donc exister sans que
#                         le deploiement PROD ait ete approuve. Accepte comme
#                         approximation raisonnable vu l'absence d'API Jenkins
#                         fiable pour croiser ces evenements (cf. commentaire
#                         plus bas sur l'authentification Jenkins).
#   - Lead time         = temps entre chaque commit livre par un tag et la
#                         creation de ce tag, moyenne sur tous les commits
#                         compris entre ce tag et le precedent.
#   - Echec de changement = un merge hotfix/* vers main dans la fenetre,
#                         rapporte au nombre de tags dans la meme fenetre.
#   - MTTR              = temps entre l'ouverture et la fusion d'une MR
#                         hotfix/* vers main (proxy du temps de correction).
#
# Variables d'environnement :
#   GITLAB_URL         URL interne de GitLab (defaut: http://common-gitlab-ci)
#   GITLAB_TOKEN       Token d'API GitLab (obligatoire)
#   GITLAB_PROJECT_ID  ID du projet GitLab (defaut: 1, thetiptop_v0)
#   WINDOW_DAYS        Fenetre d'observation en jours (defaut: 30)
#   TEXTFILE_DIR       Repertoire textfile de node-exporter (defaut: /textfiles)
# =============================================================================

import os
import re
import sys
import tempfile
from datetime import datetime, timezone, timedelta

import requests

# Meme regle que deployEnvFor() dans ciPipeline.groovy (v\d+\.\d+\.\d+.*) :
# un tag ne compte comme deploiement que s'il correspond a ce que le pipeline
# reconnait lui-meme comme declencheur PROD.
SEMVER_TAG_RE = re.compile(r'^v\d+\.\d+\.\d+')

GITLAB_URL = os.environ.get('GITLAB_URL', 'http://common-gitlab-ci').rstrip('/')
GITLAB_TOKEN = os.environ['GITLAB_TOKEN']
PROJECT_ID = os.environ.get('GITLAB_PROJECT_ID', '1')
WINDOW_DAYS = int(os.environ.get('WINDOW_DAYS', '30'))
TEXTFILE_DIR = os.environ.get('TEXTFILE_DIR', '/textfiles')

API = f'{GITLAB_URL}/api/v4/projects/{PROJECT_ID}'
HEADERS = {'PRIVATE-TOKEN': GITLAB_TOKEN}
SESSION = requests.Session()
SESSION.headers.update(HEADERS)


def get_paginated(path, params=None):
    """Suit la pagination GitLab (per_page=100) jusqu'a la derniere page."""
    results = []
    page = 1
    params = dict(params or {})
    params['per_page'] = 100
    while True:
        params['page'] = page
        resp = SESSION.get(f'{API}{path}', params=params, timeout=30)
        resp.raise_for_status()
        batch = resp.json()
        if not batch:
            break
        results.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    return results


def parse_iso(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def main():
    now = datetime.now(timezone.utc)
    window_start = now - timedelta(days=WINDOW_DAYS)

    # -------------------------------------------------------------------
    # Tags = deploiements. Recuperes en entier (pas seulement la fenetre)
    # car le lead time d'un tag dans la fenetre depend du tag precedent,
    # qui peut lui etre anterieur a la fenetre.
    # -------------------------------------------------------------------
    raw_tags = get_paginated('/repository/tags')
    semver_tags = [t for t in raw_tags if SEMVER_TAG_RE.match(t['name'])]
    semver_tags.sort(key=lambda t: parse_iso(t['commit']['created_at']))

    tags_in_window = [
        t for t in semver_tags
        if parse_iso(t['commit']['created_at']) >= window_start
    ]
    deployment_count = len(tags_in_window)
    deployment_frequency_per_day = deployment_count / WINDOW_DAYS

    # -------------------------------------------------------------------
    # Lead time for changes : pour chaque tag de la fenetre, delta entre sa
    # date et celle de chaque commit livre depuis le tag precedent.
    # -------------------------------------------------------------------
    lead_times = []
    for tag in tags_in_window:
        idx = semver_tags.index(tag)
        if idx == 0:
            continue  # premier tag jamais cree : pas de "precedent" pour comparer
        previous_tag = semver_tags[idx - 1]
        tag_time = parse_iso(tag['commit']['created_at'])

        compare = SESSION.get(
            f"{API}/repository/compare",
            params={'from': previous_tag['name'], 'to': tag['name']},
            timeout=30,
        )
        compare.raise_for_status()
        commits = compare.json().get('commits', [])
        for commit in commits:
            commit_time = parse_iso(commit['created_at'])
            delta = (tag_time - commit_time).total_seconds()
            if delta >= 0:
                lead_times.append(delta)

    lead_time_seconds = sum(lead_times) / len(lead_times) if lead_times else 0.0

    # -------------------------------------------------------------------
    # Change failure rate + MTTR : MR hotfix/* fusionnees vers main.
    # -------------------------------------------------------------------
    merged_mrs = get_paginated(
        '/merge_requests',
        params={'state': 'merged', 'target_branch': 'main'},
    )
    hotfix_mrs = [
        mr for mr in merged_mrs
        if mr['source_branch'].startswith('hotfix/')
        and mr.get('merged_at')
        and parse_iso(mr['merged_at']) >= window_start
    ]
    hotfix_count = len(hotfix_mrs)
    change_failure_rate = (hotfix_count / deployment_count) if deployment_count else 0.0

    restore_times = [
        (parse_iso(mr['merged_at']) - parse_iso(mr['created_at'])).total_seconds()
        for mr in hotfix_mrs
    ]
    mttr_seconds = sum(restore_times) / len(restore_times) if restore_times else 0.0

    write_metrics(
        deployment_count=deployment_count,
        deployment_frequency_per_day=deployment_frequency_per_day,
        lead_time_seconds=lead_time_seconds,
        change_failure_rate=change_failure_rate,
        hotfix_count=hotfix_count,
        mttr_seconds=mttr_seconds,
        status=0,
    )
    print(
        f"OK: {deployment_count} deploiement(s), "
        f"lead time {lead_time_seconds:.0f}s, "
        f"echec {change_failure_rate:.2%}, "
        f"MTTR {mttr_seconds:.0f}s "
        f"(fenetre {WINDOW_DAYS}j)"
    )


def write_metrics(**values):
    """Ecriture atomique (fichier temporaire + rename), comme backup-all.sh."""
    os.makedirs(TEXTFILE_DIR, exist_ok=True)
    now_ts = int(datetime.now(timezone.utc).timestamp())
    status = values.get('status', 1)

    lines = [
        '# HELP dora_deployment_count_total Nombre de deploiements (tags) sur la fenetre',
        '# TYPE dora_deployment_count_total gauge',
        f'dora_deployment_count_total{{project="thetiptop"}} {values.get("deployment_count", 0)}',
        '# HELP dora_deployment_frequency_per_day Deploiements par jour, moyenne sur la fenetre',
        '# TYPE dora_deployment_frequency_per_day gauge',
        f'dora_deployment_frequency_per_day{{project="thetiptop"}} {values.get("deployment_frequency_per_day", 0)}',
        '# HELP dora_lead_time_for_changes_seconds Temps moyen entre un commit et son deploiement',
        '# TYPE dora_lead_time_for_changes_seconds gauge',
        f'dora_lead_time_for_changes_seconds{{project="thetiptop"}} {values.get("lead_time_seconds", 0)}',
        '# HELP dora_change_failure_rate Fraction des deploiements suivis d\'un hotfix (0-1)',
        '# TYPE dora_change_failure_rate gauge',
        f'dora_change_failure_rate{{project="thetiptop"}} {values.get("change_failure_rate", 0)}',
        '# HELP dora_hotfix_count_total Nombre de hotfix/* fusionnes vers main sur la fenetre',
        '# TYPE dora_hotfix_count_total gauge',
        f'dora_hotfix_count_total{{project="thetiptop"}} {values.get("hotfix_count", 0)}',
        '# HELP dora_mean_time_to_restore_seconds Temps moyen entre ouverture et fusion d\'un hotfix',
        '# TYPE dora_mean_time_to_restore_seconds gauge',
        f'dora_mean_time_to_restore_seconds{{project="thetiptop"}} {values.get("mttr_seconds", 0)}',
        '# HELP dora_metrics_window_days Fenetre d\'observation utilisee, en jours',
        '# TYPE dora_metrics_window_days gauge',
        f'dora_metrics_window_days{{project="thetiptop"}} {WINDOW_DAYS}',
        '# HELP dora_metrics_last_run_timestamp_seconds Horodatage Unix du dernier calcul',
        '# TYPE dora_metrics_last_run_timestamp_seconds gauge',
        f'dora_metrics_last_run_timestamp_seconds{{project="thetiptop"}} {now_ts}',
        '# HELP dora_metrics_last_run_status Statut du dernier calcul (0=succes, 1=echec)',
        '# TYPE dora_metrics_last_run_status gauge',
        f'dora_metrics_last_run_status{{project="thetiptop"}} {status}',
        '',
    ]

    fd, tmp_path = tempfile.mkstemp(dir=TEXTFILE_DIR, prefix='dora.prom.')
    with os.fdopen(fd, 'w') as f:
        f.write('\n'.join(lines))
    os.chmod(tmp_path, 0o644)
    os.replace(tmp_path, os.path.join(TEXTFILE_DIR, 'dora.prom'))


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:  # noqa: BLE001 - on veut logguer puis remonter le code d'erreur a Ofelia
        print(f'ERREUR: {exc}', file=sys.stderr)
        write_metrics(status=1)
        sys.exit(1)
