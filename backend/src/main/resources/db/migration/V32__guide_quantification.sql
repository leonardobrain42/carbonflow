INSERT INTO app_technical_guide(id,methodology,position,title,activity,guidance,fields)
VALUES (
    'quantification',
    'VM0038_V1_1',
    32,
    'Quantificação e memória de cálculo',
    'CALCULATION',
    'Identifique o recorte e as entradas a submeter ao motor de cálculo. Um fator candidato permanece pendente de admissão.',
    '[
  {
    "key": "periodStart",
    "label": "Início do recorte",
    "type": "date",
    "options": []
  },
  {
    "key": "periodEnd",
    "label": "Fim do recorte",
    "type": "date",
    "options": []
  },
  {
    "key": "vintage",
    "label": "Ano de referência (vintage)",
    "type": "text",
    "options": []
  },
  {
    "key": "boundary",
    "label": "Fronteira e natureza dos dados medidos ou modelados",
    "type": "text",
    "options": []
  },
  {
    "key": "baseline",
    "label": "Emissões da linha de base (BE): parâmetros, unidades e fontes",
    "type": "text",
    "options": []
  },
  {
    "key": "project",
    "label": "Emissões do projeto (PE): energia, fator, unidade e fonte",
    "type": "text",
    "options": []
  },
  {
    "key": "leakage",
    "label": "Fugas (LE): incidência, justificativa e referência",
    "type": "text",
    "options": []
  },
  {
    "key": "factor",
    "label": "Fator de emissão, unidade, geografia, edição e fonte",
    "type": "text",
    "options": []
  },
  {
    "key": "snapshot",
    "label": "Identificador ou hash da memória de cálculo a conferir",
    "type": "text",
    "options": []
  }
]'
);
