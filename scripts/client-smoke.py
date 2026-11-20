#!/usr/bin/env python3
"""Actual Minecraft + NERVE. Default: HTTP fixture; --live: real Google credentials from .env."""
import argparse
import http.server
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
from live_configuration import LiveConfigurationError, load_live_environment

ROOT = Path(__file__).resolve().parent.parent
REPO = Path(os.environ.get('QB2_NERVE_REPO', '/mnt/lfs/lfs/repos/nerve')).resolve()


class Fixture(http.server.BaseHTTPRequestHandler):
    calls = 0

    def do_POST(self):
        assert self.path == '/models/gemma-4-31b-it:generateContent'
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        context = json.loads(request['contents'][0]['parts'][0]['text'])
        assert all(section in context for section in ['world', 'chat', 'memories'])
        Fixture.calls += 1
        reachable = context.get('reachableTargets') or []
        decision = {'action': 'go_to' if reachable else 'observe', 'target': reachable[0]['key'] if reachable else None,
                    'thought': 'Client test: I observed the nearby blocks and retained this private thought.',
                    'memory': 'The player opened a chest containing thirteen diamonds; this is an explicit QA recollection.',
                    'speech': 'Client integration test: perception, dialogue and NERVE are connected. This is a test fixture.'}
        body = json.dumps({'candidates': [{'content': {'parts': [{'text': json.dumps(decision)}]}}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def private_write(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', action='store_true', help='Use the actual Google API configured in .env.')
    parser.add_argument('--craftoria', type=Path, help='Read-only source Minecraft directory; copied into isolated run-craftoria.')
    parser.add_argument('--catalog-output', type=Path, help='Export only validated catalog records from the test NERVE database.')
    parser.add_argument('--skip-catalog', action='store_true', help='Only client QA; skip catalog completion/recovery assertions (no catalog export).')
    args = parser.parse_args()
    if args.skip_catalog and args.catalog_output:
        parser.error('--skip-catalog cannot export an incomplete catalog')
    server = None
    if args.live:
        try:
            values = load_live_environment(ROOT / '.env')
        except LiveConfigurationError as error:
            print(str(error))
            return 2
        except (OSError, UnicodeError):
            print('Local .env could not be read.')
            return 2
        api_key = values['GOOGLE_API_KEY']
        endpoint = values.get('QB2_API_ENDPOINT', 'https://generativelanguage.googleapis.com/v1beta')
        model = values.get('QB2_MODEL', 'gemma-4-31b-it')
    else:
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        api_key = 'explicit-local-fixture-key'
        endpoint = f'http://127.0.0.1:{server.server_port}'
        model = 'gemma-4-31b-it'
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    run = ROOT / ('run-craftoria' if args.craftoria else 'run-smoke')
    if args.craftoria:
        source = args.craftoria.resolve()
        if not (source / 'mods').is_dir():
            raise RuntimeError('--craftoria must point to an installed Minecraft directory containing mods')
        run.mkdir(exist_ok=True)
        for folder in ['config', 'kubejs', 'defaultconfigs']:
            if (source / folder).exists():
                shutil.copytree(source / folder, run / folder, dirs_exist_ok=True,
                                ignore=shutil.ignore_patterns('qb2*.json', 'qb2*.json.tmp'))
        (run / 'mods').mkdir(exist_ok=True)
        for mod in (source / 'mods').glob('*.jar'):
            if mod.name.startswith('qb2-'): continue
            target = run / 'mods' / mod.name
            if not target.exists(): target.symlink_to(mod)
        # Jade performs extra translation assertions only in the development launcher.
        # Supply the missing label as an isolated QA resource pack; no gameplay data changes.
        labels = run / 'resourcepacks/qb2-qa-labels'
        (labels / 'assets/pipez/lang').mkdir(parents=True, exist_ok=True)
        (labels / 'pack.mcmeta').write_text(json.dumps({'pack': {'pack_format': 34, 'description': 'Isolated QA: missing Pipez/Jade UI label'}}))
        (labels / 'assets/pipez/lang/en_us.json').write_text(json.dumps({'config.jade.plugin_pipez.pipe': 'Pipe'}))
        (run / 'options.txt').write_text('resourcePacks:["vanilla","file/qb2-qa-labels"]\nlang:en_us\n')
        print(f'Isolated Craftoria test: {len(list((run / "mods").glob("*.jar")))} installed mods; original saves untouched.', flush=True)
    config = run / 'config'
    config.mkdir(parents=True, exist_ok=True)
    paths = [config / 'qb2.json', config / 'qb2-secrets.json', config / 'qb2-memory-outbox.json']
    backups = {p: p.read_bytes() if p.exists() else None for p in paths}
    token = secrets.token_hex(24)
    try:
        with tempfile.TemporaryDirectory(prefix='qb2-client-nerve-') as data:
            env = {**os.environ, 'QB2_NERVE_REPO': str(REPO), 'QB2_MEMORY_DIR': data,
                   'QB2_NERVE_PORT': str(port), 'QB2_NERVE_TOKEN': token,
                   'QB2_LIVE_API_TEST': 'true' if args.live else 'false',
                   'QB2_SMOKE_DIR': str(run), 'QB2_WAIT_CATALOG': 'false' if args.skip_catalog else 'true', 'QB2_SMOKE_TIMEOUT': '1800' if args.craftoria else '720', 'QB2_PACK_TEST': 'true' if args.craftoria else 'false'}
            log = ROOT / 'build/client-smoke-bridge.log'
            log.parent.mkdir(exist_ok=True)
            with log.open('w') as output:
                bridge = subprocess.Popen(['bun', 'bridge/server.ts'], cwd=ROOT, env=env,
                                          stdout=output, stderr=subprocess.STDOUT)
                try:
                    paths[2].unlink(missing_ok=True)
                    private_write(paths[0], json.dumps({'provider': 'google', 'endpoint': endpoint, 'model': model,
                                  'nerveEndpoint': f'http://127.0.0.1:{port}', 'autonomous': False, 'debug': True}))
                    private_write(paths[1], json.dumps({'apiKey': api_key, 'nerveKey': token}))
                    with (run / 'smoke-console.log').open('w') as console:
                        result = subprocess.run(['./gradlew', 'runSmokeClient'], cwd=ROOT, env=env,
                                                stdout=console, stderr=subprocess.STDOUT, timeout=2000 if args.craftoria else 900)
                    text = (run / 'smoke-console.log').read_text()
                    markers = ['QB2_SMOKE_TITLE_OK', 'QB2_SMOKE_CHAT_DISPATCHED', 'QB2_SMOKE_WORLD_OK',
                               'QB2_SMOKE_CONFIG_OK', 'QB2_QA_FREE_FLIGHT_OK', 'QB2_QA_GO_TO_OK',
                               'QB2_QA_RETURN_OK', 'QB2_QA_RADIUS_OK', 'QB2_QA_THOUGHT_BUBBLE_OK',
                               'QB2_QA_SPEECH_BUBBLE_OK', 'QB2_QA_FINISHED failures=0',
                               'QB2_QA_INVENTORY_OK', 'QB2_QA_CLOSED_CONTAINER_OK', 'QB2_QA_RECOVERY_OK', 'QB2_QA_STAGE_READY']
                    markers.append('QB2_QA_KNOWLEDGE_SKIPPED' if args.skip_catalog else 'QB2_QA_KNOWLEDGE_OK')
                    for line in text.splitlines():
                        if line.startswith('QB2_'):
                            print(line)
                    assert result.returncode == 0, f'Minecraft smoke run failed; inspect {run}/smoke-console.log'
                    bridge.terminate()
                    bridge.wait(timeout=10)
                    entry = (REPO / 'packages/nerve-bun').as_uri()
                    code = f'const {{Engine}}=await import({json.dumps(entry)});const ids=JSON.parse(await Bun.file("ids.json").text());console.log(JSON.stringify(Object.values(ids).map(id=>JSON.parse(Engine.getArticleText(id)))));'
                    try:
                        records = json.loads(subprocess.check_output(['bun', '-e', code], cwd=data, timeout=120))
                    except (subprocess.SubprocessError, ValueError):
                        preserved = Path(tempfile.mkdtemp(prefix='failed-nerve-', dir=run))
                        shutil.copytree(data, preserved, dirs_exist_ok=True)
                        raise RuntimeError(f'Native NERVE recovery failed. Isolated diagnostic data preserved in {preserved}.') from None
                    kinds = {episode['kind'] for episode in records}
                    if not args.skip_catalog:
                        assert {'knowledge_block', 'knowledge_item', 'knowledge_entity', 'knowledge_recipe'} <= kinds, 'Catalog import incomplete'
                    catalog = [e for e in records if e['kind'].startswith('knowledge_')]
                    if not args.skip_catalog:
                        assert any('minecraft:iron_ingot' in e['keywords'] and e['kind'] == 'knowledge_recipe' for e in catalog)
                    print(f"Recovered {len(catalog)} public catalog records; catalog completion assertion {'skipped' if args.skip_catalog else 'passed'}.")
                    if args.catalog_output:
                        args.catalog_output.parent.mkdir(parents=True, exist_ok=True)
                        args.catalog_output.write_text(json.dumps(catalog, ensure_ascii=False))
                        print('Exported public catalog facts; no conversations, thoughts, or credentials.')
                    assert all(marker in text for marker in markers), 'At least one client QA check failed.'
                    if args.live:
                        assert 'api=ready' in text, 'Real Google inference did not succeed. Client QA completed independently.'
                        assert {'conversation', 'observation'} <= kinds and kinds & {'thought', 'speech'}, 'Live cognition memories missing.'
                        print('PASS: real Google inference, validated client output, NERVE recovery and extended client QA.')
                    else:
                        assert Fixture.calls >= 1 and {'thought', 'speech', 'reflection', 'conversation', 'observation'} <= kinds
                        print('PASS: controlled HTTP model fixture, real NERVE recovery and extended client QA. No real Google call in this mode.')
                finally:
                    if bridge.poll() is None:
                        bridge.terminate()
                        bridge.wait(timeout=10)
    finally:
        for path, backup in backups.items():
            if backup is None:
                path.unlink(missing_ok=True)
            else:
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, 'wb') as output:
                    output.write(backup)
        if server is not None:
            server.shutdown()
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (AssertionError, subprocess.TimeoutExpired) as error:
        print('FAIL:', str(error) if isinstance(error, AssertionError) else 'Client test timed out.')
        raise SystemExit(1)
