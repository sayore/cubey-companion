#!/usr/bin/env python3
"""Idempotently import a QB2-exported public game catalog through the existing NERVE bridge."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalog', type=Path)
    parser.add_argument('--endpoint', default='http://127.0.0.1:17842')
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.endpoint)
    try:
        local = url.hostname == 'localhost' or ipaddress.ip_address(url.hostname).is_loopback
    except ValueError:
        local = False
    if url.username or url.password or url.query or url.fragment or not (url.scheme == 'https' or (url.scheme == 'http' and local)):
        raise ValueError('Use HTTPS or loopback HTTP without URL credentials/query/fragment.')
    token = os.environ.get('QB2_NERVE_TOKEN', '')
    if len(token) < 24:
        raise ValueError('Set QB2_NERVE_TOKEN to the existing bridge token (at least 24 characters).')
    records = json.loads(args.catalog.read_text())
    if not isinstance(records, list) or not records:
        raise ValueError('Empty or invalid catalog.')
    for entry in records:
        if not (isinstance(entry, dict) and str(entry.get('kind', '')).startswith('knowledge_')
                and re.fullmatch('[a-f0-9]{64}', str(entry.get('scope', '')))
                and re.fullmatch('[a-f0-9-]{36}', str(entry.get('id', '')))
                and isinstance(entry.get('text'), str) and len(entry['text']) <= 2000
                and isinstance(entry.get('keywords'), list) and 0 < len(entry['keywords']) <= 1000):
            raise ValueError('Only validated public knowledge records may be imported.')
    opener = urllib.request.build_opener(NoRedirect())
    batch = []
    size = 2
    written = 0
    def send(entries):
        req = urllib.request.Request(args.endpoint.rstrip('/') + '/knowledge',
            data=json.dumps(entries, ensure_ascii=False).encode(),
            headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
        with opener.open(req, timeout=60) as response:
            if not json.load(response).get('ok'):
                raise ValueError('NERVE did not acknowledge the catalog batch.')
    for entry in records:
        length = len(json.dumps(entry, ensure_ascii=False).encode()) + 2
        if length > 100000:
            raise ValueError('Catalog record exceeds transport budget.')
        if batch and (len(batch) == 24 or size + length > 100000):
            send(batch)
            written += len(batch)
            batch = []
            size = 2
        batch.append(entry)
        size += length
    if batch:
        send(batch)
        written += len(batch)
    print(f'PASS: {written} public catalog records imported into NERVE; idempotent IDs preserved.')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except urllib.error.HTTPError as error:
        print(f'Import failed: HTTP {error.code}; no remote response body displayed.')
        sys.exit(1)
    except (OSError, ValueError, TypeError, KeyError):
        print('Import failed: check catalog, endpoint, token and bridge availability. No credentials displayed.')
        sys.exit(1)
