# Quantificação do PD — seleção de frota e ratings no contexto

Continuação de #216–#220 em #128/#43/#122/#94/#95. Sem mudança backend/SQL.

A referência EVR autorizada oferece “Conferir seleção de frota nesta seção”.
A leitura usa o endpoint canônico de seleção, com o ID e período da tentativa.
Confere projeto, período, ID, versão, ano de observação pinado e usableForE8=false.
Não usa a seleção mais recente como fallback. Fonte protegida não oferece a ação.

Um painel mostra somente identidade/versão da seleção, ano de observação, ciclo,
data de publicação e estado atual preparatório ou stale. O ano/ciclo identificam
um recorte registrado: não demonstram cobertura nacional nem admitem ratings.
Não renderiza modelos, denominador, estoque EVR, pesos, AFEC/MPG ou hashes de fontes,
mesmo que um campo adicional esteja presente na resposta do owner.

Seleção stale é histórica e requer rechecagem. Acesso negado remove os metadados
consultados e informa indisponibilidade. Resposta de outro período/ano ou que alegue
admissão é recusada. Frota, energia e pinos compartilham a invalidação de contexto:
outra consulta, atualização, troca de seção e destruição impedem respostas tardias
de repor o detalhe anterior.

O catálogo e as verificações de licença/atualidade permanecem com os owners. Esta
fatia não importa fonte, copia valores licenciados, calcula linha de base, fixa
suporte na narrativa, cria claims/manifesto ou exporta DOCX/PDF. O PD não fica pronto.

## Verificação

Fixtures autorais de navegador: sem grant não há botão/requisição; seleção exata
com ano/ciclo; stale; 404; período/ano incorretos; admissão indevida; resposta atrasada
de frota após consultar energia; campos numéricos/modelos/hash ausentes do DOM;
teclado/celular e narrativa inalterada. Regressões de energia/pinos/guias preservadas.

Validação local: build Angular aprovado; 9 jornadas de navegador aprovadas,
sem falhas ou skips; `git diff --check` aprovado. Backend/SQL não alterados e
suíte Java não repetida nesta fatia. CI remoto será executado no PR.
Base: `0ff0486` após #220.
