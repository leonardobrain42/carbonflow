"""Transporte e escopo compartilhados dos ensaios sintéticos da E8-012."""
import json
import os
import re
import subprocess
from http.client import HTTPConnection
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
PROJECT = 'PIL-SYN-BR-001'
IMPORT_PROJECT = 'SIM-PROJETO-INTERNO'
CONTROL_PROJECT = 'SIM-E8-012-CONTROLE'
PROJECTS = (PROJECT, IMPORT_PROJECT, CONTROL_PROJECT)


class Failure(RuntimeError):
    def __init__(self, route, observed, expected, data):
        self.code = data.get('code') if isinstance(data, dict) else None
        super().__init__(f'{route} retornou {observed}, esperado {expected} ({self.code or "sem código"}).')
        self.status = observed


class Session:
    def __init__(self, url, actor, project=PROJECT):
        server = urlsplit(url)
        if server.scheme != 'http' or server.hostname not in ('127.0.0.1', 'localhost') or server.port in (None, 8080):
            raise ValueError('Use uma porta local descartável do Compose.')
        self.http = HTTPConnection(server.hostname, server.port, timeout=35)
        self.actor = actor
        self.project = project
        self.cookie = None

    def login(self):
        _, headers = self.request('/v1/session',
                                  {'identity': 'SIM-' + self.actor, 'password': 'piloto-' + self.actor.lower()},
                                  expected=204, scope=False)
        self.cookie = headers.get('Set-Cookie').split(';', 1)[0]
        return self

    def request(self, route, payload=None, *, version=None, expected=200, headers=None, key=None, scope=True, decode=True):
        path = '/v1/projects/' + self.project + route if scope else route
        outbound = dict(headers or {})
        if self.cookie:
            outbound['Cookie'] = self.cookie
        if payload is not None:
            outbound['Idempotency-Key'] = key or str(uuid4())
        if isinstance(payload, dict):
            outbound['Content-Type'] = 'application/json'
            payload = json.dumps(payload).encode()
        if version is not None:
            outbound['If-Match'] = f'"{version}"'
        self.http.request('GET' if payload is None else 'POST', path, body=payload, headers=outbound)
        response = self.http.getresponse()
        content = response.read()
        result = json.loads(content) if (decode or response.status != expected) and content and 'application/json' in response.headers.get('Content-Type', '') else content
        if response.status != expected:
            raise Failure(route, response.status, expected, result)
        return result, response.headers

    def call(self, *args, **kwargs):
        return self.request(*args, **kwargs)[0]

    def close(self):
        self.http.close()


class Compose:
    def __init__(self, project, files=()):
        if not re.fullmatch(r'carbonflow-e8-012-[a-z0-9-]+', project):
            raise ValueError('Use um projeto Compose descartável carbonflow-e8-012-*.')
        self.project = project
        self.base = ['docker', 'compose', '-p', project, '-f', str(ROOT / 'compose.yaml')]
        for path in files:
            self.base += ['-f', str(Path(path).resolve())]

    def command(self, *args, data=None, check=True):
        return subprocess.run([*self.base, *args], input=data, capture_output=True,
                              text=True, cwd=ROOT, env=os.environ, check=check)

    def sql(self, statement):
        result = self.command('exec', '-T', 'db', 'psql', '-U', 'carbonflow', '-d', 'carbonflow',
                              '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', data=statement, check=False)
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout.strip()

    def container_ids(self):
        return {name: self.command('ps', '-q', name).stdout.strip() for name in ('app', 'db')}


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def save(path, data):
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    temp.replace(path)
