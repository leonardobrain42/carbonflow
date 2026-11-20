# CF-UX-TECH-001 — UX-02A: esquema de campos versionado e validação inicial

Continua a issue #124 após o PR #137. Esta é uma **fatia compatível**, não o encerramento de UX-02 ou da jornada.

## Entrega

- Flyway V66 adiciona `definition_version` separado do número da revisão do guia e do pacote metodológico. Revisões anteriores permanecem imutáveis (definição v1); novas revisões registram a versão da definição efetivamente usada.
- `GET /v1/projects/{id}/technical/guides` informa `definitionVersion`, `legacyUnstructured` e `helpText` por campo. A API existente segue devolvendo `answers: Map<String,String>` por compatibilidade.
- `PUT /.../guides/{guide}` aceita opcionalmente `X-Guide-Definition-Version`. Versão desatualizada: HTTP 409, sem persistência; mantém `If-Match` de revisão. Versões legadas sem cabeçalho ainda passam por validação no servidor.
- Na VM0038 v1.1, `quantification.vintage` ganha tipo de ano e validação 1900–2100. Datas e enums continuam validados no backend. Valores históricos ambíguos são sinalizados como `legacyUnstructured`, preservando o texto original e exigindo correção explícita, sem fabricar referências ou evidências.
- Formulário aparece antes dos painéis de referências, avaliação, solicitações e fatos; dicas contextuais acessíveis com `aria-describedby`; erro específico para ano. HelpTexts esclarecem diferença entre texto descritivo e ativos/direitos/evidências canônicas.
- Testes JUnit sem banco para validação/legado e teste Playwright para schema, bloqueio de ano inválido e header de versão.

## Limitações e próximos passos

Ainda NÃO são formulários de payload totalmente tipado, nem integração de seletores canônicos para estação, titular, evento, documento ou prova. `Map<String,String>` não deve ser promovido a fatos verificados. Nenhum dado aqui aciona o motor E8, confere poderes ao PP ou determina admissão VCS.

Próxima fatia: UI de estações e equipamentos com `STATION_REF`/referências reais do inventário #98, preenchimento condicionado ao perfil OPEN e backend validando identidade e ACL; depois eventos/direitos, evidências por campo, mapping PD e migração gradual do payload. Regressão de acessos, revisão e PIL-SYN obrigatória.
