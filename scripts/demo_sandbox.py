#!/usr/bin/env python3
"""Provision opt-in CarbonFlow sandbox accounts via the real account API.

Never bypasses email confirmation or auth, never uses a fixed password and never
opens the sandbox on a public interface. Docker Compose namespacing is explicit.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
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
EMAIL_RE = re.compile(r"^[a-z0-9][a-z0-9._+-]{0,55}@[a-z0-9.-]+\.test$")
PROJECT_RE = re.compile(r"^(?:carbonflow-demo|cf-demo)(?:-[a-z0-9][a-z0-9_-]{0,40})?$")


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
    # Keep Docker's published host port and the HTTP client on the same port.
    # Without this override, Compose falls back to 8080 while this script
    # connects to its own default of 18088 (connection refused on first run).
    env = {**os.environ, "CARBONFLOW_PORT": PORT}
    roster = SECRET_DIR / "admin-roster.json"
    if roster.exists():
        if roster.is_symlink() or roster.stat().st_mode & 0o077:
            raise ValueError("Configuração administrativa não é privada (0600).")
        payload = json.loads(roster.read_text(encoding="utf-8"))
        identity = payload["identity"]
        secret = payload["secret"]
        organization = payload["organization"]
        # The roster is never printed to output or added to a committed file.
        import uuid
        import base64
        uuid.UUID(identity)
        uuid.UUID(organization)
        if len(base64.urlsafe_b64decode(secret + "=" * (-len(secret) % 4))) != 20:
            raise ValueError("Credencial MFA local inválida.")
        env.update({
            "CARBONFLOW_COMPLIANCE_STAFF": f"{identity}:{secret}:{organization}",
            "CARBONFLOW_PLATFORM_SOURCE_READINESS_VIEWERS": identity,
            "CARBONFLOW_PLATFORM_SOURCE_WORK_MANAGERS": identity,
            "CARBONFLOW_PLATFORM_SOURCE_INGESTION_REVIEWERS": identity,
            "CARBONFLOW_PLATFORM_SOURCE_TECHNICAL_REVIEWERS": identity,
            "CARBONFLOW_PLATFORM_VEHICLE_RATINGS_REVIEWERS": identity,
            # Only the product-owner roles (D19/D20): this local account can declare a public licence, register and
            # import, and review on the OWNER_INTERNAL basis. It is deliberately NOT an independent reviewer or a
            # licence approver, so it never simulates dual custody.
            "CARBONFLOW_PLATFORM_CONTENT_LICENSES_OWNERS": identity,
            "CARBONFLOW_PLATFORM_FUEL_CATALOG_MANAGERS": identity,
            "CARBONFLOW_PLATFORM_FUEL_CATALOG_OWNERS": identity,
            "CARBONFLOW_PLATFORM_GRID_SERIES_MANAGERS": identity,
            "CARBONFLOW_PLATFORM_GRID_SERIES_OWNERS": identity,
        })
    result = subprocess.run(
        command, cwd=ROOT, check=True,
        capture_output=capture, text=True, env=env,
    )
    return result.stdout if capture else ""



def runtime_check() -> None:
    """Fast, non-destructive checks before attempting any Docker build."""
    if not shutil.which("docker"):
        raise RuntimeError(
            "Docker não encontrado no PATH. Instale Docker Engine/Desktop e o plugin Compose."
        )
    try:
        subprocess.run(["docker", "info", "--format", "{{.ServerVersion}}"],
                       check=True, capture_output=True, text=True, timeout=20)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError(
            "O daemon Docker não está acessível. Inicie o Docker Desktop/Engine e "
            "confira as permissões com 'docker info' (não execute com sudo para contornar "
            "credenciais sem revisar a configuração)."
        ) from exc
    try:
        subprocess.run(["docker", "compose", "version"],
                       check=True, capture_output=True, text=True, timeout=15)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError("O plugin 'docker compose' não está disponível.") from exc


def service_rows() -> list[dict]:
    """Compose 2 emits one JSON object per line; newer builds may emit a JSON list."""
    raw = compose("ps", "--format", "json", capture=True).strip()
    if not raw:
        return []
    try:
        data = json.loads(raw)
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
        if isinstance(data, dict):
            return [data]
    except json.JSONDecodeError:
        pass
    result = []
    for line in raw.splitlines():
        try:
            item = json.loads(line)
            if isinstance(item, dict):
                result.append(item)
        except json.JSONDecodeError as exc:
            raise RuntimeError("Não foi possível interpretar 'docker compose ps --format json'.") from exc
    return result


def local_port_busy() -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as connection:
        connection.settimeout(1)
        return connection.connect_ex(("127.0.0.1", int(PORT))) == 0


def port_owned_by_demo(rows: list[dict]) -> bool:
    for row in rows:
        if row.get("Service") != "app" or row.get("State") != "running":
            continue
        for publisher in row.get("Publishers") or []:
            if not isinstance(publisher, dict):
                continue
            try:
                if int(publisher.get("PublishedPort", -1)) == int(PORT) and \
                        str(publisher.get("URL") or "").strip() in ("127.0.0.1", "::1", "localhost"):
                    return True
            except (TypeError, ValueError):
                continue
    return False


def check_port(rows: list[dict]) -> None:
    if local_port_busy() and not port_owned_by_demo(rows):
        raise RuntimeError(
            f"A porta 127.0.0.1:{PORT} já está ocupada por outro processo ou Compose. "
            f"Use CARBONFLOW_PORT=18089 python3 scripts/demo_sandbox.py start "
            "ou libere a porta; não pare contêineres de outros projetos automaticamente."
        )


def health_status() -> tuple[int | None, str]:
    """Check the loopback-bound app directly without any HTTP proxy."""
    conn = http.client.HTTPConnection("127.0.0.1", int(PORT), timeout=3)
    try:
        conn.request("GET", "/health/ready")
        response = conn.getresponse()
        response.read()
        return response.status, "OK" if response.status == 200 else "HTTP não saudável"
    except (OSError, http.client.HTTPException):
        return None, "sem resposta da API"
    finally:
        conn.close()


def wait_for_health(attempts: int = 10) -> None:
    """Compose's internal HEALTHY may precede host-port availability."""
    for attempt in range(attempts):
        code, _ = health_status()
        if code == 200:
            return
        if attempt < attempts - 1:
            time.sleep(1)
    raise RuntimeError(
        f"O contêiner iniciou, mas a API do host não respondeu com HTTP 200 "
        f"na porta {PORT} após {attempts} verificações. "
        "Execute 'python3 scripts/demo_sandbox.py doctor'."
    )


