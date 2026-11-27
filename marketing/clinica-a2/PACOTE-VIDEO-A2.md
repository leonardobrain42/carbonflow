# Pacote de produção · Vídeo A2: "A clínica que parou de perder paciente no WhatsApp"

Entregáveis: (1) versão **vertical 45s** (Reels/Shorts/TikTok + anúncio), (2) versão **YouTube ~6 min**, (3) narração, (4) prompts de imagem/vídeo, (5) legendas, (6) textos de publicação, (7) passo a passo de gravação.

> Regra: no vídeo, a tela do WhatsApp e o painel são **da demo** (dados fictícios). Coloque no rodapé do vídeo: "Demonstração com dados fictícios".

---

## 0. Kit de ferramentas simples e barato (você ainda não escolheu)

| Etapa | Ferramenta sugerida | Para quê |
|---|---|---|
| Gravação de tela | OBS Studio (grátis) ou gravador do próprio sistema | Gravar a demo (`server.py`) |
| Narração | ElevenLabs (voz clonada sua ou voz pronta em pt-BR) | Transformar o texto da narração em áudio |
| Cenas ilustrativas | Veo, Runway ou Kling (use 1, não precisa dos três) | Cenas de recepção cheia, paciente com dor, dono feliz |
| Imagens/thumbnail | Gerador de imagem de sua preferência | Capa e frames |
| Edição + legendas | CapCut | Montagem, legenda automática, zoom |
| Avatar (opcional) | HeyGen | Só se você não quiser aparecer |

Confira preços e termos atuais de cada ferramenta antes de assinar; mudam com frequência.

---

## 1. VERSÃO VERTICAL (45s) · Anúncio + orgânico

Formato 9:16. Alternar **tela da demo (60%)** e **cenas ilustrativas (40%)**.

| Tempo | Imagem | Narração | Legenda na tela |
|---|---|---|---|
| 0-3s | Cena IA: recepção vazia à noite, celular acendendo | "Seu paciente mandou mensagem às onze da noite. Quem respondeu?" | **ÀS 23H, QUEM RESPONDEU?** |
| 3-9s | Demo: paciente digita "tô com um dente doendo, tem como amanhã?" | "Na clínica Sorriso, o agente respondeu em segundos." | Respondeu em segundos |
| 9-17s | Demo: bot oferece 2 horários, paciente escolhe "1" e informa o nome | "Entendeu a urgência, ofereceu os primeiros horários e agendou." | Entende urgência · Agenda sozinho |
| 17-24s | Demo: painel mostra "Aviso para a recepção" em vermelho e feed `agendar -> OK` | "E avisou a equipe humana com o resumo do caso." | Equipe avisada com resumo |
| 24-33s | Demo: digita "cancelar" → feed mostra mensagem para Marina na lista de espera | "Cancelou? A vaga vai direto pra lista de espera. Nenhuma cadeira vazia." | Vaga recuperada automaticamente |
| 33-40s | Cena IA: dentista sorrindo atendendo; agenda cheia | "Agenda, confirmação, reativação. Tudo pelo WhatsApp, 24 horas." | 24h · WhatsApp · sem trocar de sistema |
| 40-45s | Você (ou avatar) olhando pra câmera | "Tem clínica? Chama no WhatsApp e faço um diagnóstico gratuito." | **DIAGNÓSTICO GRATUITO · link na bio** |

Rodapé fixo: *Demonstração com dados fictícios.*

---

## 2. VERSÃO YOUTUBE (~6 min) · Roteiro completo

**Título:** Montei uma recepcionista de IA para clínica (e mostro tudo funcionando)
**Alternativas:** "Clínica perde paciente no WhatsApp? Veja esse agente" · "IA na clínica odontológica: agendamento, urgência e lista de espera"

### Cena 1 · Gancho (0:00-0:20)
🎬 Você em câmera (ou avatar), cena IA de recepção cheia ao fundo.
🎙️ "Toda clínica que eu conheço tem o mesmo problema: o WhatsApp lota, a recepcionista não dá conta, e o paciente que mandou mensagem à noite agenda no concorrente de manhã. Hoje eu vou mostrar um agente de IA que resolve isso, funcionando ao vivo. E no final, o que ele **não** faz, de propósito."

