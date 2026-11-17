# E8-013 · Preparação do parecer independente

**16/11/2026 · PIL-SYN-BR-001 · kit 0.2.14 · CT-E5-35 pendente.** A designação do revisor, a conferência setorial integral, o parecer assinado e o tratamento de seus achados seguem pendentes.

1. Leonardo designa o revisor competente e independente, combina o escopo e viabiliza acesso aos insumos pertinentes.
2. O revisor usa o [roteiro CT-E5-35](ROTEIRO.md), registra seus resultados no [modelo de parecer](MODELO_PARECER.md) e devolve a versão datada e assinada.
3. REV registra uma decisão, um responsável e o efeito nas issues para cada achado. Vincula a revisão corrigida, o reteste ou a evidência de resolução. Os itens impeditivos aplicáveis precisam de resolução documentada para concluir E8-013 e afetam a liberação dependente em E8-014.

O dossiê usa as evidências já versionadas do piloto. Os caminhos para o kit têm como base a raiz da distribuição 0.2.14 e os links das evidências apontam para arquivos deste repositório. A revisão interna E7, os ensaios na aplicação e o parecer independente preservam escopos e autoria próprios. A decisão operacional com uma amostra autorizada cabe à E8-014. Os pareceres e atos de validação e verificação do VVB têm autoria externa própria.

## Verificação executada

| Caso ou verificação | Esperado | Observado e prova |
|---|---|---|
| Identidade da massa | JSON de origem e massa embarcada idênticos | SHA-256 `cd1dd2b7d9e72a989fc26fbfbf7682f6238ff47701524c25b3aa42d1cdb1e19b` nos dois arquivos. Origem `03_especificacao/e6/exemplos.json`. [Massa embarcada](../../backend/src/main/resources/fixtures/e6-exemplos.json). |
| CT-E7-01 a 08 | Oito grupos de regressão em memória | Oito grupos aprovados, incluindo cortes, guardas de cálculo, V1/I1 em paralelo à coleta M2, autoria, identidade de ato e posição. Reexecução de `03_especificacao/e7/conferir_e7.py`. |
| Oráculo Decimal · CT-E2-14/15 | Usar EF-SYN-v1 a 0,30 tCO₂/MWh e reproduzir as quatro parcelas com Dy=1 no cenário de exceção | Conferidos BE, PE, LE e ER por recorte e período e o hash do snapshot. [Execução Quarkus registrada](../e8-001/execucao.json), [snapshot](../e8-001/snapshot.json) e [resultados por recorte](../e8-001/VERIFICACAO.md). |
| Aplicação Angular e Quarkus · PostgreSQL 16 | Subir ambiente isolado e executar a suíte Chrome sobre banco novo | 12/12 testes em Chrome 157, incluindo os quatro recortes, a coleta de M2 durante V1/I1 e a recusa sem EC. O Compose reutilizou as camadas de compilação de Angular e Quarkus da E8-012. O projeto e o volume de testes foram removidos. |
| Prontidão do piloto principal | Serviços saudáveis | Compose principal saudável e HTTP 204 em `/health/ready`. A demonstração principal recebeu apenas a consulta de prontidão. |

| Período | Recorte em America/Sao_Paulo, fim exclusivo | ER esperado | ER observado |
|---|---|---:|---:|
| M1 | [01/10/2027, 01/01/2028) | 1,80 | 1,80 |
| M1 | [01/01/2028, 01/10/2028) | 4,20 | 4,20 |
| **M1 total** | | **6,00** | **6,00** |
| M2 | [01/10/2028, 01/01/2029) | 1,14 | 1,14 |
| M2 | [01/01/2029, 01/10/2029) | 4,16 | 4,16 |
| **M2 total** | | **5,30** | **5,30** |

Valores em tCO₂e. A grade e os testes usam somente dados sintéticos. A compilação completa e os 26 testes Java estão registrados na [E8-012](../e8-012/VERIFICACAO.md). Esta entrega documental reexecutou a suíte de navegador e os ensaios de especificação e oráculo.

## Reproduzir a conferência

Na raiz deste repositório, informe a localização da cópia autorizada do kit. Os manifestos identificam os arquivos usados para esta revisão.

```sh
KIT="$HOME/Downloads/Kit_de_Especificacao_Plataforma_Carbono"
sha256sum -c evidencias/e8-013/EVIDENCIAS.sha256
(cd "$KIT" && sha256sum -c -) < evidencias/e8-013/KIT.sha256
python3 -B "$KIT/03_especificacao/e7/conferir_e7.py"
python3 -B scripts/conferir-oraculo.py "$KIT" \
  evidencias/e8-001/execucao.json evidencias/e8-001/snapshot.json
```

Para repetir a jornada na interface, use o cenário descrito em [Executar com Docker Compose](../../README.md#executar-com-docker-compose). Para repetir a suíte Chrome, siga [Executar os builds e testes localmente](../../README.md#executar-os-builds-e-testes-localmente) com um projeto Compose isolado.

## Pendências e efeito no backlog

| Referência | Quem fornece a prova | Prova e efeito |
|---|---|---|
| PEN-006 | Leonardo designa. Revisor assina. REV registra o tratamento. | Designação, competência, independência, parecer e resolução documentada de itens impeditivos. E8-013 e o aceite correspondente na E8-014 permanecem pendentes. |
| PEN-009 | Leonardo viabiliza acesso. Revisor registra o alcance e a conclusão. | Texto integral autorizado da ISO 14065:2020 e conferência setorial das cláusulas incidentes. A cobertura setorial e a decisão real dependente ficam abertas. [Catálogo oficial ISO](https://www.iso.org/standard/74257.html) |
| PEN-002/007/012 | PP e revisão técnica | Confirmar datas, edição, meio, conta, poderes, recibo e dados de cada ato ou vintage real. E8-008/009/010 e E8-014 dependem das provas incidentes. |
| PEN-003/005 | OP, PREP e revisão técnica | Autorizar ED/EC de 30 dias com diagrama, fuso, unidade, origem e calibração. Confirmar fronteira, cobertura, par e OM/EF admissíveis. E8-004/005/006 e E8-014 dependem da prova pertinente à importação, ao cálculo e ao período. |
| PEN-004/010 | OP, PP e revisão especializada | Comprovar direitos, inexistência de sobreposição, base do desconto, excedente, requisitos locais e licenças. Os efeitos recaem sobre enquadramento, cálculo, documentos e atos afetados em E8-003/005/007/014. |
| PEN-008 | Equipe técnica mede. Leonardo decide. | Completar a prova de ambiente representativo e decidir metas, hospedagem e responsabilidades conforme E8-012. A homologação pertinente em E8-014 segue condicionada. |
| PEN-011 | PP e revisor | Confirmar representação, geodados, V5#89, escopo/condições do VVB, posição/frações e variantes metodológicas sob incidência. E8-005/007/008/009/010/014 conservam as guardas dos marcos dependentes. |

DEC-012 permite seguir com o piloto sintético. A preparação da E8-014 pode avançar com os checklists e a organização da amostra. O aceite depende da amostra autorizada, das provas por ato, do parecer e das decisões operacionais aplicáveis.
