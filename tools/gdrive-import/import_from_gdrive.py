#!/usr/bin/env python3
# Google Drive -> Compliance Logic Import Utility
#
# Workflow:
#   1. rclone copy remote:folder -> /tmp/gdrive-import-<ts>/
#   2. Walk files recursively
#   3. For each file:
#      - compute sha256
#      - POST /documents (multipart form-data)
#      - HTTP 201 -> CREATED
#      - HTTP 409 -> duplicate (skip)
#      - HTTP 5xx -> retry up to 3 times with exponential backoff
#   4. Final report: created/skipped/failed
#
# Idempotency: re-runs are safe; duplicates skipped via sha256 on server.

import argparse
import hashlib
import logging
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional

try:
    import requests
    from tqdm import tqdm
except ImportError:
    print("ERROR: pip install requests tqdm --break-system-packages")
    sys.exit(1)


logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
log = logging.getLogger(__name__)


@dataclass
class ImportStats:
    total: int = 0
    created: int = 0
    skipped: int = 0
    failed: int = 0
    failures: List[str] = field(default_factory=list)


@dataclass
class FileToImport:
    local_path: Path
    rel_path: str
    sha256: str


def compute_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(65536), b''):
            h.update(chunk)
    return h.hexdigest()


def rclone_copy(remote: str, folder: str, local_dir: Path) -> int:
    log.info(f"rclone copy '{remote}:{folder}' -> '{local_dir}'")
    result = subprocess.run(
        ['rclone', 'copy', f'{remote}:{folder}', str(local_dir), '--progress', '--transfers', '4'],
        capture_output=True,
        text=True
    )
    if result.returncode != 0:
        log.error(f"rclone failed: {result.stderr}")
        sys.exit(1)
    count = sum(1 for _ in local_dir.rglob('*') if _.is_file())
    log.info(f"Downloaded {count} files")
    return count


def scan_files(local_dir: Path) -> List[FileToImport]:
    files = []
    log.info(f"Scanning {local_dir}...")
    for path in tqdm(list(local_dir.rglob('*')), desc='Hashing'):
        if not path.is_file():
            continue
        rel = path.relative_to(local_dir)
        sha = compute_sha256(path)
        files.append(FileToImport(local_path=path, rel_path=str(rel), sha256=sha))
    return files


def upload_one(file, base_url, api_key, ca_cert, client_id, doc_type, source, historic, dry_run, max_retries=3):
    if dry_run:
        return f'dry-run: {file.rel_path} ({file.sha256[:8]})'

    url = f'{base_url}/documents'
    headers = {'X-API-Key': api_key}
    data = {
        'client_id': client_id,
        'type': doc_type,
        'source': source,
        'historic': str(historic).lower()
    }

    for attempt in range(1, max_retries + 1):
        try:
            with open(file.local_path, 'rb') as f:
                files = {'file': (file.local_path.name, f, 'application/octet-stream')}
                resp = requests.post(
                    url, headers=headers, data=data, files=files,
                    verify=ca_cert if ca_cert else False,
                    timeout=60
                )

            if resp.status_code == 201:
                return 'created'
            if resp.status_code == 409:
                return 'skipped'
            if resp.status_code >= 500 and attempt < max_retries:
                wait = 2 ** attempt
                log.warning(f"  retry {attempt}/{max_retries} for {file.rel_path} after {wait}s (HTTP {resp.status_code})")
                time.sleep(wait)
                continue
            return f'failed:HTTP_{resp.status_code}:{resp.text[:200]}'

        except requests.RequestException as e:
            if attempt < max_retries:
                wait = 2 ** attempt
                log.warning(f"  retry {attempt}/{max_retries} for {file.rel_path} after {wait}s ({e})")
                time.sleep(wait)
                continue
            return f'failed:exception:{e}'

    return 'failed:max_retries_exhausted'


