INSERT INTO app_technical_guide(id,methodology,position,title,activity,guidance,fields)
VALUES (
    'additionality',
    'VM0038_V1_1',
    29,
    'Adicionalidade e investimento',
    'ADDITIONALITY',
    'Organize a cronologia antes de propor uma rota para revisão. Contratos anteriores à consideração da receita de carbono exigem análise do contexto.',
    '[
  {
    "key": "route",
    "label": "Rota candidata",
    "type": "select",
    "options": [
      "Incidência a verificar",
      "Lista positiva VMD0049 a verificar",
      "Demonstração por projeto a revisar"
    ]
  },
  {
    "key": "market",
    "label": "Mercado, classe de veículos e recorte temporal",
    "type": "text",
    "options": []
  },
  {
    "key": "source",
    "label": "Fonte, edição e referência da incidência proposta",
    "type": "text",
    "options": []
  },
  {
    "key": "considered",
    "label": "Primeira consideração documentada da receita de carbono",
    "type": "date",
    "options": []
  },
  {
    "key": "decision",
    "label": "Data da decisão de investimento",
    "type": "date",
    "options": []
  },
  {
    "key": "commitment",
    "label": "Data do primeiro compromisso vinculante",
    "type": "date",
    "options": []
  },
  {
    "key": "contract",
    "label": "Contrato, ata ou documento e referência de acesso restrito",
    "type": "text",
    "options": []
  },
  {
    "key": "analysis",
    "label": "Relação entre os eventos e questões para revisão independente",
    "type": "text",
    "options": []
  },
  {
    "key": "responsible",
    "label": "Responsável por obter as provas pendentes",
    "type": "text",
    "options": []
  }
]'
);
