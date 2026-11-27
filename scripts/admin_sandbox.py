#!/usr/bin/env python3
"""Provision an opt-in, local-only staff administrator with TOTP.

This is NOT an internet-facing deployment or a production privilege grant.
Passwords and MFA seeds remain in gitignored 0600 local files. No static credential.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import struct
import sys
import time
import uuid

import demo_sandbox as demo

ADMIN_EMAIL = "admin@carbonflow.test"
CONFIG = demo.SECRET_DIR / "admin-roster.json"
APPROVER_EMAIL = "admin2@carbonflow.test"
APPROVER_CONFIG = demo.SECRET_DIR / "admin2-roster.json"


def private_config(path: Path | None = None) -> dict[str, str]:
    path = path or CONFIG
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
        raise ValueError("Provisionamento ainda não existe ou roster com permissão insegura.")
    config = json.loads(path.read_text(encoding="utf-8"))
    uuid.UUID(config["identity"])
    uuid.UUID(config["organization"])
    raw = base64.urlsafe_b64decode(config["secret"] + "=" * (-len(config["secret"]) % 4))
    if len(raw) != 20:
        raise ValueError("MFA administrativo inválido.")
    return config


def login(saved: dict[str, str]):
    browser = demo.client()
    status, _ = demo.api(browser, "POST", "/v1/account-session", {
        "email": saved["email"], "password": saved["password"]
    })
    if status != 204:
        raise RuntimeError(f"Login da conta admin não foi autorizado: HTTP {status}.")
    return browser


def identity_and_organization(browser) -> tuple[str, str]:
    status, session = demo.api(browser, "GET", "/v1/account-session")
    if status != 200:
        raise RuntimeError("Sessão de administrador não disponível.")
    identity = str(uuid.UUID(session["identity"]["id"]))
    status, organizations = demo.api(browser, "GET", "/v1/organizations")
    if status != 200 or not isinstance(organizations, list):
        raise RuntimeError("Não foi possível consultar a organização da conta.")
    if organizations:
        organization = str(uuid.UUID(organizations[0]["id"]))
    else:
        status, created = demo.api(browser, "POST", "/v1/organizations",
            {"name": "Administração CarbonFlow (sandbox)"}, key=demo.new_key())
        if status != 201:
            raise RuntimeError(f"Não foi possível criar organização sandbox: HTTP {status}.")
        organization = str(uuid.UUID(created["id"]))
    return identity, organization


def setup(email: str = ADMIN_EMAIL, config_path: Path | None = None, name: str = "Administrador CarbonFlow · Sandbox",
          approver: bool = False) -> None:
    config_path = config_path or CONFIG
    demo.validate()
    if approver and not CONFIG.exists():
        raise ValueError("Crie primeiro o admin do responsável: python3 scripts/admin_sandbox.py setup")
    print("Iniciando sandbox isolado em 127.0.0.1 ...")
    # Reuse the image built by demo_sandbox.start; Compose builds it on first use.
    # Avoid a second network-sensitive Maven/Node build on every admin setup.
    demo.compose("up", "-d", "--wait")
    account = demo.credentials(email, name, create=True)
    demo.provision(account)
    browser = login(account)
    try:
        identity, organization = identity_and_organization(browser)
    finally:
        demo.api(browser, "DELETE", "/v1/account-session")

    if config_path.exists():
        existing = private_config(config_path)
        if existing["identity"] != identity or existing["organization"] != organization:
            raise ValueError("Admin anterior não corresponde à conta atual. Recuse sobrescrever silenciosamente.")
        print("Admin já configurado. MFA preservado.")
    else:
        demo.private_dir(demo.SECRET_DIR)
        seed = base64.urlsafe_b64encode(secrets.token_bytes(20)).decode().rstrip("=")
        payload = {"identity": identity, "organization": organization, "secret": seed}
        fd = os.open(config_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(payload, output)
            output.write("\n")
        print("Roster admin local criado com MFA aleatório.")

    # Recreate the process, never its database. Only then Quarkus reads the new roster.
    demo.compose("up", "-d", "--force-recreate", "--wait", "app")
    print(f"Painel: http://127.0.0.1:{demo.PORT}/workspace/platform/sources")
    print(f"Conta: {email}")
    print(f"Senha: python3 scripts/demo_sandbox.py show --email {email}")
    print("Código temporário MFA: python3 scripts/admin_sandbox.py " + ("code-approver" if approver else "code"))
    if approver:
        print("Conta APROVADORA: aprova licenças e decide como revisor independente; não declara nem é responsável (dupla custódia).")
    print("Conta de sandbox; não concede PP, PREP, REV, VVB nem acesso à produção.")


def totp(secret: bytes, counter: int) -> str:
    digest = hmac.new(secret, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 15
    value = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return f"{value % 1_000_000:06d}"


def code(config_path: Path | None = None) -> None:
    config = private_config(config_path)
    seed = config["secret"]
    secret = base64.urlsafe_b64decode(seed + "=" * (-len(seed) % 4))
    remaining = 30 - (int(time.time()) % 30)
    print(f"Código MFA (expira em até {remaining}s): {totp(secret, int(time.time()) // 30)}")
    print("Não compartilhe o código com terceiros.")


def main() -> None:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="Admin de sandbox local com sessão e MFA reais.")
    parser.add_argument("action", choices=("setup", "code", "setup-approver", "code-approver"))
    args = parser.parse_args()
    if args.action == "setup":
        setup()
    elif args.action == "setup-approver":
        setup(APPROVER_EMAIL, APPROVER_CONFIG, "Aprovador CarbonFlow · Sandbox", approver=True)
    elif args.action == "code-approver":
        code(APPROVER_CONFIG)
    else:
        code()


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        print(f"Erro: {error}", file=sys.stderr)
        sys.exit(1)
