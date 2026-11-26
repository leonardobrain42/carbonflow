# CF-DATA-007 · Licença pública do MCTI (CC BY-ND 3.0) e extensão da D19 a licenças (25/11/2026)
**Nível D17:** plataforma (módulo de licenças, categoria nova) e dados (fonte MCTI). Não é método nem programa. **Não é parecer jurídico; a leitura das operações abaixo é do agente e precisa de confirmação.**

## 1. Fatos verificados (evidência em `docs/data/evidence/mcti-license-capture.json`)
- A página oficial de fatores de emissão do MCTI/SIRENE (capturada em 25/11/2026) traz no rodapé: "Todo o conteúdo deste site está publicado sob a licença Creative Commons Atribuição-SemDerivações 3.0 Não Adaptada". **Não trouxe verificação antirrobô para este cliente**; o HTML salvo tem SHA-256 `3fea8475…860b4`. A página não informa a data em que o aviso passou a valer.
- Planilha de Despacho 2026 (janeiro a outubro): `…/arquivo/Despacho_2026_janout.xlsx`, 65.683 bytes, SHA-256 `7f8e2292…784fe`, **o servidor não envia `Last-Modified`**; o MCTI republica arquivos "corrigidos" com o mesmo nome, então o hash entra na edição (`2026-janout-7f8e2292`).
- Texto jurídico da CC BY-ND 3.0 lido (SHA-256 do HTML `f38359a4…2f5`): **cl. 3** concede licença mundial, perpétua, sem royalties e não exclusiva para **Reproduzir** a Obra, incorporá-la em **Coletâneas**, **Distribuir e Executar publicamente**, e os direitos "incluem o direito de fazer as modificações **tecnicamente necessárias** para exercer os direitos em outros meios e formatos"; direitos não concedidos ficam reservados. **Cl. 1:** *Adaptation* é obra baseada na Obra (tradução, obra derivada, "outras alterações"). **Cl. 4:** manter avisos, atribuir, não sublicenciar.
- Lei 9.610/1998, **art. 7º, §2º** (lida na cópia da Câmara): "A proteção concedida no inciso XIII [bases de dados] **não abarca os dados ou materiais em si mesmos** e se entende sem prejuízo de quaisquer direitos autorais que subsistam a respeito dos dados ou materiais contidos nas obras."

## 2. Operações, uma a uma (o que a licença dá e o que é interpretação)
| Operação do módulo | Fundamento | Natureza | Situação |
|---|---|---|---|
| Citar/referenciar a fonte | cl. 3 e 4 | cláusula | declarável |
| Guardar a planilha original | cl. 3 (Reproduzir), com avisos e atribuição | cláusula | declarável |
| Mostrar trechos com atribuição | cl. 3 (Distribuir/Executar) e 4(b) | cláusula | declarável |
| **Extrair os valores numéricos** | Lei 9.610 art. 7º §2º (a proteção da base não alcança os dados em si); a estrutura/seleção da planilha continua protegida | **interpretação** | declarável **só como interpretação**, a confirmar |
| Converter unidades e normalizar horários | "modificação tecnicamente necessária" (cl. 3) **ou** operação matemática sobre dados em si (art. 7º §2º); **fronteira com *Adaptation* não é nítida** | interpretação | tratada dentro da extração; **o produto guarda o original intocado e a transformação como camada derivada separada** |
| Produzir cálculos e resultados próprios | resultado do CarbonFlow não reproduz a obra; fundamento é o mesmo do art. 7º §2º | interpretação | **não** exige operação própria do módulo; só citar a fonte |
| **Redistribuir planilha modificada** | ND: a concessão não inclui Adaptações | **fora da licença** | **recusada pela API** (`OPERACAO_FORA_DA_LICENCA`) |
**Pendência específica:** o MCTI descreve a Análise de Despacho como "associada a projetos de MDL … exclusivamente, para estimar as RCEs". **Não decidido** se isso é limite de aplicabilidade metodológica ou restrição de uso; fica para a consulta e para a admissibilidade (a licença do site não trata disso). A página dá a licença ao **site**; que ela alcance o arquivo XLSX hospedado nele é leitura razoável, **não confirmada pelo MCTI**.

## 3. D19 estendida a licenças (V112, aditiva)
- **Categorias distintas:** `PARTICULAR_AUTHORIZATION` (a existente) e `PUBLIC_LICENSE` (nova, exige identificador, texto jurídico com hash, evidência com URL/data/hash e **base do início**). Serve a ONS, Senatran, Inmetro, IBGE.
- **Três atos distintos, nunca confundidos:** (1) *declaração interna* do responsável (`DECLARED_INTERNAL`, rol `carbonflow.platform.content-licenses.owners`), com fundamento por operação (cláusula × interpretação); (2) conferência dos termos (a mesma tela, decisão posterior); (3) *aprovação independente* (`APPROVED` por outra pessoa, `DUPLA_CUSTODIA` intacta). **Nenhum consumidor existente aceita `DECLARED_INTERNAL`** (todos exigem `APPROVED` e `submitted_by <> decided_by`), então a declaração **não simula** dupla custódia nem amplia direitos; o `decisionBasis` aparece como `OWNER_INTERNAL` ou `INDEPENDENT`.
- **Recusas:** licença pública fora do catálogo da plataforma (hoje só `CC-BY-ND-3.0`); operação que a licença não cobre; "cláusula" invocada para operação que a concessão não traz; início anterior à captura sem disponibilização comprovada (**início = data da captura**, não a da publicação); prazo inventado (`endsAt` nulo = "sem prazo fixo", respeitadas as condições).
- **O que isso NÃO faz:** não habilita o pipeline do MCTI em produção, não admite dados, não substitui a consulta ao MCTI, não vale como aprovação independente.

## 4. Registro
Fonte `MCTI-SIN-DESPACHO` / `2026-janout-7f8e2292` semeada pela V112 (metadados; sem bytes). O corpo pronto da declaração está em `docs/data/mcti-license-declaration.json` (validado contra a evidência por `scripts/test_public_license_declaration.py`, no CI). **A declaração em si é feita pelo responsável (rol `owners`) na tela de licenças**: não foi feita aqui porque exige a sua identidade autenticada com MFA. Desenvolvimento do pipeline **não aguarda** isso; só a utilização real dos dados fica à espera.
## 5. Bloqueios por tipo
Desenvolvimento: nenhum. Dados/processamento: ingestão real da planilha aguarda a declaração. Admissão metodológica: aberta (MDL × VM0038; CO₂; fuso/convenção). Uso operacional e certificação externa: fechados.
