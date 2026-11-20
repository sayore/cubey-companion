import http.server
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
import uuid

ROOT = Path(__file__).resolve().parent.parent


class CatalogImportTest(unittest.TestCase):
    def setUp(self):
        self.received = []
        self.redirected = []
        owner = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                if self.path == '/redirect/knowledge':
                    self.send_response(302)
                    self.send_header('Location', '/leaked')
                    self.end_headers()
                    return
                if self.path != '/knowledge':
                    owner.redirected.append(self.path)
                self.server.received.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                body = b'{"ok":true}'
                self.send_response(200)
                self.end_headers()
                self.wfile.write(body)
            def do_GET(self):
                owner.redirected.append(self.path)
                self.send_response(200)
                self.end_headers()
            def log_message(self, *args): pass
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.received = self.received
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def run_import(self, records, endpoint=None):
        with tempfile.TemporaryDirectory() as folder:
            catalog = Path(folder) / 'catalog.json'
            catalog.write_text(json.dumps(records))
            return subprocess.run(['python3', str(ROOT / 'scripts/import-catalog.py'), str(catalog), '--endpoint', endpoint or self.base],
                env={**os.environ, 'QB2_NERVE_TOKEN': 'private-test-token-1234567890'}, capture_output=True, text=True)

    def record(self):
        return dict(id=str(uuid.uuid4()), scope='a'*64, kind='knowledge_item', keywords=['minecraft:iron_ingot'],
                    text='Iron ingot', createdAt='1970-01-01T00:00:00Z')

    def test_batches_keep_ids_and_exclude_credentials_from_output(self):
        entries = [self.record() for _ in range(49)]
        result = self.run_import(entries)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual([24, 24, 1], list(map(len, self.received)))
        self.assertEqual(entries, [e for batch in self.received for e in batch])
        self.assertNotIn('private-test-token', result.stdout + result.stderr)

    def test_private_episodes_block_entire_import_before_network_access(self):
        result = self.run_import([self.record(), {**self.record(), 'kind': 'conversation'}])
        self.assertEqual(1, result.returncode)
        self.assertEqual([], self.received)

    def test_authenticated_requests_never_follow_redirects(self):
        result = self.run_import([self.record()], self.base + '/redirect')
        self.assertEqual(1, result.returncode)
        self.assertIn('HTTP 302', result.stdout)
        self.assertEqual([], self.redirected)


if __name__ == '__main__':
    unittest.main()
