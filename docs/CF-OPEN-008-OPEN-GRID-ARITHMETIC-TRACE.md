# CF-OPEN-008 #97 — Núcleo matemático VM0038 v1.1 e rastreabilidade preparatória

**STATUS: TEST_ONLY / NOT_ADMITTED.** Implementação em Java de aritmética isolada, sem API de execução de projeto e sem fonte real aprovada. Não habilita E8, registro, Verra/VVB, elaboração definitiva de PD ou emissão de VCUs.

## Regras oficiais consultadas

Fonte: https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/ (versão 1.1, vigente desde 19/08/2026), PDF https://verra.org/documents/vm0038-electric-vehicle-charging-systems-v1-1/ , seções 9.1–9.4 e 10. A licença protege o documento normativo: este arquivo referencia as regras, não reproduz o texto ou as tabelas de Verra.

- Eq. 2: AFEC ponderado pelo estoque EVR por modelo, não vendas nem eventos de carga. Eq. 3: MPG comparável ponderado pelo mesmo EVR.
- Eq. 1: BE com ED (kWh efetivamente entregues), fator fóssil tCO2e/US gal, 100, melhoria tecnológica IR^(y−1) e AFEC×MPG.
- Eq. 4: PE com EC (kWh consumidos na infraestrutura) e EFel tCO2e/kWh. ED e EC são grandezas diferentes. Se EFel for horário, EC e a cobertura temporal também precisam seguir essa resolução no runner futuro.
- §9.3: leakage considerado de minimis (zero), somente quando a metodologia/ramo forem pertinentes.
- Eq. 7: ER=(BE−PE)×Dy. Eq. 8: Dy=ERC/(ERF+ERC); a ausência comprovada de créditos de frota pode justificar Dy=1. Não presumir Dy por zero digitado.
- Ramo específico OPEN Brasil LDV BEV DCFC SIN sem bateria, solar, V2G ou fonte associada. Para infraestrutura associada, Eq. 5/6 substitui Eq. 4 e **não** pode ser executada nesse motor.

## Entrega do código

### Núcleo OpenGridCalculationMath (backend, package-private)

Input: branch, projectYear, fleetRows (EVR, kWh/100 mi, MPG por modelo), ED, EC, EFfuel, EFel, IR e discount (ERC, ERF, comprovante declarado de ausência de créditos de frota).

Outputs: AFEC, MPG, BE, PE, LE, Dy, ER e ER em duas casas; trace ordenado por modelo e por equação, com valor, unidade, símbolo e referência normativa. BigDecimal/DECIMAL128, entradas limitadas em precisão, nenhuma conversão ou valor implícito. Preserva input 0 legítimo e rejeita ausência; não arredonda negativos para emissão zero.

**Não é uma admissão:** a prova de ausência de crédito e os demais valores ainda não são autenticados nem associados a snapshots. Portanto Memory.state=PURE_MATH_TEST_ONLY_NOT_ADMITTED, usableForE8=false, sempre. Não há nova rota de cálculo ou alteração do PIL-SYN; a engine legada sintética permanece preservada e isolada.

### Plano de equações OpenCalculationTransparency (projeto real)

GET /v1/projects/{project}/technical/open-calculation-readiness acrescenta equationPlan[] com AFEC, MPG, BE, PE, LE, DY, ER: regra/edição, unidade, dependências, códigos bloqueantes, status NOT_CALCULATED/OUT_OF_SCOPE e `whyNotCalculated`. O texto reúne os motivos que os gates do mesmo diagnóstico já publicam. Fora do recorte, o texto diz que nenhum número foi produzido. Não são enviados valores numéricos nem insumos de fonte para um cliente sem admissão. A Angular UI apresenta as explicações como detalhes acessíveis e link à fonte oficial, sem executar fórmula TypeScript. Cada entrada e cada dependência ainda não admitida mostra o nome em português e o símbolo estável na referência. O título da equação continua sendo o símbolo público, como BE ou ER.

A autorização continua via VIEW_OPEN_CALCULATION de DynamicProjectScope, auditada, com no-store, incluindo requisitos de MR quando pertinentes. A referência do método não é uma autorização de usar seu texto integral.

## Testes

- JUnit: massa totalmente fictícia de teste com EVR 12@18/32 e 8@23/27, AFEC 20 e MPG 30; ED=2700, EC=3000 kWh, EFfuel=0.009, EFel=0.0003, IR=1, Dy=1 sob exceção explicitamente sinalizada na fixture: BE=4.05, PE=0.9, ER=3.15 tCO2e. Outro caso IR=0.99, ERC 60 / ERF 40 ⇒ Dy=0.6 e ER=1.8657. Não são dados oficiais nem fator do SIN.
- Validações: unidades, ausência versus zero, versão do motor, ramificação, ano, duplicação de modelos, overflow, limite de precisão, reordenação, JDBC NUMERIC(30,12), IR e Dy sem defaults, ER negativo, trace e hashes/IDs do piloto fora das APIs reais.
- Testes Quarkus/PostgreSQL e Angular: 7 equações no diagnóstico autorizado, sem emissões reais; papel/tenant e bloqueios mantidos.

## Próximas integrações obrigatórias (não implementadas nesta fatia)

1. Vincular parâmetros e period energy snapshots realmente admitidos por projeto/ano, com EVR (#94), ratings (#95), fatores e desconto (#96), ED/EC QA (#98), direitos/adicionalidade (#99), regras e licenças (#10/#33/#92).
2. Criar runner E8 único e transacional: If-Match, idempotência, snapshots e hashes, controle de competência, replay/diff/stale, e adoção das opções e temporalidades aplicáveis.
3. Ligar somente resultados admissíveis ao PD (#43/#122) e explorador de transparência (#128). Não confundir TOOL07 A1 tCO2 com tCO2e ou aptidão regulatória.

**A issue #97 continua aberta.** Esta fatia só comprova aritmética isolada e explicações de bloqueios reais na jornada.
