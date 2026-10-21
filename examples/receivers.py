# Copyright 2026 Maretha Solutions
# SPDX-License-Identifier: Apache-2.0
"""Local stand-ins for Splunk HEC, Sentinel, a webhook, syslog and QRadar, recording what they receive."""
import gzip
import json
import os
import socketserver
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OUT = sys.argv[1] if len(sys.argv) > 1 else "."


def record(name, entry):
    with open(os.path.join(OUT, name + ".jsonl"), "a") as f:
        f.write(json.dumps(entry) + "\n")


class Http(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body=b""):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
        text = raw.decode("utf-8")
        headers = {k: v for k, v in self.headers.items()}
        path = self.path.split("?")[0]
        if path == "/services/collector/event":
            if headers.get("Authorization") != "Splunk e2e-token":
                return self.reply(403, b'{"text":"Invalid token","code":4}')
            record("splunk", {"headers": headers, "body": text})
            return self.reply(200, b'{"text":"Success","code":0}')
        if path == "/hook":
            if headers.get("Authorization") != "Bearer e2e-bearer":
                return self.reply(401, b"{}")
            record("webhook", {"headers": headers, "body": text})
            return self.reply(200, b"{}")
        if path == "/tenant-e2e/oauth2/v2.0/token":
            record("sentinel-token", {"body": text})
            return self.reply(200, b'{"access_token":"aad-e2e","expires_in":3600}')
        if path == "/dataCollectionRules/dcr-e2e/streams/Custom-NuxeoAudit":
            if headers.get("Authorization") != "Bearer aad-e2e":
                return self.reply(401, b"{}")
            record("sentinel", {"query": self.path.split("?")[1], "body": text})
            return self.reply(204)
        self.reply(404)


class OctetCounting(socketserver.StreamRequestHandler):
    def handle(self):
        while True:
            length = b""
            while True:
                c = self.rfile.read(1)
                if not c:
                    return
                if c == b" ":
                    break
                length += c
            record("syslog", {"message": self.rfile.read(int(length)).decode("utf-8")})


class Newline(socketserver.StreamRequestHandler):
    def handle(self):
        for line in self.rfile:
            record("qradar", {"message": line.decode("utf-8").rstrip("\n")})


class TcpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


for server in (TcpServer(("127.0.0.1", 15514), OctetCounting), TcpServer(("127.0.0.1", 15515), Newline)):
    threading.Thread(target=server.serve_forever, daemon=True).start()
print("receivers ready", flush=True)
ThreadingHTTPServer(("127.0.0.1", 18088), Http).serve_forever()
