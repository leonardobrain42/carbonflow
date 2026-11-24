# Quantificação do PD — conferir pinos no contexto da seção

Continuação de #218 em #128/#43/#122. O endpoint canônico e seus grants/licenças
permanecem no owner de parâmetros. Nenhum contrato backend é alterado.

As referências EFfuel, IR e Dy autorizadas oferecem a ação “Conferir pino nesta seção”.
Um único painel mostra a versão exata: método/edição, vintage, unidade registrada,
tipo de origem, conversão registrada e estado atual. Valores licenciados ou declarados
não são renderizados nesse painel. Unidade ou conversão ausente é explicitamente
não registrada; o navegador não infere um valor nem executa conversão.

O painel lê somente GET /open-parameter-bindings/{id}. A resposta deve corresponder
ao projeto, ID, símbolo e versão da referência autorizada. Resposta inconsistente ou
que afirme usableForE8 é recusada. Exibe vinculado sem admissão ou desatualizado,
sem transformar revisão em aprovação normativa ou prontidão documental.

Ao consultar outro pino, atualizar ou mudar de seção, o detalhe anterior é removido.
Respostas atrasadas não repõem conteúdo após a troca de contexto ou destruição do
componente. Erro de acesso apaga o detalhe e mostra mensagem genérica. A referência
protegida não oferece a ação. O link aos metadados canônicos continua disponível.

Não há fórmula, cópia para narrativa, nova tabela, alteração de grants/licenças,
suporte congelado, claims, manifesto ou exportação. O PD continua sem prontidão.

## Verificação

Navegador com fixtures autorais: abrir por teclado, versão/método/conversão, viewport
móvel, estado stale, acesso 404, resposta inconsistente e resposta atrasada após
atualização. Verifica painel removido e narrativa sem alterações. Regressão do vínculo
memória/PD preservada. Backend e política de licença são os já testados em #218.

Validação local: build Angular aprovado; 9 jornadas de navegador aprovadas,
sem falhas ou skips; `git diff --check` aprovado. Não houve alteração backend,
SQL ou contratos de autorização, portanto a suíte Java não foi repetida.
CI remoto será executado no PR. Base: `4481b84` após #218.
