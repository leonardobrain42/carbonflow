"""Agente de recepção da Clínica Sorriso (DEMO, dados fictícios).

Dois "cérebros" intercambiáveis, mesmas ferramentas:
  - offline: fluxo determinístico, sem internet nem chave. Ideal para gravar o vídeo sem surpresas.
  - live:   usa a API de mensagens da Anthropic com tool calling (defina ANTHROPIC_API_KEY).

Somente biblioteca padrão.
"""
import datetime as dt
import json
import os
import re
import urllib.request

HOJE = dt.date(2026, 11, 27)  # sexta-feira (fixo para a demo ser reproduzível)
SYSTEM = (
    "Você é a recepcionista virtual da Clínica Sorriso (odontologia), simpática e objetiva, "
    "em português do Brasil. Use as ferramentas para consultar e reservar horários; nunca invente horários. "
    "Se houver dor forte, inchaço, sangramento ou trauma, trate como urgência: ofereça o primeiro horário "
    "e chame escalar_humano com um resumo. Não dê diagnóstico nem preço de tratamento. "
    "Antes de agendar, confirme nome e horário escolhido."
)


class Clinica:
    """Estado em memória: agenda, lista de espera e fila para a recepção humana."""

    def __init__(self):
        self.reset()

    def reset(self):
        d1 = HOJE + dt.timedelta(days=1)  # sábado: sem atendimento
        seg = HOJE + dt.timedelta(days=3)
        ter = HOJE + dt.timedelta(days=4)
        self.livres = {
            seg.isoformat(): ["08:30", "09:30", "14:00", "16:00"],
            ter.isoformat(): ["09:00", "10:30", "15:00"],
        }
        # Demo: amanhã (sábado) a clínica tem plantão de urgência até 12h.
        self.livres[d1.isoformat()] = ["09:00", "10:30"]
        self.agendados = [
            {"data": d1.isoformat(), "hora": "11:30", "nome": "Carlos Lima", "proc": "Limpeza", "confirmado": False}
        ]
        self.espera = [{"nome": "Marina Souza", "tel": "(11) 90000-0001", "pref": "qualquer horário de manhã"}]
        self.humano = []
        self.feed = []  # linhas de log das ações do agente (aparecem no painel)

    def log(self, txt):
        self.feed.append(txt)

    # ---------- ferramentas ----------
    def buscar_horarios(self, periodo="qualquer", urgente=False):
        out = []
        for data in sorted(self.livres):
            for h in self.livres[data]:
                if periodo == "manha" and int(h[:2]) >= 12:
                    continue
                if periodo == "tarde" and int(h[:2]) < 12:
                    continue
                out.append({"data": data, "hora": h})
        out = out[:2] if urgente else out[:4]
        self.log(f"buscar_horarios(periodo={periodo}, urgente={urgente}) -> {len(out)} opções")
        return out

    def agendar(self, nome, data, hora, procedimento="Avaliação"):
        if hora not in self.livres.get(data, []):
            self.log(f"agendar({nome}, {data} {hora}) -> HORÁRIO INDISPONÍVEL")
            return {"ok": False, "erro": "horário indisponível"}
        self.livres[data].remove(hora)
        self.agendados.append({"data": data, "hora": hora, "nome": nome, "proc": procedimento, "confirmado": True})
        self.log(f"agendar({nome}, {data} {hora}, {procedimento}) -> OK, evento criado no calendário")
        return {"ok": True, "data": data, "hora": hora}

    def cancelar(self, nome):
        for a in self.agendados:
            if a["nome"].lower() == nome.lower():
                self.agendados.remove(a)
                self.livres.setdefault(a["data"], []).append(a["hora"])
                self.log(f"cancelar({nome}) -> vaga {a['data']} {a['hora']} liberada")
                return self._preencher_vaga(a)
        return {"ok": False, "erro": "agendamento não encontrado"}

    def _preencher_vaga(self, vaga):
        if self.espera:
            p = self.espera.pop(0)
            self.livres[vaga["data"]].remove(vaga["hora"])
            self.agendados.append({"data": vaga["data"], "hora": vaga["hora"], "nome": p["nome"],
                                   "proc": "Limpeza", "confirmado": False})
            msg = f"Oi, {p['nome'].split()[0]}! Abriu uma vaga {fmt(vaga['data'])} às {vaga['hora']}. Quer ficar com ela?"
            self.log(f"lista_de_espera -> mensagem enviada a {p['nome']}: \"{msg}\"")
            return {"ok": True, "vaga_reaproveitada_para": p["nome"], "mensagem": msg}
        return {"ok": True, "vaga_reaproveitada_para": None}

    def escalar_humano(self, resumo, urgencia="normal"):
        self.humano.append({"resumo": resumo, "urgencia": urgencia})
        self.log(f"escalar_humano({urgencia}) -> {resumo}")
        return {"ok": True}


def fmt(data):
    d = dt.date.fromisoformat(data)
    dias = ["segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo"]
    if d == HOJE + dt.timedelta(days=1):
        return "amanhã"
    return f"{dias[d.weekday()]} ({d.strftime('%d/%m')})"


# ----------------------------- cérebro offline -----------------------------
URGENCIA = re.compile(r"dor|doendo|inchad|sangr|quebr|abscesso|infec", re.I)