### Cena 2 · O que é um agente (0:20-1:10)
🎬 Slide animado: Chatbot de menu × Agente.
🎙️ "Chatbot de menu é aquele 'digite 1 para agendar'. Ninguém gosta. Um agente entende o que o paciente escreve, consulta a agenda de verdade e **faz** a ação: agenda, cancela, avisa a equipe. Ele tem três coisas: objetivo, ferramentas e limites."
🎬 Diagrama: Paciente → WhatsApp → Agente → [buscar horários · agendar · cancelar · avisar recepção] → Agenda / Lista de espera / Equipe.

### Cena 3 · Demo 1: urgência (1:10-2:40)
🎬 Tela da demo (`http://127.0.0.1:8090`), chat à esquerda, painel à direita.
🎙️ "Olha o painel da clínica. Tem uma agenda, uma lista de espera e um espaço para avisos da recepção. Agora eu sou o paciente."
⌨️ Digite: `Oi, tô com um dente doendo muito, tem como amanhã?`
🎙️ "Reparou? Ele identificou **dor** como urgência. Não ofereceu qualquer horário, ofereceu os dois primeiros. E olha o painel: já apareceu um aviso vermelho para a recepção humana, com resumo."
⌨️ Digite: `1` → depois o nome `João Pereira`.
🎙️ "Ele confirmou nome e horário antes de agendar, e o evento já está na agenda. Embaixo, o registro das ferramentas que o agente usou: buscar horários, avisar a equipe, agendar. Tudo auditável."

### Cena 4 · Demo 2: cancelamento e lista de espera (2:40-3:40)
⌨️ Digite: `cancelar`
🎙️ "Agora o paciente desistiu. Olha o que acontece: a vaga foi liberada e **no mesmo instante** uma mensagem foi enviada para a Marina, que estava na lista de espera. Antes, essa vaga ficava vazia até alguém lembrar de ligar."
🎬 Destaque o feed `lista_de_espera -> mensagem enviada a Marina Souza`.

### Cena 5 · Por trás da demo (3:40-4:40)
🎬 Diagrama da arquitetura + trecho de código (arquivo `agent.py`, função `escalar_humano` e a lista `TOOLS`).
🎙️ "Em produção isso se conecta ao WhatsApp Business oficial, ao Google Agenda ou ao sistema da clínica. O cérebro pode ser um modelo de linguagem, e as ferramentas são funções comuns que checam as regras: não agenda horário ocupado, não dá diagnóstico, não passa preço de tratamento."

### Cena 6 · O que ele NÃO faz (4:40-5:20)
🎙️ "Ele não diagnostica, não promete resultado e não decide caso de dor sozinho: escala para uma pessoa. Dados do paciente seguem a LGPD, com acesso limitado e registro de tudo que foi feito. A recepção continua existindo, mas com tempo para quem está na clínica."

### Cena 7 · O que dá pra ir além (5:20-5:50)
🎙️ "Isso é só o começo. O mesmo sistema confirma consulta 24h antes, pede avaliação no Google depois do atendimento e reativa paciente que sumiu há seis meses."

### Cena 8 · CTA (5:50-6:10)
🎙️ "Se você tem clínica, odonto, estética ou fisio, me chama no WhatsApp. Faço um diagnóstico gratuito e te mostro quantas consultas ficam na mesa hoje. Link na descrição."

---

## 3. TEXTO DE NARRAÇÃO LIMPO (para ElevenLabs)
Cole só o texto falado. Pausas com vírgula, frases curtas. Gere em takes por cena.

**Versão 45s:**
"Seu paciente mandou mensagem às onze da noite. Quem respondeu? Na clínica Sorriso, o agente respondeu em segundos. Entendeu a urgência, ofereceu os primeiros horários e agendou. E avisou a equipe humana com o resumo do caso. Cancelou? A vaga vai direto pra lista de espera. Nenhuma cadeira vazia. Agenda, confirmação, reativação. Tudo pelo WhatsApp, vinte e quatro horas. Tem clínica? Chama no WhatsApp e faço um diagnóstico gratuito."

Configurações: voz masculina ou feminina natural, estabilidade média, velocidade ligeiramente acima do normal (1.05-1.1) para vertical.

---

## 4. PROMPTS DE CENAS IA (vídeo e imagem)

Estilo comum a todos: *"cinematic, soft natural light, modern Brazilian dental clinic, shallow depth of field, realistic, 9:16, no text, no logos"*. Gere clipes de 4-6s.

