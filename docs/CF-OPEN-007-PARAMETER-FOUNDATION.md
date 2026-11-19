# CF-OPEN-007 / #96 — Fundação de fatores (fatia 1)

**Não é liberação de fatores brasileiros.** Esta entrega não cria séries oficiais, não conclui VT0011/TOOL07, não aprova parâmetros de projetos reais e não executa E8.

## Dependências

A branch está empilhada sobre PR #111 (CF-OPEN-006/V49); V50 deve ser aplicada após V49. Após merge de #111, retarget desta PR para main e repetir o CI. A implementação reutiliza os contratos de fontes e licenças #92, inventário #90/#91, frota #94 e ratings #95. A seleção real por projeto permanece em #97.

## Entregue

- Migration V50: candidatos tipados EF_GRID, EF_FUEL, IR, DY, por organização, fonte, licença, SHA-256, linha e período; gás e combustível sem misturar domínios, status CANDIDATE/SUSPENDED.
- Registro de parecer interno por escopo e localizador, com trigger que impede o autor de rever o próprio candidato. Parecer interno não significa admissão normativa.
- Trigger para expurgar valores numéricos quando uma licença é REVOKED ou DENIED. Metadados/hashes permanecem para trilha.
- Conversor puro OpenEmissionParameterMath com BigDecimal e unidades estritas: t[gás]/MWh para t[gás]/kWh; kg[gás]/L para t[gás]/USgal usando 3,785411784 L/USgal; outras combinações explícitas, sem inventar GWP, densidade ou mudança CO2→CO2e.
- IR e Dy NÃO têm default nem conversão enquanto não houver regra/vintage/evidências; método retorna sempre usableForE8=false.
- Testes unitários de conversão, tipo, unidade, gases, precisão, overflow, ausência distinta de zero, determinismo e bloqueio de IR/Dy. O CI deve validar Flyway com PostgreSQL.

## Necessário para fechar a issue #96

1. Ingerir uma série brasileira SIN e um fator de combustível reais e licenciados via #92, com unidade, período de publicação/observação e fonte verificável.
2. Elaborar e revisar o caderno OM/EFel sob VT0011 v1.0 / TOOL07 v7.0. Fator MCTI para inventário corporativo não vira automaticamente OM metodológica.
3. Implementar gases CO2/CH4/N2O e equivalências apenas com GWP/densidade e fontes aplicáveis; não misturar energia e combustível.
4. Implementar IR e Dy com sua regra e prova de direitos/double claiming, nunca fixando 1 por conveniência.
5. Criar workbench staff de parecer e API com acesso MFA, segregação de responsabilidades, licença por operação e leitura revogável, além de cobertura mobile.
6. Criar ProjectParameterBinding e snapshots por projeto/vintage/edição, e propagação STALE para #13/#35/#97.
7. Testar alteração de edição, licença expirada/revogada, cross-tenant, versionamento e E8 sem contaminação do PIL-SYN.

A versão atual é uma base preparatória para a próxima fatia e NÃO satisfaz o aceite integral de #96. Ausência de parâmetro permanece pendência, não ER=0.