def bulk_upload(files, args, client_id):
    stats = ImportStats(total=len(files))

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {
            pool.submit(
                upload_one,
                f, args.base_url, args.api_key, args.ca_cert,
                client_id, args.doc_type, args.source, args.historic, args.dry_run
            ): f for f in files
        }

        with tqdm(total=len(files), desc='Uploading') as pbar:
            for future in as_completed(futures):
                f = futures[future]
                result = future.result()

                if result == 'created':
                    stats.created += 1
                elif result == 'skipped':
                    stats.skipped += 1
                elif result.startswith('dry-run'):
                    log.info(result)
                    stats.skipped += 1
                else:
                    stats.failed += 1
                    stats.failures.append(f'{f.rel_path}: {result}')
                    log.error(f"FAIL {f.rel_path}: {result}")

                pbar.update(1)

    return stats


def resolve_client_id(base_url, api_key, ca_cert, client_inn):
    log.info(f"Resolving client_id for inn={client_inn}...")
    resp = requests.get(
        f'{base_url}/clients',
        headers={'X-API-Key': api_key},
        params={'inn': client_inn},
        verify=ca_cert if ca_cert else False,
        timeout=10
    )
    resp.raise_for_status()
    data = resp.json()
    content = data.get('content', [])
    if not content:
        log.error(f"Client with inn={client_inn} not found. Create via POST /clients first.")
        sys.exit(1)
    client_id = content[0]['id']
    log.info(f"Found client_id={client_id} for inn={client_inn}")
    return client_id


def main():
    parser = argparse.ArgumentParser(description='GDrive -> compliance-logic import utility')
    parser.add_argument('--remote', required=True, help='rclone remote name')
    parser.add_argument('--folder', required=True, help='GDrive folder path')
    parser.add_argument('--client-inn', required=True, help='Client INN (must exist in DB)')
    parser.add_argument('--doc-type', default='STATEMENT', choices=['STATEMENT', 'CONTRACT', 'ACT', 'INVOICE', 'OTHER'])
    parser.add_argument('--source', default='BACKFILL_IMPORT', choices=['EMAIL', 'BACKFILL_IMPORT', 'MANUAL'])
    parser.add_argument('--historic', action='store_true', default=True)
    parser.add_argument('--base-url', default='https://127.0.0.1:8771')
    parser.add_argument('--ca-cert', default='/etc/compliance-tls/ca/ca.crt')
    parser.add_argument('--api-key', required=True)
    parser.add_argument('--workers', type=int, default=5)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--keep-temp', action='store_true')
    args = parser.parse_args()

    client_id = resolve_client_id(args.base_url, args.api_key, args.ca_cert, args.client_inn)

    timestamp = time.strftime('%Y%m%d-%H%M%S')
    temp_dir = Path(tempfile.mkdtemp(prefix=f"gdrive-{timestamp}-"'))
    log.info(f"Temp dir: {temp_dir}")

    try:
        rclone_copy(args.remote, args.folder, temp_dir)
        files = scan_files(temp_dir)
        if not files:
            log.warning("Nothing downloaded")
            return

        log.info(f"To upload: {len(files)} files (inn={args.client_inn} type={args.doc_type} source={args.source})")
        if args.dry_run:
            log.info("=== DRY RUN ===")

        stats = bulk_upload(files, args, client_id)

        print("")
        print("=== Import completed ===")
        print(f"  Total:    {stats.total}")
        print(f"  Created:  {stats.created}")
        print(f"  Skipped:  {stats.skipped}")
        print(f"  Failed:   {stats.failed}")
        if stats.failures:
            print("=== Failures (first 10) ===")
            for f in stats.failures[:10]:
                print(f"  - {f}")

    finally:
        if not args.keep_temp:
            import shutil
            shutil.rmtree(temp_dir, ignore_errors=True)
            log.info(f"Removed temp: {temp_dir}")
        else:
            log.info(f"Temp kept (--keep-temp): {temp_dir}")


if __name__ == '__main__':
    main()
