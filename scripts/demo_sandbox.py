#!/usr/bin/env python3
"""Provision opt-in CarbonFlow sandbox accounts via the real account API.

Never bypasses email confirmation or auth, never uses a fixed password and never
opens the sandbox on a public interface. Docker Compose namespacing is explicit.
"""
from __future__ import annotations

import argparse
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, Request, build_opener

ROOT = Path(__file__).resolve().parent.parent
SECRET_DIR = ROOT / ".carbonflow-demo"
ACCOUNT_DIR = SECRET_DIR / "accounts"
DEFAULT_EMAIL = "demo@carbonflow.test"
DEFAULT_NAME = "Demonstração CarbonFlow"
INBOX = "/run/carbonflow-demo-inbox"
PROJECT = os.environ.get("CARBONFLOW_DEMO_PROJECT", "carbonflow-demo")
PORT = os.environ.get("CARBONFLOW_PORT", "18088")
EMAIL_RE = re.compile(r"^[a-z0-9][a-z0-9._+-]{0,55}@[a-z0-9.-]+\\.test$")
PROJECT_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,45}$")


def validate() -> None:
    if not PROJECT_RE.fullmatch(PROJECT):
        raise ValueError("CARBONFLOW_DEMO_PROJECT precisa ser um nome Compose local válido.")
    if not PORT.isdigit() or not 1 <= int(PORT) <= 65535:
        raise ValueError("CARBONFLOW_PORT deve estar entre 1 e 65535.")


def compose(*args: str, capture: bool = False) -> str:
    command = [
        "docker", "compose", "-p", PROJECT,
        "-f", str(ROOT / "compose.yaml"),
        "-f", str(ROOT / "compose.demo.yaml"), *args,
    ]
    result = subprocess.run(
        command, cwd=ROOT, check=True,
        capture_output=capture, text=True,
    )
    return result.stdout if capture else ""


def private_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise ValueError(f"Diretório de credenciais sem permissões privadas: {path}")


def normalize_email(email: str) -> str:
    value = email.strip().lower()
    if not EMAIL_RE.fullmatch(value) or ".." in value:
        raise ValueError("Use um e-mail fictício terminado em .test (ex.: demo2@carbonflow.test).")
    return value


def credential_file(email: str) -> Path:
    return ACCOUNT_DIR / (hashlib.sha256(email.encode("utf-8")).hexdigest() + ".json")


def credentials(email: str, name: str, create: bool) -> dict[str, str]:
    email = normalize_email(email)
    if len(name.strip()) == 0 or len(name.strip()) > 120:
        raise ValueError("Nome da conta deve ter entre 1 e 120 caracteres.")
    if create:
        private_dir(SECRET_DIR)
        private_dir(ACCOUNT_DIR)
    path = credential_file(email)
    if path.is_file():
        if path.is_symlink() or path.stat().st_mode & 0o077:
            raise ValueError("Arquivo de credenciais não é privado (permissões 0600 necessárias).")
        saved = json.loads(path.read_text(encoding="utf-8"))
        if saved.get("email") != email or not saved.get("password"):
            raise ValueError("Arquivo de credenciais inválido; não sobrescreva manualmente.")
        return saved
    if not create:
        raise ValueError("A conta ainda não foi preparada. Execute 'start' ou 'add-user'.")
    saved = {"email": email, "name": name.strip(), "password": secrets.token_urlsafe(24)}
    # O_EXCL prevents overwriting a password that may already back a live account.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        json.dump(saved, output, ensure_ascii=False)
        output.write("\n")
    return saved


def api(client, method: str, path: str, payload=None, *, key: str | None = None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if key is not None:
        headers["Idempotency-Key"] = key
    request = Request(
        f"http://127.0.0.1:{PORT}{path}", data=data, method=method, headers=headers,
    )
    try:
        response = client.open(request, timeout=12)
    except HTTPError as exc:
        response = exc
    with response:
        result = response.read()
        try:
            parsed = json.loads(result) if result else None
        except json.JSONDecodeError:
            parsed = None
        return response.status, parsed


def client():
    return build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))


def new_key() -> str:
    return secrets.token_hex(20)


def inbox_tokens(email: str) -> set[str]:
    # No token is written to logs. Read-only inspection of the container's tmpfs.
    command = (
        f'for f in {INBOX}/*.json; do '
        '[ -f "$f" ] || continue; cat "$f"; printf "\\n"; done'
    )
    text = compose("exec", "-T", "app", "sh", "-c", command, capture=True)
    result: set[str] = set()
    for line in text.splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("recipient") == email and message.get("purpose") == "VERIFY_EMAIL":
            if isinstance(message.get("token"), str):
                result.add(message["token"])
    return result


