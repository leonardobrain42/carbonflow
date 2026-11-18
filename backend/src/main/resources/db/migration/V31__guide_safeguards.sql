INSERT INTO app_technical_guide(id,methodology,position,title,activity,guidance,fields)
VALUES (
    'safeguards',
    'VM0038_V1_1',
    31,
    'Salvaguardas e partes interessadas',
    'OWNER',
    'Registre referências controladas dos documentos. Mantenha dados pessoais e relatos individuais no processo de acesso restrito.',
    '[
  {
    "key": "stakeholders",
    "label": "Grupos afetados e direitos envolvidos",
    "type": "text",
    "options": []
  },
  {
    "key": "incidence",
    "label": "Incidência declarada da salvaguarda avaliada",
    "type": "select",
    "options": [
      "A confirmar",
      "Aplicável",
      "Não aplicável"
    ]
  },
  {
    "key": "criterion",
    "label": "Salvaguarda, risco e fonte de referência",
    "type": "text",
    "options": []
  },
  {
    "key": "justification",
    "label": "Justificativa da não aplicabilidade",
    "type": "text",
    "options": []
  },
  {
    "key": "consultation",
    "label": "Data declarada da consulta",
    "type": "date",
    "options": []
  },
  {
    "key": "receipt",
    "label": "Referência do evento e do comprovante de consulta",
    "type": "text",
    "options": []
  },
  {
    "key": "channel",
    "label": "Canal para manifestações e acompanhamento externo",
    "type": "text",
    "options": []
  },
  {
    "key": "mitigation",
    "label": "Medida de mitigação, responsável e prazo",
    "type": "text",
    "options": []
  }
]'
);
