# Efeitos colaterais da biblioteca administrativa (CF-IMP-029-E)

**Não** redefinir a responsabilidade das outras issues: a CF-IMP-029 é dona da navegação administrativa e das operações do catálogo; a ECA-001 governa direitos/autorizações e a ECA-002 define o catálogo de requisitos normativos consumido pelas jornadas.

| Item | Ajuste necessário | Gate |
|---|---|---|
| CF-IMP-028 | Staff autentica com MFA e grants por finalidade; separar `SOURCE_CURATOR`, `CONTENT_LICENSE_REVIEWER` e analista de representações. | Nenhum staff aprova própria licença. |
| CF-IMP-029 | Acrescentar `/platform/sources`, versões, licenças, ingestões e fila de purge, métricas de conteúdos autorizados. | Não presumir licenças por upload manual. |
| CF-IMP-015 | Biblioteca de ajuda aponta a `source_version` e a `requirement_version`; o leitor recebe somente metadados ou trechos licenciados. | Material não licenciado não entra no índice de ajuda. |
| CF-IMP-014 | Tarefas referenciam regras estáveis e versões, não chunks persistidos em tarefa. | Invalidar dependências quando vigência mudar. |
| ECA-001 | Manter matriz de licenças por titular/versão/capacidade/ambiente/finalidade; fluxo de autorização formal Verra. | Sem licença, somente fixture autoral. |
| ECA-002 | RequirementResolver usa regras publicadas e revisadas com `source_version_id`. | Mudanças de documento não publicam regra automaticamente. |
| ECA-006 | Extrator aplica política de conteúdo aos anexos do cliente e às fontes externas. | Não copiar documento privado para corpus global. |
| ECA-008 | `RAG_RETRIEVE`, `MODEL_INFERENCE`, `SHOW_EXCERPTS` validados a cada chamada; abstenção em falta de corpus permitido. | Sem autorização sem RAG. |
| ECA-014 | Segurança, tenancy, observabilidade, não retenção por provedor e deleção de vetores. | Revogação bloqueia antes da purge assíncrona. |
| CF-IMP-026 / ECA-015 | Testar legal hold, revogação, expiração, fonte adversarial, conta revogada, indisponibilidade, eventos e exclusão. | Negativas devem passar em CI. |
| CF-IMP-029 suporte | Tickets futuros podem referenciar metadados de fonte, mas não receber anexos normativos por padrão. | Capacidade suporte `enabled=false` até CF-SUP-001. |

## Decisões pendentes

1. Direitos/escopos expressos obtidos da Verra; não presumir cobertura de metodologia por simples inclusão no catálogo.
2. Quem será o verificador de licenças, eventual revisão jurídica, delegação e segregação.
3. Storage/KMS, limites e janela de purga após revogação; decidir antes da produção.
4. Quais fontes públicas brasileiras permitem indexação/comercialização, por titular e licença.
5. Preferência por API oficial ou armazenamento de versões licenciadas, quando Verra responder.

## Critério de integração

Toda feature de busca, autoria ou verificação por IA deve apresentar rastreio:
`source_version → legal_scope → licensed_operation → requirement/answer → evidence/review`.
Resultados sem evidência permanecem `UNKNOWN`, nunca `COMPLIANT`.
