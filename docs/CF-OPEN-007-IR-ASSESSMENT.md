# CF-OPEN-007 — Núcleo preparatório de avaliação de IR (#96)

## Escopo
VM0038 v1.1, §9.1 Eq. 1 e §10.1 (pp. 20–21), [fonte oficial](https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/).
Para o ramo OPEN LDV DCFC SIN BR, esta fatia reconhece exclusivamente a rota específica com atualização anual da linha de base. O resultado candidato é IR=1; nenhuma outra taxa é presumida. A rota de defaults, ainda que exista na metodologia, não é implementada para este recorte brasileiro. O cálculo e os oráculos sintéticos permanecem intocados.

## Contrato do núcleo
`OpenIrAssessment` recebe projeto, ramo, ano ordinal do projeto e ano de referência, frequência e abordagem, autor e referências EVR/AFEC/MPG/EF_FUEL (identificador, versão, SHA-256 e ano). As referências são estruturais, não uma certificação de conteúdo. Para esta primeira rota, adota-se a seleção de ano de referência já utilizada no E8; eventual regra de vintage mais granular deverá ser resolvida no adaptador, sem confundir ano de projeto e ano civil.
O parecer recebido pelo núcleo exige outro ator, justificativa, decisão e o digest exato da avaliação. Digest muda se o projeto, o ano, o autor, a política ou a referência mudar; a ordem de entrada não altera o digest. Ausência de referência, duplicação ou ano incompatível não produz candidato.
O núcleo nunca retorna usableForE8=true. REVIEW_RECORDED_NOT_ADMITTED significa somente que o contrato lógico de parecer foi satisfeito, não que o autor/revisor foi autenticado ou que houve avaliação real.

## Próxima integração (ainda não implementada)
1. Resolver IDs canônicos e vigência/licenças pelo backend, conferir conteúdo e frequência anual efetiva (incluindo plano e atualizações observadas); não aceitar IDs/hashes fornecidos pelo cliente como prova.
2. Persistir avaliação e parecer em revisões imutáveis, com organização, ambiente, escopo e trilha de auditoria; aplicar locks, idempotência, ACL e competência/independência do revisor.
3. Integrar ao workbench de parâmetros e à jornada de linha de base, com justificativas e motivos de pendência.
4. Ligar avaliação vigente à seleção e ao snapshot de #97, preservando tentativas históricas e diferenciando IR declarado em ensaio de IR fundamentado. Não modificar o runner nesta fatia; ainda não consome o núcleo novo.
5. Testar por API/DB/UI a alteração/revogação de evidência, segregação e reprodução histórica. Manter gate de admissão real e homologação #101.

## Testes
OpenIrAssessmentTest: frequência, defaults, escopo, referências ausentes/duplicadas/ano incompatível, parecer próprio/rejeitado/sem justificativa, digest estável e alteração de versão, projeto e ano. Fixtures autorais de teste, sem dados reais.