def verified_login(saved: dict[str, str]) -> int:
    browser = client()
    status, _ = api(browser, "POST", "/v1/account-session", {
        "email": saved["email"], "password": saved["password"],
    })
    if status == 204:
        try:
            state, body = api(browser, "GET", "/v1/account-session")
            if state != 200 or body is None or body["identity"]["email"] != saved["email"]:
                raise RuntimeError("Conta autenticada retornou sessão inconsistente.")
            scoped, orgs = api(browser, "GET", "/v1/organizations")
            if scoped != 200 or not isinstance(orgs, list):
                raise RuntimeError("Não foi possível consultar organizações da conta.")
            # A sessão de conta não deve autenticar no piloto SIM-*.
            pilot, _ = api(browser, "GET", "/v1/session")
            if pilot != 401:
                raise RuntimeError("Sessão de conta não está isolada do piloto.")
        finally:
            api(browser, "DELETE", "/v1/account-session")
    return status


def provision(saved: dict[str, str]) -> None:
    first = verified_login(saved)
    if first == 204:
        return
    if first != 401:
        raise RuntimeError(f"Login sandbox indisponível (HTTP {first}); encerre outras sessões se necessário.")
    before = inbox_tokens(saved["email"])
    browser = client()
    status, _ = api(browser, "POST", "/v1/accounts", {
        "email": saved["email"], "name": saved["name"],
    }, key=new_key())
    if status != 202:
        raise RuntimeError(f"Cadastro de teste recusado (HTTP {status}).")
    status, _ = api(browser, "POST", "/v1/accounts/email-verification", {
        "email": saved["email"],
    }, key=new_key())
    if status != 202:
        raise RuntimeError(f"Confirmação de e-mail recusada (HTTP {status}).")
    issued = inbox_tokens(saved["email"]) - before
    if len(issued) != 1:
        raise RuntimeError(
            "Não foi emitido um novo desafio. A conta talvez já exista com outra senha. "
            "Use outro e-mail .test ou reinicie APENAS o volume do projeto Compose de demonstração."
        )
    status, _ = api(browser, "POST", "/v1/accounts/email-confirmations", {
        "token": issued.pop(), "password": saved["password"],
    }, key=new_key())
    if status != 204:
        raise RuntimeError(f"Ativação sandbox recusada (HTTP {status}).")
    if verified_login(saved) != 204:
        raise RuntimeError("A nova conta não conseguiu entrar na API após confirmação.")


def output(saved: dict[str, str], quiet: bool) -> None:
    print(f"CarbonFlow sandbox: http://127.0.0.1:{PORT}/workspace")
    print(f"E-mail: {saved['email']}")
    if not quiet:
        print(f"Senha: {saved['password']}")
    else:
        print("Senha aleatória omitida (--quiet).")
    print("Conta local, sem poderes PP/REV/VVB, sem projeto pré-criado.")
    print(f"Para consultar novamente: python3 scripts/demo_sandbox.py show --email {saved['email']}")


def cli() -> None:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="Gerenciar contas de demonstração local do CarbonFlow.")
    commands = parser.add_subparsers(dest="action", required=True)
    start = commands.add_parser("start", help="Inicia Compose isolado e cria conta sandbox.")
    start.add_argument("--quiet", action="store_true", help="Não mostrar a senha no terminal (para CI).")
    add = commands.add_parser("add-user", help="Cria outra conta local com senha própria.")
    add.add_argument("--email", required=True, help="E-mail fictício .test")
    add.add_argument("--name", default="Conta de demonstração", help="Nome de exibição")
    add.add_argument("--quiet", action="store_true")
    show = commands.add_parser("show", help="Mostra credenciais locais salvas.")
    show.add_argument("--email", default=DEFAULT_EMAIL)
    commands.add_parser("smoke", help="Valida contas locais usando API e cookies reais.")
    commands.add_parser("stop", help="Para apenas o ambiente demo, conservando o volume.")
    args = parser.parse_args()
    validate()
    if args.action == "stop":
        compose("down", "--remove-orphans")
        print("Ambiente demo parado. Volume e credenciais locais preservados.")
        return
    if args.action == "show":
        output(credentials(args.email, DEFAULT_NAME, create=False), quiet=False)
        return
    if args.action == "start":
        print("Subindo ambiente sandbox isolado (somente 127.0.0.1)...")
        compose("up", "--build", "-d", "--wait")
        saved = credentials(DEFAULT_EMAIL, DEFAULT_NAME, create=True)
        provision(saved)
        output(saved, quiet=args.quiet)
        return
    if args.action == "add-user":
        saved = credentials(args.email, args.name, create=True)
        provision(saved)
        output(saved, quiet=args.quiet)
        return
    if args.action == "smoke":
        files = sorted(ACCOUNT_DIR.glob("*.json")) if ACCOUNT_DIR.is_dir() else []
        if not files:
            raise ValueError("Nenhuma conta local salva. Execute 'start' primeiro.")
        for path in files:
            if path.is_symlink() or path.stat().st_mode & 0o077:
                raise ValueError("Permissões inseguras para arquivo de credenciais.")
            saved = json.loads(path.read_text(encoding="utf-8"))
            result = verified_login(saved)
            if result != 204:
                raise RuntimeError(f"Conta de teste {saved['email']} falhou (HTTP {result}).")
            print(f"OK: {saved['email']} (API, sessão e isolamento de cookies)")
        return


if __name__ == "__main__":
    try:
        cli()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError, URLError) as error:
        print(f"Falha ao preparar sandbox: {error}", file=sys.stderr)
        sys.exit(1)
