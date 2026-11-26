#!/usr/bin/env python3
"""Registra no catálogo de combustíveis do sandbox local os dados de docs/data/fuel-catalog-br-seed.json.

Usa a conta admin local (scripts/admin_sandbox.py setup) e o MFA real. Só REGISTRA: não revisa, não admite e não repete uma
entrada já registrada (mesmo combustível, parâmetro, gás, escopo, valor, unidade e início de vigência). A revisão é ato humano,
na tela /workspace/platform/fuel-catalog.
Uso: register_fuel_catalog.py [--dry-run]
"""
import argparse
import json
import sys
import time
from pathlib import Path

import admin_sandbox as admin
import demo_sandbox as demo

SEED = Path(__file__).resolve().parent.parent / "docs" / "data" / "fuel-catalog-br-seed.json"
API = "/v1/platform/fuel-catalog"


def body(item):
    out = {k: v for k, v in item.items() if k != "evidenceUrl" and v is not None}
    return out


def key_of(item):
    return (item.get("fuelId"), item.get("kind"), item.get("parameter"), item.get("gas"), item.get("emissionScope"),
            item.get("value") or item.get("nominal") or ("remainder" if item.get("remainder") else None), item.get("unit"),
            item.get("validFrom"), item.get("componentId"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    seed = json.loads(SEED.read_text(encoding="utf-8"))
    entries = seed["entries"]
    if args.dry_run:
        for item in entries:
            print("registraria:", item["fuelId"], item["kind"], item.get("parameter") or item.get("componentId"), item.get("gas") or "",
                  item.get("value") or item.get("nominal") or "resto", item.get("unit") or "")
        print(f"{len(entries)} entradas (nada enviado).")
        return 0
    config = admin.private_config()
    browser = admin.login(demo.credentials(admin.ADMIN_EMAIL, "Administrador CarbonFlow · Sandbox", create=False))
    secret = admin.base64.urlsafe_b64decode(config["secret"] + "=" * (-len(config["secret"]) % 4))
    status, _ = demo.api(browser, "POST", "/v1/compliance/session", {"code": admin.totp(secret, int(time.time()) // 30)})
    if status != 200:
        raise RuntimeError(f"Sessão MFA não aberta: HTTP {status}. Se o código já foi usado, aguarde 30 s e repita.")
    status, catalog = demo.api(browser, "GET", API)
    if status != 200:
        raise RuntimeError(f"Catálogo indisponível: HTTP {status} (a conta tem o papel de responsável pelo catálogo?).")
    existing = {key_of(e) for e in catalog.get("entries", [])}
    created = skipped = 0
    for item in entries:
        if key_of(item) in existing:
            skipped += 1
            continue
        status, answer = demo.api(browser, "POST", API + "/entries", body(item), key=demo.new_key())
        if status not in (200, 201):
            raise RuntimeError(f"Entrada {item['fuelId']}/{item.get('parameter') or item.get('componentId')} recusada: HTTP {status} {answer}")
        created += 1
    print(f"Registradas: {created}; já existiam: {skipped}. Nada foi revisado nem admitido.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, OSError) as error:
        print(f"Erro: {error}", file=sys.stderr)
        sys.exit(1)