def doctor() -> bool:
    """Read-only local diagnostic. Never rebuilds, restarts or deletes anything."""
    print(f"CarbonFlow sandbox · projeto {PROJECT} · 127.0.0.1:{PORT}")
    runtime_check()
    rows = service_rows()
    if not rows:
        print("Nenhum serviço deste projeto Compose está ativo.")
    for service in ("db", "app"):
        row = next((item for item in rows if item.get("Service") == service), None)
        if row is None:
            print(f"{service}: não iniciado")
        else:
            print(f"{service}: {row.get('State', 'desconhecido')} · "
                  f"health={row.get('Health') or 'não informado'}")
    ready, summary = health_status()
    print(f"API /health/ready: {summary}" + (f" (HTTP {ready})" if ready else ""))
    busy = local_port_busy()
    if busy and not port_owned_by_demo(rows):
        print(f"ALERTA: porta {PORT} ocupada sem pertencer ao app deste projeto Compose.")
        print("Escolha uma porta livre com CARBONFLOW_PORT=18089.")
    elif not busy and rows:
        print("ALERTA: nenhum processo está aceitando conexões na porta configurada.")
    if ready == 200 and port_owned_by_demo(rows):
        print(f"Sandbox pronto: http://127.0.0.1:{PORT}/workspace")
        return True
    # Inspect only the migration error category; never echo application logs
    # because they may contain confidential diagnostics or access tokens.
    if any(row.get("Service") == "app" for row in rows):
        try:
            recent = compose("logs", "--no-color", "--tail", "160", "app", capture=True)
            matched = re.search(
                r"Migration checksum mismatch for migration version\s+(\d+)", recent
            )
            if matched:
                version = matched.group(1)
                print(f"ERRO FLYWAY V{version}: o arquivo de migração mudou após ser aplicado.")
                if version == "69":
                    print("Correção V69/V69.1 disponível: atualize o código com a migração "
                          "original V69 e a nova V69.1; não execute Flyway repair.")
                elif version == "71":
                    print("V71 divergente no banco persistente. Primeiro faça backup; depois "
                          "inicie apenas o banco e execute 'python3 scripts/demo_flyway_compat.py' "
                          "para comparação segura. O ajuste opt-in usa --apply SOMENTE se "
                          "o esquema for compatível; nunca rode repair genérico.")
                else:
                    print("Não execute repair sem comparar o esquema existente e "
                          "o histórico de migrações com um backup.")
            if "Detected resolved migration not applied to database: 69.1" in recent:
                print("V69.1 foi publicada após versões que este banco já aplicou. "
                      "Atualize o Compose demo com QUARKUS_FLYWAY_OUT_OF_ORDER=true; "
                      "a migração será executada pelo próprio Flyway após resolver V71.")
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError):
            print("Não foi possível ler o diagnóstico das migrações dos logs.")
    print("Se os serviços estiverem ativos mas sem saúde, consulte localmente:")
    print(f"  docker compose -p {PROJECT} -f compose.yaml -f compose.demo.yaml ps")
    print(f"  docker compose -p {PROJECT} -f compose.yaml -f compose.demo.yaml logs --tail 80 app db")
    print("Não execute 'down -v' nem apague .carbonflow-demo: isso destruiria dados ou credenciais.")
    print("Se o erro for na construção Maven/Docker, confira acesso a repositórios "
          "de dependências; se a aplicação iniciar mas não conectar ao banco, "
          "confira CARBONFLOW_DB_PASSWORD persistida no volume.")
    return False

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
            view, _ = api(browser, "GET", "/workspace")
            if view != 200:
                raise RuntimeError("Rota /workspace não respondeu com a interface Angular.")
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
    browser = client()
    status, _ = api(browser, "POST", "/v1/accounts", {
        "email": saved["email"], "name": saved["name"],
    }, key=new_key())
    if status != 202:
        raise RuntimeError(f"Cadastro de teste recusado (HTTP {status}).")
    # The register call itself sends a challenge. Record that token before
    # explicitly requesting a fresh one; only the fresh challenge is usable.
    before = inbox_tokens(saved["email"])
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
    start.add_argument("--reuse-image", action="store_true",
        help="Usa imagem local existente sem rebuild (não incorpora mudanças recentes do código).")
    add = commands.add_parser("add-user", help="Cria outra conta local com senha própria.")
    add.add_argument("--email", required=True, help="E-mail fictício .test")
    add.add_argument("--name", default="Conta de demonstração", help="Nome de exibição")
    add.add_argument("--quiet", action="store_true")
    show = commands.add_parser("show", help="Mostra credenciais locais salvas.")
    show.add_argument("--email", default=DEFAULT_EMAIL)
    commands.add_parser("smoke", help="Valida contas locais usando API e cookies reais.")
    commands.add_parser("doctor", help="Diagnostica Docker, Compose, serviços e porta sem modificar dados.")
    commands.add_parser("stop", help="Para apenas o ambiente demo, conservando o volume.")
    args = parser.parse_args()
    validate()
    if args.action == "doctor":
        if not doctor():
            raise RuntimeError("Sandbox indisponível neste endereço. Verifique os avisos do diagnóstico.")
        return
    if args.action == "stop":
        compose("down", "--remove-orphans")
        print("Ambiente demo parado. Volume e credenciais locais preservados.")
        return
    if args.action == "show":
        output(credentials(args.email, DEFAULT_NAME, create=False), quiet=False)
        return
    if args.action == "start":
        runtime_check()
        check_port(service_rows())
        print("Subindo ambiente sandbox isolado (somente 127.0.0.1)...")
        if args.reuse_image:
            print("Usando imagem local existente. Para incorporar novas mudanças, execute 'start' sem --reuse-image.")
        compose("up", *(["--no-build"] if args.reuse_image else ["--build"]), "-d", "--wait")
        # Never create passwords if the application is not reachable; the database
        # and credentials must remain in sync across failures and upgrades.
        # Compose --wait checks internal readiness; real account registration and
        # login below verify that the published service is actually usable.
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
