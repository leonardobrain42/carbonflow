# Quantificação do PD — detalhe do pino canônico

Continua #216/#217 em #128/#43/#122. O owner permanece #96/#97.

A prévia do PD já nomeava os pinos EFfuel, IR e Dy com ID, versão e vintage.
Faltava um destino de detalhe por ID; a listagem atual não era um substituto seguro
para a versão referenciada. A nova rota é:

`GET /v1/projects/{project}/technical/open-parameter-bindings/{pin}`

O serviço canônico de parâmetros reutiliza a projeção tipada existente, filtrando
por projeto e ID, sob `VIEW_OPEN_PARAMETER_BINDING`. Não consulta o último pino
como substituto. Publicar outra versão não muda ID, valor declarado, versão ou
vintage do anterior. Atualidade é reavaliada no momento da leitura e pode mudar
para `STALE_NOT_ADMITTED` sem reescrever o histórico.

A resposta tem `private, no-store` e `nosniff`. Conta externa, ID inexistente ou
ID de outro projeto retornam 404. Fator licenciado permanece sem valor numérico;
IR/Dy explícitos seguem as mesmas permissões já usadas pela listagem canônica.
Licença expirada mantém o histórico de metadados visível à conta autorizada com
estado stale, sem copiar o número do fator. Esse detalhe não é um endpoint de bytes,
conteúdo protegido, regra admitida ou prova original.

As três referências do PD agora oferecem links exatos para seus pinos. O vínculo
continua protegido quando falta grant e a própria prévia da memória é retirada
quando sua licença/atualidade falha, conforme #216. Não há migração, tabela nova,
novo motor, fórmula no frontend ou promoção de `usableForE8`/`marksDocumentReady`.

## Verificação e limites

Testes API/PostgreSQL cobrem detalhe por ID, metadados e headers, ausência de valor
fóssil, isolamento entre contas/projetos, ID inexistente, licença expirada e leitura
da versão histórica após uma publicação posterior. A integração PD → referência
→ pino confere IDs/versões e ausência de fator licenciado. O navegador verifica o
link exato e acesso por teclado, preservando os cenários de bloqueio/restrição.

Esta entrega remove somente a limitação de ausência de endpoint dos pinos citada
em #216. Não acrescenta catálogo completo de fontes, locators, revisão humana por
alegação, pinagem de suporte na narrativa, manifesto ou exportação DOCX/PDF.
Validação local: build Angular aprovado; 7 testes Java com PostgreSQL 16 descartável
aprovados; 2 jornadas de navegador aprovadas, incluindo viewport móvel e teclado;
`git diff --check` aprovado. Suíte completa não repetida nesta fatia; CI remoto no PR.
Base: `05e079a`, com CI da main aprovada antes da publicação.
