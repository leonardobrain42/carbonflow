# P0.2 — seleção contextual das entradas da quantificação

Refs #89 #94 #95 #96 #97 #98. A listagem de tentativas por período agora contém
currentSelection (OPEN-E8-INPUT-SELECTION@1), uma projeção somente leitura da
seleção padrão atual, não a memória de tentativa histórica nem um parecer.

- Resolve calendário pelo mesmo OpenE8Timing usado no executor. Mostra janela,
  ano relativo, ano de fonte UTC e bloqueio temporal. Não infere ano de M1/M2.
- Usa os seletores latestBinding, latestFleet e pin do executor. Expõe
  identificador, versão, digest e estado do pacote ED/EC/EFel horário, seleção
  EVR/ratings e pinos EFfuel, IR e Dy. Não usa fator elétrico anual como fallback.
- Pinos respeitam exclusivamente o ano da fonte, sem fallback ao mais recente
  de outro ano. Reusa as verificações de valor do ensaio e de atualidade/direitos.
  Dy vem da avaliação estruturada do ano de projeto (`STRUCTURED_PROJECT_YEAR`, V102),
  não de pino; o pino escalar legado deixou de alimentar tentativas novas. Não presume que a avaliação de Dy esteja admitida.
- Metadados sob VIEW_OPEN_E8_RUN e no-store já existentes. Não inclui valores,
  regras/evidências privadas, pesos ou linhas do catálogo. A projeção não
  reproduz todas as checagens de execução nem de segregação do autor.
- A seleção pode mudar entre consulta e tentativa: os IDs congelados da tentativa
  permanecem os do executor, não os desta projeção. Não altera o POST, fingerprint,
  memória histórica, cálculo, permissões, banco ou migrations.
- usableForE8=false, canIssueVcu=false. NOT_ADMITTED significa referência
  preparatória contextual, não aprovação. Testes de seleção por ano, revisão,
  revogação, ausência de calendário, escalar Dy não suportado e ACL existente.

Ainda faltam admissibilidade real, resolução normativa de Dy/IR e homologação
dos fatores, EVR e ratings. Não encerra as issues amplas.
