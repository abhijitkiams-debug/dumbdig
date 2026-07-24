#!/usr/bin/env python3
"""Tiny static server for the demo that NEVER lets the browser cache assets.

`python3 -m http.server` lets browsers heuristically cache index.html and the
JS bundle, so after a code change a device can keep running stale JavaScript
(the classic "I fixed it but the phone still shows the old bug"). This server
sends `Cache-Control: no-store` on every response, so each load fetches the
current files. Use it instead of the plain http.server:

    python3 serve.py 8000
"""
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class NoCacheHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
        self.send_header('Pragma', 'no-cache')
        self.send_header('Expires', '0')
        super().end_headers()

    def log_message(self, fmt, *args):  # quieter logs
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    httpd = ThreadingHTTPServer(('0.0.0.0', port), NoCacheHandler)
    print('Serving %s on http://0.0.0.0:%d (no-store, cache disabled)' % ('.', port))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        httpd.shutdown()
