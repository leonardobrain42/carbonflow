INSERT INTO app_technical_guide(id,methodology,position,title,activity,guidance,fields)
VALUES (
    'monitoring',
    'VM0038_V1_1',
    30,
    'Plano de monitoramento',
    'MONITORING',
    'Planeje os fluxos ED e EC separadamente. Registros operacionais, cobertura e revisão de lotes pertencem ao período de monitoramento.',
    '[
  {
    "key": "boundary",
    "label": "Fronteira, período previsto e pontos de medição",
    "type": "text",
    "options": []
  },
  {
    "key": "ed",
    "label": "Energia entregue aos veículos (ED): instrumento, versão e unidade",
    "type": "text",
    "options": []
  },
  {
    "key": "ec",
    "label": "Energia consumida pelo sistema (EC): instrumento, versão e unidade",
    "type": "text",
    "options": []
  },
  {
    "key": "calibration",
    "label": "Certificado de calibração e identificação dos instrumentos",
    "type": "text",
    "options": []
  },
  {
    "key": "validUntil",
    "label": "Validade da calibração a conferir",
    "type": "date",
    "options": []
  },
  {
    "key": "frequency",
    "label": "Periodicidade da leitura e unidade de agregação",
    "type": "text",
    "options": []
  },
  {
    "key": "quality",
    "label": "Controles de qualidade, lacunas e reconciliação ED/EC",
    "type": "text",
    "options": []
  },
  {
    "key": "contingency",
    "label": "Procedimento de contingência e evidência a guardar",
    "type": "text",
    "options": []
  },
  {
    "key": "responsible",
    "label": "Responsáveis pela coleta e pela revisão",
    "type": "text",
    "options": []
  }
]'
);