1. **Recepção vazia à noite:** "Empty modern dental clinic reception at night, a smartphone on the desk lighting up with a WhatsApp-style notification, blue ambient light, slow push-in, cinematic."
2. **Paciente com dor:** "Young Brazilian man at home at night sitting on the couch, holding his cheek in discomfort, typing on his phone, warm lamp light, handheld camera, realistic."
3. **Recepção cheia (problema):** "Busy dental clinic reception, receptionist overwhelmed with phone ringing and patients waiting, slightly chaotic, realistic documentary style."
4. **Dentista tranquila (solução):** "Smiling Brazilian female dentist greeting a patient in a bright modern clinic, calm atmosphere, tablet showing a full schedule, realistic, soft light."
5. **Agenda cheia:** "Close-up of a tablet showing a calendar fully booked with green appointments, hand tapping the screen, clinic desk background, shallow depth of field."
6. **Dono aliviado (fecho):** "Clinic owner, mid-40s, relaxed at his desk looking at a dashboard on a laptop, genuine smile, modern office, golden hour light."

Dica: gere 2-3 variações de cada, escolha a melhor. **Pessoas geradas são ilustrativas**; não as apresente como pacientes reais.

**Thumbnail (16:9):** "Split image: left, frustrated dental receptionist with phone ringing; right, calm clinic with a phone screen showing a WhatsApp chat. Bold space on the right for text, high contrast, no text." Texto sobreposto: **"RECEPÇÃO COM IA"** + seta apontando pro celular. Cores: azul-petróleo e branco.

---

## 5. TEXTO DE PUBLICAÇÃO

**Instagram/TikTok (legenda):**
"Seu paciente mandou mensagem às 23h. Quem respondeu? 🦷
Esse agente de IA atende no WhatsApp, entende urgência, agenda, avisa a equipe e preenche vaga cancelada com a lista de espera.
Demonstração com dados fictícios.
👉 Quer um diagnóstico gratuito para a sua clínica? Link na bio.
#clinicaodontologica #dentista #automacao #inteligenciaartificial #gestaodeclinicas #[suacidade]"

**YouTube (descrição):**
"Mostro ao vivo um agente de IA para clínicas: agendamento no WhatsApp, urgência, cancelamento e lista de espera. Capítulos: 0:00 Gancho · 0:20 O que é um agente · 1:10 Demo urgência · 2:40 Cancelamento · 3:40 Por trás da demo · 4:40 O que ele não faz · 5:50 Como contratar. Diagnóstico gratuito: [link WhatsApp]. *Demonstração com dados fictícios.*"

**Texto de anúncio (Meta Ads):**
- Título: "Sua clínica perde paciente no WhatsApp?"
- Texto: "Atendimento em segundos, 24h, com agendamento e lista de espera automáticos. Faça um diagnóstico gratuito."
- Botão: "Enviar mensagem (WhatsApp)". Público: donos/gestores de clínica na [cidade], raio de 20-30 km.
- Mensagem de boas-vindas: "Oi! Vi seu interesse no agente para clínicas. Qual o tipo da sua clínica e quantas mensagens por dia chegam no WhatsApp?"

---

## 6. COMO GRAVAR A DEMO (10 min)

```bash
cd marketing/clinica-a2
DEMO_MODE=offline python3 server.py      # roteiro previsível, sem internet
# ou, com cérebro de IA de verdade:
ANTHROPIC_API_KEY=... python3 server.py
```
Abra `http://127.0.0.1:8090`, tela cheia, zoom do navegador em 110-125%. Use "Reiniciar demo" antes de cada take. Grave 3 takes: urgência, cancelamento, e um take com mensagem livre (modo live) para mostrar que não é script.

**Importante:** o modo offline é um fluxo fixo para gravação. Se você mostrá-lo como "IA entendendo qualquer frase", seria enganoso. Use o modo live para esse trecho, ou diga que é demonstração do fluxo.

---

## 7. CHECKLIST ANTES DE PUBLICAR
- [ ] "Demonstração com dados fictícios" visível.
- [ ] Nenhum dado real de paciente/clínica na tela.
- [ ] Cenas geradas por IA identificadas como ilustrativas (descrição ou rodapé).
- [ ] Nenhum percentual ou resultado afirmado sem fonte própria (use os da sua primeira clínica-piloto).
- [ ] Link do WhatsApp testado.
- [ ] Menção a LGPD e supervisão humana no vídeo longo.
