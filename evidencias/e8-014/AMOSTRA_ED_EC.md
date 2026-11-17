# Amostra autorizada de ED/EC · CT-E5-29

PIL-SYN-BR-001 · E8-014 · kit 0.2.14 · responsável pela origem e autorização **OP** · conferência **REV**.

## O que o operador entrega

| Item | Evidência a registrar |
|---|---|
| Autorização | Responsável e titular da fonte, referência do ato de autorização, escopo da amostra, finalidade de homologação, destinatários autorizados, condições de uso e vigência. Indicar onde obter os arquivos com acesso controlado. |
| Janela e origem | 30 dias representativos do mesmo período de monitoramento, com início e fim exclusivo, zona, sistemas de origem, exportador, data de extração, nomes dos arquivos e contagem de registros. Indicar a frequência e se a energia é por intervalo, sessão ou contador acumulado. |
| ED e EC | ED entregue na recarga, com estação, carregador, sessão/veículo elegível e medição correspondente. EC do lado da rede, com circuito, instalação e medidor. Informar identificador estável da leitura, início, fim exclusivo, unidade e deslocamento explícito. Apontar lacunas, zeros observados, reinícios de contador e alterações na exportação. |
| Diagrama e inventário | Diagrama datado que relacione pontos de ED e EC, medidores, circuitos e fronteira. Identificar instalações, dispositivos, revisões e datas de troca que incidem na amostra. |
| Calibração | Identificador do instrumento, certificado ou verificação, período de validade, responsável e evidência de substituição ou descontinuidade quando houver. Vincular à janela de uso de cada revisão. |
| Perfil e controle independente | Dicionário da exportação com chaves, formato, unidades, zona, significado de zeros e ausências e mudanças de versão. OP fornece referência ou extrato de controle obtido da fonte. REV confere a transformação e monta o esperado com base nessa fonte. |

O CSV aceito pela demonstração usa UTF-8, vírgulas, um registro por linha, timestamp com deslocamento e energia em Wh, kWh ou MWh. Para ver o cabeçalho e os nomes das ações, consulte [Importação ED/EC](../../README.md#demonstrar-importação-e-revisão-edec). Se o operador exportar outro formato ou contador acumulado, preserve o original e trate o perfil como dependência da homologação. Documente a transformação, sua versão, os hashes de entrada e saída e o vínculo entre cada linha e sua origem.

A API atual aceita a origem e a autorização sintéticas fixadas no piloto. Antes de importar a amostra operacional, a equipe precisa disponibilizar um ambiente de homologação que registre e valide a origem, a autorização e o perfil aprovados para essa amostra. Use na demonstração somente massas sintéticas.

## Como homologar a importação

1. **OP** identifica o projeto e o período, entrega a amostra pelo meio autorizado e registra o SHA-256 dos brutos, suas contagens e as referências do diagrama, inventário e calibração. A equipe registra build, esquema, revisão do inventário e versão do perfil no ambiente habilitado para essa amostra.
2. **REV** prepara o resultado esperado a partir do bruto e do controle do operador antes de comparar a saída do sistema. Relaciona cada linha à chave de origem, canal e instrumento, instante local e UTC, unidade original, energia esperada em kWh, recorte de ano-calendário e classificação esperada. ED e EC têm chaves, grandezas e totais próprios. Cortes usam intervalos semiabertos e segmentos medidos quando exigidos.
3. **OP** importa. **PREP** propõe decisões e **REV** admite ou registra os impedimentos. Compare original recuperado e hash, totais e motivos da prévia, versão admitida e seleção salva. Confira 100% das linhas e registre diferenças de chave, conversão, corte, situação ou quantidade. Guarde à parte as correções e seus vínculos com as versões anteriores.
4. Reenvie o mesmo bruto e confira o efeito único. Inclua um arquivo inválido ou a mudança de perfil versionada prevista no CT-E5-29. Para arquivo inválido, confira o bruto preservado, o motivo e a ausência de efeito sobre a seleção. Para perfil alterado, confira a versão exata e a reconciliação com o original. Reproduza os casos negativos em ambiente autorizado e isolado.
5. **OP e REV** confirmam a origem, a metrologia e a conciliação. O aceite exige classificação e explicação para todas as linhas e resolução das diferenças impeditivas. A amostra comprova o escopo de importação testado. A cobertura integral de M1 ou M2 exige conferência própria por todos os canais na janela fechada.

## Registro de conciliação a preencher

| Origem, arquivo e linha | Chave, canal/medidor e revisão | Início/fim, zona e recorte | Quantidade e unidade originais | kWh esperado | kWh observado | Classificação esperada e observada, decisão e motivo |
|---|---|---|---:|---:|---:|---|
| | | | | | | |

| Recorte, classe ED/EC e canal | Linhas recebidas e classificadas | Admitidas | Pendentes, inválidas ou descartadas com motivo | Total esperado e observado em kWh | Diferença, prova e responsável |
|---|---:|---:|---|---|---|
| | | | | | |

Registre a autorização, IDs e versões dos lotes, perfil, seleção, referências de download restrito, resultado de reenvio e do caso negativo, divergências e seus responsáveis. OP e REV registram nome, data, conclusão e escopo da amostra. Provas protegidas ficam no ambiente de acesso controlado com referência no relatório. O caso completo consta em `03_especificacao/e5/ACEITE_E_SAIDA.md` e a preparação de campo em `03_especificacao/e4/IMPORTACAO_E_CONCILIACAO.md` do kit.
