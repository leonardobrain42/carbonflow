# CF-ROADMAP-001 — Ordem das entregas: versão revisada (proposta)

**Estado: proposta** para decisão do responsável pelo produto (25/11/2026). Não é decisão nem compromisso de prazo; nenhuma data foi estimada.

**Origem e limite.** Revisa o roteiro de 10 entregas ordenadas (Ordem · Entrega · Issues existentes) visto em captura de tela em 24/11/2026. O roteiro original não está versionado neste repositório, e a captura mostrava só as 10 primeiras linhas (a coluna de issues cortada na linha 2 e uma seta indicando mais conteúdo). Esta proposta cobre **somente essas 10 linhas**; se houver mais, devem ser reconciliadas. Estados e dependências abaixo vêm de [PENDENCIAS](../specification/PENDENCIAS.md), [DECISOES_E_DUVIDAS](../specification/DECISOES_E_DUVIDAS.md), do título das issues e do código atual; onde algo é inferência, está marcado como tal.

## 1. O que muda e por quê
| # | Mudança | Motivo |
|---|---|---|
| M1 | **Dy sai da linha 1 e vai para a linha 2.** | CF-SPEC-D14: o rateio do Dy por EVs em circulação fica bloqueado até o EVR nacional ser admitido (G03, #92/#94/#95). "Completar Dy" não fecha antes da admissibilidade de fontes. |
| M2 | **Cada issue repetida passa a ter a parte que fecha em cada linha** (#96, #97, #98). | Sem isso, não se sabe quando a linha está pronta. A matriz já admite que uma capacidade dependa de várias issues sem duplicar o dono. |
| M3 | **A linha 4 (aplicabilidade, titularidade, adicionalidade) roda em paralelo a 1 a 3**, não depois. | É pré-condição do cálculo (gate `OPEN_APPLICABILITY` do pré-cálculo), depende de outros dados e outras pessoas, e titularidade (PEN-004/010) exige especialista. |
| M4 | **#45 (segurança, acessibilidade, performance, recuperação) sai da linha 10 e vira trilha contínua.** | Qualidade deixada para o fim se acumula; a linha 10 fica só com homologação e piloto. |
| M5 | **Nova trilha T de dependências de terceiros, iniciada já.** | Itens que não dependem de código (amostra real, licenças, especialista, parecer independente, poderes) têm prazo próprio e, se esperarem a vez, travam as linhas 2, 3, 6 e 10. |

## 2. Roteiro revisado
Trilha A é sequencial; trilha B corre em paralelo desde o início; as linhas 7 a 10 dependem das duas.

| Ordem | Trilha | Entrega | Issues (parte que fecha nesta linha) | Pendências que precisa provar |
|---|---|---|---|---|
| 1 | A | Completar IR, vigência e consistência temporal dos parâmetros | #96 (IR), #97 (consistência de IR entre janelas e ano 1) | G05 |
| 2 | A | Completar a admissibilidade das fontes brasileiras, frota nacional, ratings, fatores de emissão **e Dy** | #92, #94, #95, #96 (fatores fósseis, SIN/VT0011 e Dy) | G03, G04; Q02 e Q03 |
| 3 | A | Concluir o motor de quantificação e a consolidação anual | #97 (adaptador E8 e BE/PE/ER), #98 (QA de ED/EC e janela do período) | G04, G06 |
| 4 | B | Completar aplicabilidade, titularidade e adicionalidade | #93, #99, #103 | PEN-004/010 |
| 5 | B | Consolidar a preparação técnica guiada e as dependências entre etapas | #13, #19, #124 | G08 |
| 6 | B | Completar gestão de equipe, representação e convites | #15, #24, #27 | Q06; PEN-002/007/012 |
| 7 | A+B | Elaborar e exportar o PD completo, com rastreabilidade | #43, #122, #128 | G07 |
| 8 | A+B | Consolidar a jornada de monitoramento e a elaboração do MR | #42, #98 (jornada de monitoramento e MR) | G06, G07 |
| 9 | — | Implementar assistência contextual por IA | #100 | D05: IA não é autoridade de regra |
| 10 | — | Homologar o fluxo completo e realizar o piloto autorizado | #101, #48, #49 | G09; PEN-006, PEN-008 |
| C | contínua | Segurança, regressões, acessibilidade, performance e recuperação | #45 | revisitado a cada linha, não só no fim |

Observações:
- **A divisão das issues repetidas (M2) é proposta minha**, a partir dos títulos das issues e de G04/G05/G06; precisa ser confirmada por quem é dono de cada issue.
- A linha 5 continua depois de partes de 1 a 4 porque o grafo real de dependências (#13/#19) só tem o que consumir quando há gates reais; isso é inferência, não consta de documento.
- A linha 9 não bloqueia nada e pode correr a qualquer momento, dentro da regra de fontes autorizadas.
- Linhas 7 e 8 mantêm a ordem original (PD antes do MR).

## 3. Trilha T — dependências de terceiros (iniciar já)
| Item | Referência | Bloqueia | Quem decide ou fornece |
|---|---|---|---|
| Amostra real autorizada de ED/EC (diagrama, fuso, unidades, calibração, cobertura, par e OM/EF) | PEN-003/005, G06 | prova real das linhas 3, 8 e 10 | operador do projeto |
| Estratégia admissível para EVR nacional, lacunas e ratings (decisão de dados e risco) | Q02, #94/#95 | linha 2 e o rateio do Dy | responsável pelo produto |
| Licenças e direitos de uso das fontes | G03, #92, política de fontes (D05) | linha 2 | titulares das fontes |
| Opção OM/TOOL07, gases, vintage e fonte SIN, com revisão competente | Q03, #96 | linhas 2 e 3 | revisão técnica competente |
| Direitos, sobreposição, desconto, excedente e requisitos locais | PEN-004/010 | linha 4 | especialista jurídico/técnico |
| Poderes, representação e convites | Q06, PEN-002/007/012, #15/#24 | linhas 6 e 10 | organizações e representantes |
| Parecer independente e tratamento dos achados; texto autorizado da ISO 14065:2020 | PEN-006, PEN-009 | linha 10 | revisor independente |
| Confirmação externa da interpretação do Dy (anual versus acumulada, ano inicial) | Q05 | linha 2 | especialista ou VVB |

Nenhum desses itens é resolvido por código ou por teste sintético; `PENDENCIAS.md` já exige link de prova, commit/versão e responsável para dar uma pendência como resolvida.

## 4. Decisões que esta proposta pede
1. Aprovar M1 (Dy na linha 2) e a divisão das issues repetidas (M2), com a confirmação dos donos.
2. Aprovar a linha 4 em paralelo (M3), o que supõe haver pessoas diferentes para ela; se for a mesma equipe, a ordem original da trilha B passa a ser sequencial e perde o ganho.
3. Indicar quem é responsável por cada item da trilha T, que hoje não tem dono registrado.
4. Informar se o roteiro tem mais de 10 linhas.

## Direção D17 (25/11/2026)
A [jornada de sete etapas](../specification/CF-JOURNEY-001.md) e o núcleo metodológico agnóstico são aprovados como direção de produto. Esta proposta de ordem de entrega permanece uma sequência técnica do primeiro recorte; não é a sequência universal da jornada nem altera a decisão sobre M1–M5. [Auditoria e lacunas](../specification/CF-JOURNEY-001-AUDITORIA.md). Integrar J04/J05 na trilha B e cobertura documental no marco #122; qualidade e gates contínuos.
