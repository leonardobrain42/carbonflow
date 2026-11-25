# CF-PACKAGES-001 — Estratégia de pacotes metodológicos: administração, fontes e código
**Decisão CF-SPEC-D18, do responsável pelo produto, 25/11/2026:** uma nova metodologia é adicionada pela **administração do CarbonFlow**; as **fontes** são adicionadas à metodologia específica; o que precisar de lógica é feito **por alteração de código**. Este documento é a estratégia proposta para cumprir essa decisão. Contrato de direção e sequência de entregas, **não** declaração de implementação, aprovação normativa ou licença. Camada D17: **plataforma** (registro e capacidades) com adaptadores por **método/edição** (código). Jornada: J01–J03 primeiro, depois J06. Owners: #90 (catálogo/contexto), #10 (regras), #92 (disponibilidade/licença), #124 (schemas), #43 (documentos), #16 (execução).

## 1. Princípio: o que cada lado pode fazer
Uma capacidade da plataforma para uma metodologia **não é um campo editável**. É o resultado de três condições que têm donos diferentes:

| Condição | Dono | Meio | Exemplos |
|---|---|---|---|
| **Dados** | Administração (staff, MFA, auditoria) | Telas/API de administração | Registrar metodologia/edição, anexar fontes, listar necessidades de fonte, registrar decisões de licença |
| **Código** | Desenvolvimento | PR, revisão, release | Schema de triagem, requisitos, formulários, adaptadores de cálculo, atos e templates |
| **Licença/autorização** | Decisão de licença já existente (`app_content_license*`) | Decisão por finalidade | Referenciar requisito, armazenar original, extrair, exibir trecho |

**Capacidade efetiva = código ∧ dados ∧ licença, calculada por finalidade, nunca gravada nem editada.** A administração não consegue habilitar cálculo, regra, admissão nem ato externo; o código não consegue liberar o que a licença não autoriza; uma licença não cria funcionalidade que o código não tem. Isso mantém D17: aceitar e cadastrar não é executar, admitir, homologar nem liberar.

## 2. Ciclo de vida derivado (nunca definido à mão)
O estado de uma metodologia/edição é deduzido dos fatos, em níveis que podem coexistir e regridem se o fato regride:

