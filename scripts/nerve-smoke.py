#!/usr/bin/env python3
"""Real NERVE native write/search/restart test; no Google requests, no production data."""
import json, os, secrets, socket, subprocess, tempfile, time, urllib.request, urllib.error, uuid
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
base = f'http://127.0.0.1:{port}'
token = secrets.token_hex(24)
def post(path, value, authenticated=True):
    req=urllib.request.Request(base+path, data=json.dumps(value).encode(), headers={'Content-Type':'application/json', **({'Authorization':'Bearer '+token} if authenticated else {})})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)
with tempfile.TemporaryDirectory(prefix='qb2-nerve-test-') as directory:
    env={**os.environ, 'QB2_MEMORY_DIR':directory, 'QB2_NERVE_TOKEN':token, 'QB2_NERVE_PORT':str(port)}
    def start():
        proc=subprocess.Popen(['bun', 'bridge/server.ts'], cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for _ in range(100):
            if proc.poll() is not None: raise RuntimeError(proc.stdout.read().decode())
            try:
                post('/search', {'scope':'a'*64,'keywords':['ready'],'topK':3}); return proc
            except (OSError, urllib.error.HTTPError): time.sleep(.05)
        proc.terminate();proc.wait();raise RuntimeError('Bridge startup timeout')
    proc=start()
    try:
        episode={'id':str(uuid.uuid4()),'scope':'a'*64,'kind':'thought','keywords':['minecraft:iron_ingot','processing'],'text':'Iron processed at the press.','createdAt':'2026-11-20T14:00:00Z'}
        assert post('/memories', episode)['ok']
        assert post('/memories', episode)['ok']
        other={**episode, 'id':str(uuid.uuid4()), 'scope':'b'*64, 'text':'Different world.'}
        post('/memories',other)
        query={'scope':'a'*64,'keywords':['minecraft:iron_ingot']+[f'noise_{i}' for i in range(999)],'topK':3}
        assert post('/search',query)==[episode]
        try: post('/search', query, False); raise AssertionError('Unauthenticated access accepted')
        except urllib.error.HTTPError as error: assert error.code==401
        try: post('/search',{**query,'topK':-1}); raise AssertionError('Invalid topK accepted')
        except urllib.error.HTTPError as error: assert error.code==400
        knowledge={**episode, 'id':str(uuid.uuid4()), 'scope':'c'*64, 'kind':'knowledge_recipe', 'text':'Synced iron recipe, partial.'}
        knowledge2={**knowledge,'id':str(uuid.uuid4()),'keywords':['minecraft:diamond'],'text':'Diamond recipe.'}
        assert post('/knowledge',[knowledge,knowledge2])['count']==2
        assert post('/knowledge',[knowledge])['ok']
        try: post('/knowledge',[{**knowledge,'id':str(uuid.uuid4())},episode]); raise AssertionError('Mixed invalid batch accepted')
        except urllib.error.HTTPError as error: assert error.code==400
        assert post('/search',{'scope':'c'*64,'keywords':['minecraft:iron_ingot'],'topK':3})==[knowledge]
        proc.terminate();proc.wait(timeout=10)
        proc=start()
        assert post('/search',query)==[episode], 'Memory lost after bridge restart'
        assert post('/search',{'scope':'c'*64,'keywords':['minecraft:iron_ingot'],'topK':3})==[knowledge]
        assert len(json.loads(Path(directory,'ids.json').read_text()))==4, 'Retry allocated duplicate article'
        print('PASS: real NERVE write, idempotent retry, 1000-keyword search, scope isolation, authentication, validation and restart persistence')
    finally:
        proc.terminate();proc.wait(timeout=10)
