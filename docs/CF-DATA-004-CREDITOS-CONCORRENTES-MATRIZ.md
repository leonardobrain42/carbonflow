# CF-DATA-004 — Matriz de créditos concorrentes (Dy): o que pesquisar, onde e o que ainda falta

**Nível D17:** plataforma/dados (programas e registros externos) com efeito no método VM0038 v1.1, Eq. (7) e (8). **Estado: dossiê de leitura. Não admite fonte, não habilita cálculo, não define o universo de programas, não cria dado, migração nem código.** Redigido em 25/11/2026. Complementa [CF-OPEN-007-DECISION-Q04-Q05](CF-OPEN-007-DECISION-Q04-Q05.md), [CF-OPEN-007-DY-Q05-POLICY](CF-OPEN-007-DY-Q05-POLICY.md) e [CF-OPEN-007-DY-ASSESSMENT](CF-OPEN-007-DY-ASSESSMENT.md); não repete o que já está lá.

## 1. O que é a "matriz" no produto hoje
Já existe, como registro declarado (V84, V86, V99, V101, V102; `OpenDySearchCoverage`). Por avaliação de Dy, para cada **programa** declarado, duas linhas, **ERC** e **ERF**, cada uma com: abrangência (`NATIONAL_BR` ou `PARTIAL_BR`), resultado (`FOUND`, `NO_RESULTS`, `INCOMPLETE`), janela pesquisada (início inclusivo, fim exclusivo), data da consulta, fonte e edição, localizador e limitações. O sistema **exige** que, para uma conclusão, todas as linhas sejam nacionais, completas e cobrindo exatamente a janela anterior do projeto; um resultado positivo exige emissão individualizada e negativo não pode conter emissão. **Isso valida coerência; não prova que o universo de programas está completo.**

## 2. O que a metodologia pede (já verificado em Q05)
- Dy = ERC / (ERC + ERF); ERC e ERF são créditos emitidos "no ano y−1" (texto), mas a medição é descrita como total do ano 1 a y−1 (acumulado). A interpretação está pendente de confirmação externa; a política D14 lê o **anual** e registra o acumulado sem usá-lo.
- Para DCFC o recorte do projeto é **nacional**.
- Sem créditos de frota na região, Dy = 1; se cobrirem região maior, rateio razoável (D14: por EVs em circulação, **bloqueado** até o denominador nacional de EVR ser admitido, G03).

## 3. Quais programas entram na matriz
**O universo de programas não está decidido, e este dossiê não o inventa.** O que se pôde apurar de fonte primária e o que falta:

| Candidato | O que se sabe | O que falta |
|---|---|---|
| Verra (VCS), metodologia de veículos elétricos | A Verra publicou a [VMR0014](https://verra.org/methodologies/vmr0014-electric-and-hybrid-vehicles-and-mobile-machinery-ams-iii-c-revision-v1-0/), revisão da AMS-III.C ativa desde 3/jul/2025, que usa a VT0008 e a VT0011; a AMS-III.C v16 como metodologia VCS autônoma é inativada em 1/ago/2026 e projetos nela tinham de concluir a validação até 31/jul/2026 ([aviso da Verra](https://verra.org/verra-publishes-revision-to-cdm-methodology-for-electric-and-hybrid-vehicles/)) | Confirmar quais metodologias VCS geram créditos **de frota** concorrentes (AMS-III.C v16, VMR0014, outras) e a busca no registro da Verra, com seus termos de uso |
| MDL (UNFCCC), AMS-III.C | A AMS-III.C é metodologia do MDL, origem declarada do parâmetro IR | Se ainda há atividades registradas/emitindo no Brasil e como consultar |
| Gold Standard | **Nada apurado**: a busca não trouxe resultado sobre métodos de frota elétrica nem sobre o registro | Verificar a biblioteca de metodologias e o registro |
| Outros (programas brasileiros, voluntários, jurisdicionais) | **Nada apurado** | Decisão de quem fixa o universo |

Cada programa que o produto declarar tem de ter as duas linhas, ERC e ERF; um programa esquecido não é detectável pelo sistema. Por isso a lista de programas deve ser **decisão registrada**, com data e revisão, e não um padrão embutido.

## 4. Matriz de lacunas
| Ponto | Situação | Quem fecha |
|---|---|---|
| Universo de programas e metodologias de frota | Não definido; só a Verra/MDL têm candidatos apurados | Responsável do produto, com revisão metodológica |
| Janela: anual (y−1) × acumulada (1 a y−1) | Política D14 lê a anual; o acumulado é registrado sem entrar no cálculo; a matriz nacional **não** é estendida à janela acumulada | Confirmação externa (Verra/VVB) |
| Ano 1 | Só ausência comprovada na janela exploratória de 12 meses e parecer independente; ausência declarada não é prova | Política D14 (já implementada) |
| Território: DCFC nacional × créditos de frota em região maior | Rateio por EVs em circulação **bloqueado** até o denominador nacional (G03, #94/#95); nenhuma fração é estimada | G03 / Q02 |
| Titularidade e direitos do projeto (para saber o que é "concorrente") | Não coberto por esta matriz | Revisão jurídica (G05/direitos) |
| Licença e termos de uso dos registros consultados | **Desconhecidos** para cada registro; a tela de licenças existe e nenhuma licença foi registrada. Emissão exige fonte com edição e licença organizacional vigente | Quem consulta cada registro |
| Evidência de cada consulta: data, filtros, captura | Hoje é texto declarado (localizador); sem captura nem hash obrigatórios | Produto, se quiserem endurecer |
| Exaustividade | Não demonstrável pelo sistema; resultado negativo vale só como "não encontrado na consulta declarada" | Revisão independente |
| Mudança de edição do registro ou da regra do programa | A matriz guarda fonte e edição; o que fazer ao mudar (reabrir a avaliação) segue a regra de obsolescência existente | Já coberto para licença/calendário |

## 5. Consequências para o produto (sem alterar nada agora)
1. Nenhum "default" de programas: a matriz continua vazia até alguém declarar a pesquisa.
2. Antes de qualquer conector a um registro, é preciso a licença e a decisão do universo; um conector sem isso produziria dado sem regra.
3. A leitura anual permanece a vigente; o acumulado continua informativo.
4. O ponto que mais bloqueia o Dy real **não é código**: é o universo de programas, a confirmação anual × acumulada e o denominador nacional de EVR.

## 6. Decisões que não são minhas
- **Universo de programas e metodologias de frota** a pesquisar (e quem revisa esse universo).
- **Confirmação anual × acumulada** com a Verra/VVB (Q05).
- **Licenças** dos registros (quem consulta e registra).
- Se vale endurecer a evidência da consulta (captura e hash).

## 7. Não coberto
Consulta aos registros em si (Verra, Gold Standard, MDL e outros) e seus termos de uso; texto integral da VM0038/VMR0014; valores de créditos emitidos (deliberadamente não levantados); a pergunta de titularidade.