def cerebro_offline(c: Clinica, sess: dict, texto: str) -> str:
    t = texto.strip()
    st = sess.setdefault("st", {"etapa": "inicio", "urgente": False, "opcoes": [], "escolha": None, "nome": None})

    if re.search(r"cancel", t, re.I):
        nome = st["nome"] or "Carlos Lima"
        r = c.cancelar(nome)
        extra = " Já ofereci a vaga para quem estava na lista de espera. " if r.get("vaga_reaproveitada_para") else " "
        return f"Cancelado, {nome.split()[0]}.{extra}Quando quiser remarcar é só me chamar!"

    if st["etapa"] == "inicio":
        if URGENCIA.search(t):
            st["urgente"] = True
            ops = c.buscar_horarios(urgente=True)
            st["opcoes"], st["etapa"] = ops, "escolher"
            c.escalar_humano("Paciente relata dor/urgência por WhatsApp. Oferecidos os 2 primeiros horários.", "alta")
            linhas = "\n".join(f"{i+1}) {fmt(o['data'])} às {o['hora']}" for i, o in enumerate(ops))
            return ("Sinto muito que esteja com dor. Vou te encaixar o mais rápido possível 💙 "
                    f"Os primeiros horários são:\n{linhas}\nQual prefere? Já avisei nossa equipe.")
        if re.search(r"agend|marcar|consulta|limpeza|hor[aá]rio", t, re.I):
            ops = c.buscar_horarios()
            st["opcoes"], st["etapa"] = ops, "escolher"
            linhas = "\n".join(f"{i+1}) {fmt(o['data'])} às {o['hora']}" for i, o in enumerate(ops))
            return f"Claro! Tenho estes horários:\n{linhas}\nQual prefere?"
        return "Olá! Sou a recepcionista virtual da Clínica Sorriso 🦷 Posso agendar consulta, remarcar ou tirar dúvidas. Como posso ajudar?"

    if st["etapa"] == "escolher":
        m = re.search(r"\b([1-4])\b", t)
        idx = int(m.group(1)) - 1 if m else (0 if re.search(r"primeir|sim|pode|esse|cedo", t, re.I) else None)
        if idx is None or idx >= len(st["opcoes"]):
            return "Não entendi qual horário. Pode responder com o número da opção?"
        st["escolha"], st["etapa"] = st["opcoes"][idx], "nome"
        return "Perfeito! Para confirmar, qual o seu nome completo?"

    if st["etapa"] == "nome":
        st["nome"] = t.title()
        o = st["escolha"]
        proc = "Avaliação de urgência" if st["urgente"] else "Avaliação"
        r = c.agendar(st["nome"], o["data"], o["hora"], proc)
        st["etapa"] = "fim"
        if not r["ok"]:
            return "Esse horário acabou de ser ocupado 😕 Quer que eu busque outro?"
        return (f"Pronto, {st['nome'].split()[0]}! ✅ Agendado para {fmt(o['data'])} às {o['hora']}. "
                "Te aviso 24h e 2h antes. Se precisar cancelar, é só dizer aqui.")

    return "Posso ajudar em mais alguma coisa?"


# ----------------------------- cérebro live (Anthropic) -----------------------------
TOOLS = [
    {"name": "buscar_horarios", "description": "Lista horários livres.",
     "input_schema": {"type": "object", "properties": {
         "periodo": {"type": "string", "enum": ["manha", "tarde", "qualquer"]},
         "urgente": {"type": "boolean"}}}},
    {"name": "agendar", "description": "Reserva um horário.",
     "input_schema": {"type": "object", "required": ["nome", "data", "hora"], "properties": {
         "nome": {"type": "string"}, "data": {"type": "string", "description": "AAAA-MM-DD"},
         "hora": {"type": "string", "description": "HH:MM"}, "procedimento": {"type": "string"}}}},
    {"name": "cancelar", "description": "Cancela agendamento pelo nome e tenta reaproveitar a vaga.",
     "input_schema": {"type": "object", "required": ["nome"], "properties": {"nome": {"type": "string"}}}},
    {"name": "escalar_humano", "description": "Avisa a recepção humana com resumo.",
     "input_schema": {"type": "object", "required": ["resumo"], "properties": {
         "resumo": {"type": "string"}, "urgencia": {"type": "string", "enum": ["normal", "alta"]}}}},
]


def cerebro_live(c: Clinica, sess: dict, texto: str) -> str:
    key = os.environ["ANTHROPIC_API_KEY"]
    model = os.environ.get("DEMO_MODEL", "claude-haiku-5-5")
    msgs = sess.setdefault("msgs", [])
    msgs.append({"role": "user", "content": texto})
    for _ in range(6):  # limite de voltas de ferramenta
        body = json.dumps({"model": model, "max_tokens": 600, "system": SYSTEM + f" Hoje é {HOJE.isoformat()}.",
                           "tools": TOOLS, "messages": msgs}).encode()
        req = urllib.request.Request("https://api.anthropic.com/v1/messages", body, {
            "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as r:
            resp = json.load(r)
        msgs.append({"role": "assistant", "content": resp["content"]})
        usos = [b for b in resp["content"] if b["type"] == "tool_use"]
        if not usos:
            return "".join(b.get("text", "") for b in resp["content"]).strip()
        resultados = []
        for u in usos:
            fn = getattr(c, u["name"])
            resultados.append({"type": "tool_result", "tool_use_id": u["id"],
                               "content": json.dumps(fn(**u["input"]), ensure_ascii=False)})
        msgs.append({"role": "user", "content": resultados})
    return "Desculpe, tive um problema. Vou chamar a recepção."


def responder(c: Clinica, sess: dict, texto: str) -> str:
    if os.environ.get("ANTHROPIC_API_KEY") and os.environ.get("DEMO_MODE", "live") == "live":
        return cerebro_live(c, sess, texto)
    return cerebro_offline(c, sess, texto)
