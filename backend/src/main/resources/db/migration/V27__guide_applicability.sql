INSERT INTO app_technical_guide(id,methodology,position,title,activity,guidance,fields)
VALUES (
    'applicability',
    'VM0038_V1_1',
    27,
    'Aplicabilidade e fronteira',
    'INTAKE',
    'Delimite as estações e a frota. Use a referência da evidência para que a revisão consiga localizá-la.',
    '[
  {
    "key": "boundary",
    "label": "Quais estações, veículos e instalações integram a fronteira?",
    "type": "text",
    "options": []
  },
  {
    "key": "plannedDate",
    "label": "Data operacional prevista",
    "type": "date",
    "options": []
  },
  {
    "key": "startEvidence",
    "label": "Documento e evento candidatos a comprovar o início VCS",
    "type": "text",
    "options": []
  },
  {
    "key": "rights",
    "label": "Quem detém os direitos sobre as reduções e com qual instrumento?",
    "type": "text",
    "options": []
  },
  {
    "key": "incidence",
    "label": "Incidência declarada do critério avaliado",
    "type": "select",
    "options": [
      "A confirmar",
      "Aplicável",
      "Não aplicável"
    ]
  },
  {
    "key": "criterion",
    "label": "Critério, fonte e edição consultados",
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
    "key": "evidence",
    "label": "Referência da prova de titularidade e da fronteira",
    "type": "text",
    "options": []
  }
]'
);
