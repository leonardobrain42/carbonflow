# CF-OPEN-004 — Aplicabilidade OPEN preparatória (primeira fatia da #93)

## Contrato que permite implementar #94–#101 sem bloquear na #92

**Preparação pode continuar; aprovação não é presumida.** O CarbonFlow não deve exigir que todos os fatores, licenças, séries EVR e fontes de normas estejam disponíveis para permitir que PP/PREP cadastrem atividade, infraestrutura, direitos e evidências. O gate separa explicitamente:
- `canContinuePreparation=true`: permite orientar usuários, redigir declarações e preparar documentos;
- `canUseForE8=false`: nenhuma fonte ou declaração não comprovada é admitida ao motor de cálculo real;
- `norm=PENDING_NORMATIVE_REVIEW`: não traduz hipótese VMD ou números históricos em decisão de aplicabilidade/Verra;
- `sourceDependencies`: códigos canônicos da #90/#92, consumidos como pendências; não duplicar inventário.

A estratégia de testes permite fixtures sintéticas em ambientes de QA, **nunca** fallback para projetos reais. Os PRs de #94–#96 podem implementar ingestão, identidade de modelos e calculadoras puras/validações de contrato independentemente da publicação de fontes brasileiras, mantendo o fluxo final `PENDING_OFFICIAL_DATA`.

## Dados versionados
Migração V47 `app_project_open_applicability_revision`:
- declaração para organização/projeto + número da revisão e versão de triagem que a originou;
- acesso aberto a qualquer BEV compatível e restrição ou não a veículos nominados, independentemente da propriedade do terreno;
- DCFC, BEV, fonte SIN, infraestrutura associada, V2G;
- titular do carregador, referência verificável do contrato de titularidade sobre reduções, canal, prova e data de divulgação aos motoristas, risco de dupla reivindicação, data de início;
- autoria, data e histórico imutável, chave idempotente por ator e pedido.

**Não se armazenam os textos dos contratos ou dados de terceiros nesta tabela**: somente referências informadas pelo projeto. Não são provas admitidas pela simples declaração. O mesmo projeto recebe nova revisão quando atualizar informações; alterar uma resposta relevante da triagem ([CF-SPEC-D15](../specification/DECISOES_E_DUVIDAS.md)) marca a declaração anterior `STALE` até reconfirmação; mover o cursor, editar textos narrativos ou salvar uma etapa sem tocar o recorte não a invalidam. Desde D16, rede, acesso, energia e direitos são declarados aqui e não mudam mais pela triagem. O pré-cálculo (gate `OPEN_APPLICABILITY`/`STALE`) usa a mesma regra (`OpenApplicability.stale`).

## API e escopos
- `GET /v1/projects/{project}/technical/open-applicability` (VIEW_OPEN_APPLICABILITY)
- `PUT /v1/projects/{project}/technical/open-applicability` (SAVE_OPEN_APPLICABILITY), exige `Idempotency-Key` e `If-Match`.
- Proprietário de rascunho pode consultar e editar; PREP com grant de atividade APPLICABILITY pode editar; demais papéis sem grant não têm visibilidade do recurso. Todas as ações usam `DynamicProjectScope` e geram auditoria.
- `401` sem sessão, `404` em projeto sem escopo, `412` revisão divergente, `409` reuse de chave para outro payload, `428` falta de versão.
- O usuário encontra a ficha em **Preparação técnica → Analisar** ao usar a jornada VM0038. Não precisa convidar veículo visitante nem fornecer VIN para definir rede aberta.

## Roteamento metodológico

- Rede **OPEN_CANDIDATE** se qualquer BEV compatível pode recarregar e não há restrição a subconjunto nominal. `privateLand=true` não muda a classificação.
- Rede restrita tem `CLOSED_RESTRICTION` — sem aplicar ponderação nacional aos veículos conhecidos.
- A primeira fatia cobre BEV LDV, DCFC, SIN exclusivo, sem geração local, armazenamento ou V2G. Outro ramo produz `OUTSIDE_FIRST_IMPLEMENTED_SCOPE`, mas mantém a ficha e a autoria disponíveis para análise futura.
- Contrato do dono **não substitui** divulgação aos motoristas; falta de canal, data ou prova retorna `DRIVER_DISCLOSURE / NEEDS_EVIDENCE`.
- `NO_OVERLAP_DECLARED` é somente declaração. Risco de dupla reivindicação precisa de verificação jurídica.
- `VMD0049_ROUTE` (lista positiva), `MARKET_ROUTE` (dados oficiais de penetração), `REGULATORY_SURPLUS` e `ED_EC` são gates distintos. A citação de 0,7% de estoque em 2024 na VMD0049 v1.1 Tabela 1 fica abaixo do limiar de 7,5%, e mesmo assim não fecha o teste §4 (1)(b): estoque oficial abaixo de 7,5% e média trienal de vendas abaixo de 30%. Sem essas séries o resultado é `PENDING_OFFICIAL_DATA`. Ausência não vira zero, zero declarado não substitui a série, e um par numérico sem admissão também não fecha. `closesApplicability` e `usableForE8` permanecem falsos.

## Critérios verificados pela implementação
- API e frontend com classificação de rede OPEN mesmo em propriedade privada, dados parciais e indicação explícita de pendências;
- salvar de forma versionada, com If-Match e replays idempotentes;
- resposta relevante nova na triagem (CF-SPEC-D15) marca declaração como stale, sem reescrever histórico;
- status de preparação independente da biblioteca de fontes, E8 explicitamente não liberado;
- autorização restrita ao mesmo projeto e organização;
- fixtures de testes usados apenas na CI, sem alterar o PIL-SYN-BR-001.

## A completar para encerrar a #93
- Guardar e versionar provas públicas de divulgação e direitos com hash e acesso interno autorizado (#33/#92);
- Comparação de data/edição da norma vigente e regimes/renovação (#10);
- Rotas 1(a)/1(b) efetivamente avaliadas com dados oficiais e verificação de vintage/território, preservando a revisão (#92/#94/#99);
- Cross-check jurídico de direitos, contratos e double claiming, alterações de acordo e propagação de stale downstream;
- Validação humana real antes do cálculo E8 ou parecer normativo.

**Esta entrega não representa emissão, elegibilidade aprovada, parecer do VVB ou validação da Verra.**