1. **REGISTRADA** — existe o registro de metadados (programa, metodologia, edição, título, titular de direitos, URL oficial). Já permite que um projeto a escolha como **candidata**, com lacuna explícita, sem fallback para VM0038 ou PIL-SYN (D17 §1).
2. **COM_FONTES** — fontes anexadas à metodologia, com as necessidades de fonte listadas (a mesma taxonomia que o console de fontes já usa para o VM0038, #92).
3. **FONTES_AUTORIZADAS** — decisões de licença vigentes, por finalidade. Revogação rebaixa só o que dependia dela; o congelado é preservado.
4. **COM_PACOTE** — existe pacote em código para aquela metodologia/edição, com versão e digest (hoje `MethodologyPackages`, #289).
5. **HABILITADA_PARA_<finalidade>** — triagem, requisitos, preparação, e assim por diante, calculada por finalidade. Cálculo real, admissão e ato externo continuam gates próprios e **não** derivam deste ciclo.

Uma metodologia sem código fica em 1–3: aceita como rascunho, com lacuna e próxima ação, sem rejeição na criação e sem execução.

## 3. O que a administração faz e não faz
Faz: registrar metodologia/edição (acréscimo de nova versão, nunca sobrescrita), anexar fonte à metodologia específica, registrar necessidades de fonte, submeter decisões de licença pelos fluxos existentes. Todas as operações exigem conta de staff com MFA, **privilégio próprio** para gerir o registro (distinto de apenas ver prontidão de fontes, como já previsto em CF-OPEN-ADMIN-SOURCES), auditoria e `private, no-store`.

Não faz: definir capacidades, estados, regras, parâmetros, fórmulas, cálculo, templates nem publicar norma. Não anexa texto ou bytes protegidos a um catálogo público; o registro guarda **metadados e referências** (nome, URL, hash, titular), como já faz `app_source_version`. Cliente nunca vê o inventário administrativo (isolamento staff × cliente, #90).

## 4. O que fica por código e como entra
Tudo que tem lógica entra por PR no repositório, ligado ao registro por uma chave estável `programa + metodologia + edição`:
- schema de triagem, atividades cobertas e textos (hoje `ProjectJourney.Profile`, rc.37–39);
- requisitos, schemas de formulário, parâmetros e unidades (enquanto não houver motor declarativo provado);
- adaptador de quantificação com oráculo independente (#16/#97);
- atos, tarefas, templates e mapa de documentos (#43/#124, A04).

**Contrato de pacote em código** (formalizado na fatia 4, `MethodologyPackageContract`; regras em [docs/CF-PACKAGES-001-METHODOLOGY-REGISTRY.md](../docs/CF-PACKAGES-001-METHODOLOGY-REGISTRY.md)): descritor com `packageId`, `version`, `digest`, chave do registro, finalidades suportadas e avaliadores; mais um **kit de conformidade** que todo pacote precisa passar: não vazar campos ou gates de outra metodologia (AGN-01), respostas neutras para o que não cobre, atividades dentro do vocabulário da plataforma, digest que muda a qualquer alteração. Pacotes **TEST_ONLY** existem só no escopo de teste e nunca entram no registro de produção (AGN-02). Declarar um pacote sem registro correspondente, ou registro sem pacote, não é erro: é o estado derivado correspondente.

Um schema declarativo (dados em vez de código) só será considerado se houver repetição real entre pacotes e um validador estrito sem `eval` (D17 §3). Até lá, "o que precisa de lógica é código" é regra, não exceção.

## 5. Versões, fixação e migração
- Registro e anexos são **somente de acréscimo**, com versão e digest; correção é nova versão, nunca edição de linha.
- O projeto **fixa** a versão do pacote e do registro quando a metodologia é escolhida e quando atos são congelados. Uma versão nova não altera projetos existentes. Migrar é operação explícita e versionada que reabre só os dependentes e preserva os congelados (D17, readiness).
- Resolução por data e ato (edição vigente, transição de edições) segue os contratos de #10/#13; este documento não decide vigência.
- Ambiente: as licenças hoje são `SANDBOX`; o registro nasce no mesmo regime. Produção depende de decisão separada.

## 6. Sequência de entrega (cada item é uma fatia com PR, teste e spec)
1. **Registro de metodologias (dados).** Migração aditiva do registro (chave, metadados, versão, digest, autor, data) ligado a `app_source_version` quando já houver fonte; API de staff com privilégio próprio, MFA e auditoria; a triagem passa a listar, além dos pacotes em código, as metodologias **registradas sem pacote**, aceitando-as como candidata com lacuna. Semeia VM0038 1.1 de forma coerente com V37.
2. **Fontes por metodologia.** Generalizar as necessidades de fonte hoje específicas do VM0038/OPEN (V42) para qualquer metodologia registrada; o console passa a receber a chave da metodologia em vez de uma constante.
3. **Capacidade efetiva.** Cálculo derivado (código ∧ dados ∧ licença) por finalidade, exposto ao projeto sem inventário administrativo; fixação da versão do pacote no projeto.
4. **Contrato e kit de conformidade do pacote.** Formalizar o descritor, ligar o VM0038 ao registro e executar o kit contra o VM0038 e um pacote TEST_ONLY (AGN-02/03); incluir ausência de licença revogando só o afetado.
5. **A04.** Seções e template do PD por pacote seguem o mesmo padrão: dados descrevem, código implementa, licença autoriza.

## 7. Critérios de aceite propostos (ainda não executados)
- Administrador registra uma metodologia sem pacote: projeto a escolhe como candidata, vê a lacuna e a próxima ação, e nenhum campo, gate, motor ou cálculo de outra metodologia aparece (AGN-01).
- Anexar fonte e decidir licença não habilita finalidade sem código; código sem licença não habilita a finalidade protegida; revogar licença rebaixa só os dependentes e preserva o congelado (AGN-03).
- Dois pacotes e duas edições simultâneos resolvem contexto e vigência por projeto e ato (AGN-03); pacote TEST_ONLY não aparece no registro nem na lista de produção (AGN-02).
- Cliente, outra organização e staff sem privilégio não leem nem alteram o registro; toda operação administrativa gera auditoria; o registro só aceita acréscimo.
- Nenhuma alteração do VM0038 OPEN, de D12–D16 nem dos gates existentes (AGN-04).

## 8. Escolhas desta proposta, reversíveis e a confirmar
Foram adotadas por serem as mais conservadoras; cada uma pode ser trocada sem mudar a decisão D18:
- um **privilégio de staff próprio** para gerir o registro, separado da leitura de prontidão de fontes;
- **registro somente de acréscimo**, sem edição ou exclusão;
- **fixação de versão no projeto** quando a metodologia é escolhida;
- **regime sandbox** até haver decisão sobre produção;
- **sem segundo aprovador** no cadastro (a administração cadastra); decisões de licença seguem o fluxo e as regras que já existem. Se houver exigência de aprovação por segunda pessoa, ela é acrescentada como regra nova, não presumida aqui.

Fora do escopo e sem suposição: quem na administração tem cada privilégio, política de substituição de edições, publicação em produção, conteúdo licenciado real e qualquer segundo método comercial (AGN-02/03 provam extensibilidade por fixture).
