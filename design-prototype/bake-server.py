"""Design-prototype server that can also save baked sky layers (3.12).

Serves this folder like `python -m http.server`, and accepts PUT of .png/.json files under baked/ only,
so bake.html can write the layers it renders. Local use only: binds 127.0.0.1.
Usage: python bake-server.py [port]   (default 8768)
"""
import http.server
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent
BAKED = (ROOT / 'baked').resolve()


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=str(ROOT), **k)

    def do_PUT(self):
        path = (ROOT / self.path.split('?')[0].lstrip('/')).resolve()
        if BAKED not in path.parents or path.suffix not in ('.png', '.json'):
            self.send_error(403, 'only .png/.json under baked/')
            return
        n = int(self.headers.get('Content-Length', 0))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(self.rfile.read(n))
        self.send_response(201)
        self.end_headers()


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8768
    http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()
