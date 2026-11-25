# Governança — especificação e implementação no mesmo Git
## Fonte de verdade e precedência
1. Norma oficial e regime aplicável ao ato, edição e licença (referência, não cópia integral não autorizada). Divergência interpretativa é pendência.
2. Requisito e decisão de produto/metodologia aprovados, com fonte e rationale. Issue dona mantém o aceite; este índice não a substitui.
3. Contrato detalhado em docs, código, migrações e testes. Divergência entre intenção e implementação é um achado, **não** autorização automática para mudar a regra.
4. Evidência de execução por commit, ambiente, dados e atores. CI verde ≠ homologação ≠ ato externo.
5. README e UI são projeções; não criar outra regra de cálculo ou motor de autorização.

## Antes de começar uma fatia
- Ler estado, decisões, pendências, matriz e issue dona; consultar contratos específicos e último CI. Verificar sobreposição com PRs abertos e mudanças da main.
- Declarar contexto (metodologia/edição, recorte, ator, ambiente, período, entrada e saída), regra/incidência, referência oficial e distinção política interna. Se necessário, obter decisão humana; não resolver silêncio normativo por default conveniente.
- Identificar serviço canônico, dados/versionamento, autorização, migração aditiva, revogação e rechecagem. Delimitar fatos, declarações, pareceres, snapshots, cálculo e publicação.
- Planejar aceites positivos e negativos, regressão sintética, isolamento e trilha. Não prometer aceite integral da issue por uma fatia.
- Escolher a menor fatia coerente com a prioridade e o gargalo de usuário, evitando duplicar motores. Se uma dependência real impedir teste positivo, fazer preparação explícita e manter gate.

## No PR
Atualize o documento canônico em docs/ (ou adicione um contrato), a entrada correspondente na matriz/estado e decisões/pendências **quando mudarem**. Atualize o catálogo quando adicionar documento; histórico de status deve indicar commit e PR/CI. Se não houver impacto de especificação (correção mecânica, dependência etc.), justifique no PR. Não editar retrospectivamente migrações publicadas, oráculos e evidências históricas.
Registre testes efetivamente executados e seus resultados; teste escrito não é aprovado. Verifique ID/edição/fonte/licença/escopo e acesso de dados. Não incluir conteúdo normativo/cliente restrito, credenciais, dados pessoais ou materiais não licenciados. Prefira metadados e links.

## Versionamento e revisão
CF-SPEC é versão documental separada da versão de software, do kit histórico e da VM0038. O corte inicial proposto é 0.3.0-rc.1. A cada mudança substancial acrescente changelog, preserve IDs de origem e referências. Após recuperação do kit, arquive distribuição autorizada imutável ou seu manifesto e referências seguras; não substituir por reconstrução. Divergência de documento histórico é mantida como registro, não apagada. Não use hash como prova de autenticidade ou admissão.
Status recomendado: DOCUMENTADO, IMPLEMENTADO_PARCIAL, TESTE_ESCRITO, CI_VERIFICADO (commit/escopo), BLOQUEADO, HOMOLOGADO (prova/autoridade), ATO_EXTERNO_COMPROVADO (retorno e autoridade). São eixos distintos. Não calcular percentual global de completude sem universo/denominador.

## Gate D17 de extensibilidade
Toda fatia explicita camada de incidência (plataforma, programa, método/edição, jurisdição, projeto), vínculo J01–J07, owner e teste. Constante específica fica no adaptador/pack, não em contrato genérico. Exceções temporárias descritas na [auditoria](CF-JOURNEY-001-AUDITORIA.md) recebem ação, owner e regressão; não duplicar regras/cálculo/ledger/autorizações. Norma e template por ato/edição, cobertura atômica antes de declarar release. [Contrato D17](CF-JOURNEY-001.md).
