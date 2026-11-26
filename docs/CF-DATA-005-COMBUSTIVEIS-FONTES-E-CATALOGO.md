# CF-DATA-005 · Catálogo de combustíveis brasileiros: fontes oficiais, contrato e lacunas (D19 fatia 5, Q12, #96)

**Nível D17:** plataforma/dados (contrato) e jurisdição Brasil (fontes). Recorte inicial inalterado. **Nada aqui admite fator**: o catálogo versionado traz identidades, uma composição com fonte primária lida e as lacunas de evidência. Pesquisa de 25/11/2026.

## 1. O que foi lido na fonte primária
- **Resolução CNPE nº 9, de 14/07/2026** (DOU 30/07/2026, Seção 1 Extra B, p. 12; SHA-256 do PDF baixado `56c8440c…e741fd`; o PDF traz o aviso de que não substitui a versão certificada): art. 1º fixa, em caráter **excepcional e temporário, 32%** de etanol anidro na **Gasolina Comum**; parágrafo único: vigência de **180 dias** da entrada em vigor, prorrogável **uma única vez** por igual período por ato do Presidente do CNPE; art. 2º suspende o percentual do art. 1º, I, da Resolução CNPE nº 9/2025 durante a vigência; art. 3º trata a tolerância de **1 ponto percentual** como metrológica, não cumulável; art. 4º: vigência a partir de **1º/08/2026**. O fim da vigência **não foi calculado** (a convenção de contagem dos 180 dias não está no texto lido).
- O **teor anterior (30%)** vem de reportagens; o valor não aparece no texto primário lido (que só cita o art. 1º, I, da Resolução CNPE nº 9/2025). Fica como lacuna.

## 2. Fontes candidatas por combustível (o que existe, o que falta ler)
| Combustível | Fonte candidata | Estado |
|---|---|---|
| Gasolina C | CNPE nº 9/2026 (teor de etanol anidro); especificação da ANP | teor: **lido**; especificação ANP: **não lida** |
| Etanol anidro e hidratado | especificações da ANP; EPE; nota da AEA | **não lidas** (primárias) |
| Gasolina A, diesel A | EPE, Nota Técnica EPE/DPG/SDB/2025/03 (jun/2025); Inventário Nacional (MCTI) | EPE e inventário: **não lidos**; ver §3 |
| Diesel B (comercial) e biodiesel | ato primário da mistura (MME/CNPE); reportagens indicam **B15** em vigor, sem B16 em 2026 | **ato não lido**; B15 não entra no catálogo |
| GNV | EPE; ANP (qualidade do gás); Inventário Nacional | **não lidos** |

## 3. Fonte secundária encontrada (não admitida)
A **Nota Técnica da AEA "Intensidade de carbono dos energéticos para veículos pesados"** (anexada ao MDIC, dados médios nacionais de 2024) **reproduz** a Nota Técnica da EPE de jun/2025. Relevante para este catálogo: traz propriedades físicas (massa específica e PCI) por combustível (Tabela 1), intensidade de carbono em **gCO2eq/MJ** separada em poço-ao-tanque, tanque-à-roda e poço-à-roda (Tabelas 2 a 6), **sem separar os gases**. Limites: é secundária e de uma associação (direitos de reuso não confirmados), foi feita **para veículos pesados**, e o valor é CO2eq agregado, sem a base de GWP lida por mim. Por isso **nenhum número dela entrou no catálogo**. O arquivo (SHA-256 `1ce894f7…e0462`) não é versionado. A nota também registra que as propriedades do etanol hidratado e do GNV **divergem** das usadas no PBEV/Inmetro, o que importa para a coerência com o rating (AFEC/MPG).

## 4. Confronto com a metodologia (perguntas que só pessoas decidem)
O fluxo existente de EFfuel (gasolina, CO2e, unidade-alvo tCO2e/US gal, G04) já exige gás CO2e na origem. O catálogo novo não o substitui. Para ampliar a outros combustíveis, faltam decisões fundamentadas nos textos da metodologia:
1. **Escopo da emissão:** só uso (tanque-à-roda) ou também a cadeia (poço-à-tanque)? A resposta muda qual coluna da fonte vale; **não presumido**.
2. **CO2 biogênico** do etanol e do biodiesel (contabilização, e a parcela renovável da gasolina C e do diesel B): regra da metodologia/ferramenta **não confrontada** aqui.
3. **Combustível comparável de LDV:** gasolina C (com 32% de etanol hoje) e etanol hidratado como combustíveis de linha de base; o catálogo registra a **composição datada**, e a escolha é do projeto/metodologia.
4. **Base de GWP** (AR4/AR5/AR6) para CO2e: exigida explícita no contrato; qual vale vem da metodologia/programa.
5. **Unidade e conversão** (energia, massa, volume, galão US): o contrato aceita unidades por dimensão e **não converte entre dimensões**; conversão entre dimensões exige propriedade (massa específica, PCI) de fonte primária.

## 5. Contrato (`scripts/fuel_catalog.py`, validador offline)
Entidades: `fuels` (identidade, família, papel PRODUTO/MISTURA, fase, **lacunas pendentes**), `compositions` (mistura, componente, base volume/massa/energia, nominal **ou** complemento, janela de vigência, regra de vigência, evidência) e `data` (propriedade ou fator, **gás**, **gwpSet** para CO2E, valor em texto decimal, unidade da dimensão, **ano de vintage**, janela, evidência, **revisão**, `admission`).
Regras validadas: valor só com evidência (fonte, localizador, estado de leitura); **fonte primária não lida não sustenta valor**; gás obrigatório em fator de emissão; CO2E exige GWP explícito; unidade pertence à dimensão; janelas da mesma série **não se sobrepõem**; componentes de uma mistura somam **exatamente 1** (um único complemento fecha a conta); **`admission` só pode ser `NOT_ADMITTED`**; revisão `INDEPENDENT` exige revisor diferente de quem registrou e `OWNER_INTERNAL` nunca vale como independente (mesma separação da fatia 1).
Seed: `docs/data/fuel-catalog.json` (8 combustíveis, composição da gasolina C a 32% desde 01/08/2026 com a fonte lida, **zero** dados de fator).

## 6. O que ainda falta
- **Persistência e tela** do catálogo (tabelas aditivas, perfis, auditoria, MFA), seguindo o padrão do catálogo de modelos: próxima fatia, depois de decidir as perguntas do §4 que alterem o contrato.
- Leitura das fontes primárias do §2 (EPE jun/2025, Inventário Nacional/MCTI, ANP, ato da mistura do diesel).
- Ligação do catálogo ao `EF_FUEL` do E8: **não feita**; nada aqui altera cálculo.
