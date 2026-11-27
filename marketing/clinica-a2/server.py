"""Servidor da demo: python3 server.py  ->  http://127.0.0.1:8090"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from agent import Clinica, fmt, responder

HERE = os.path.dirname(os.path.abspath(__file__))
CLINICA = Clinica()
SESSAO = {}


def estado():
    ag = sorted(CLINICA.agendados, key=lambda a: (a["data"], a["hora"]))
    return {
        "agenda": [{**a, "quando": f"{fmt(a['data'])} {a['hora']}"} for a in ag],
        "espera": CLINICA.espera,
        "humano": CLINICA.humano,
        "feed": CLINICA.feed,
        "modo": "live" if os.environ.get("ANTHROPIC_API_KEY") and os.environ.get("DEMO_MODE", "live") == "live" else "offline",
    }


class H(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/api/state":
            return self._send(200, estado())
        if self.path in ("/", "/index.html"):
            with open(os.path.join(HERE, "index.html"), "rb") as f:
                return self._send(200, f.read(), "text/html")
        self._send(404, {"erro": "não encontrado"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        dados = json.loads(self.rfile.read(n) or b"{}")
        if self.path == "/api/chat":
            try:
                texto = responder(CLINICA, SESSAO, str(dados.get("texto", ""))[:1000])
            except Exception as e:  # falha do modelo não derruba a demo
                texto = "Tive um problema técnico, vou chamar a recepção."
                CLINICA.log(f"ERRO: {e}")
            return self._send(200, {"resposta": texto, **estado()})
        if self.path == "/api/reset":
            CLINICA.reset()
            SESSAO.clear()
            return self._send(200, estado())
        self._send(404, {"erro": "não encontrado"})

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    porta = int(os.environ.get("PORT", 8090))
    print(f"Demo em http://127.0.0.1:{porta}  (modo: {estado()['modo']})")
    ThreadingHTTPServer(("127.0.0.1", porta), H).serve_forever()
