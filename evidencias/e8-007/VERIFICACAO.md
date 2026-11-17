# E8-007 · Dossiês PD e MR por ato

PIL-SYN-BR-001 · Kit 0.2.14 · Simulação em 16/11/2026 · manifesto `e8-007@1` · Flyway V7.

As saídas DOCX em inglês usam um modelo sintético com seções mapeadas de SRC-007 PD/MR 5.0B. Os manifestos identificam o arquivo de referência e seu SHA-256. Os anexos de representação, contratação, parecer e resposta usam referências sintéticas atribuídas ao papel previsto, com destino e versão fixados. O canal de exportação é `ARQUIVO_SIMULADO`. Assinaturas, protocolo externo e homologação exigem provas dos responsáveis previstos no backlog.

| Ato | Peças e fontes exercitadas |
|---|---|
| Desenvolvimento · PKG-E4-01 | PD indicativo, plano de engajamento e avaliação ESG em rascunho, compromisso, datas projetadas e estimativas por período. Incidências avaliadas com justificativa. |
| Listagem em validação · PKG-E4-02 | PD completo, peças de engajamento/ESG e mapa KML sintético, com contratação e representações aplicáveis sob acesso reservado. |
| Registro · PKG-E4-03 | PD completo, mapa, direitos, representação de registro, relatório e representação sintéticos de validação atribuídos ao VVB. A versão congelada é referenciada no MR. |
| Pedido de aprovação · PKG-E4-04 | MR M1 ou M2, cobertura, representação de emissão, verificação e análise de visita/risco de autoria sintética, ESG monitoring e eventual mapa. A variante reservada conserva os bytes do fechamento e do snapshot do cálculo. |
| Nova rodada e controles comuns · PKG-E4-05/07 | Referência à revisão anterior, motivo, peça de resposta e comparações da mesma exposição. No ensaio, o histórico preserva ZIP, manifesto e recibo. |

## Resultados na aplicação

| Caso | Resultado observado |
|---|---|
| CT-E5-15 e CT-E4-10 | O PD completo sem mapa e o indicativo sem justificativa tiveram o avanço recusado. O backend recusou referência de autoria incompatível. A devolução registrou item, responsável e correção esperada. |
| CT-E5-16 e CT-E4-09 | Conta com PREP e REV recebeu recusa ao tentar congelar sua própria versão. DOCX público e comparado público preservaram o conteúdo técnico e omitiram nota, contato fictício, anexos reservados e propriedades autorais. A concessão ao VVB abriu somente a variante concedida. A revogação bloqueou o link. |
| CT-E5-19/28 | PP exportou ZIP com arquivos e manifesto conferidos por SHA-256. Canal real recebeu recusa. Hash divergente bloqueou recibo. A repetição do mesmo recibo retornou o registro original e o ZIP exportado permaneceu recuperável após nova rodada. |
| CT-E5-13/14 | Os MRs reproduziram M1 = 6,00 e M2 = 5,30 tCO₂e com EF-SYN-v1. M1 contém 1,80 e 4,20 tCO₂e, e M2 contém 1,14 e 4,16 tCO₂e. A reabertura de M1 mostrou as variantes afetadas e bloqueou nova exportação. A entrega anterior permaneceu byte a byte recuperável. |

O build Angular e o Docker Compose passaram. A suíte `mvn clean verify` executou **20 testes** com Java 21, Quarkus e PostgreSQL 16.16 em Docker. **Sete jornadas automatizadas em Chrome** passaram, incluindo prévia pública e reservada, aprovação por revisão segregada, download e captura do recibo na interface. Os scripts locais de E4, E6 e E7 também passaram. Eles conferem a referência do kit em memória.

Foram renderizados e inspecionados 10 DOCX de amostra, com 11 páginas ao todo, incluindo PDs, MRs, anexos de engajamento e ESG e ambas as exposições da comparação.

- [Dossiês e recibo da suíte Java](DOSSIES.json)
- [Manifestos reservados dos MRs de M1 e M2](MRS.json)
- [Hashes das quatro exportações verificadas na suíte](EXPORTACOES.sha256)

## Pendências

- **PEN-002, PEN-007, PEN-011 e PEN-012** continuam abertas para edição, vigência, poderes, canal, representação, geodados e vintages do ato real. PP e revisão precisam validar o que incide por evento. E8-008 trata das listagens e da conferência do canal, E8-009 trata das auditorias e E8-010 trata dos atos de verificação/emissão e vínculos de posição conforme o backlog.
- **PEN-004, PEN-009, PEN-010** continuam abertas para prova de direitos, sobreposição, Dy, fontes setoriais, licenças e excedente. OP e PP fornecem as provas e a revisão técnica confirma incidência.
- **PEN-006** permanece aberta para designação do revisor competente e independente e registro de seu parecer. Autoria de pareceres e assinaturas externas cabe aos seus emissores.
- Campanhas integradas de limites, recuperação, retenção e cinco sessões moderadas com pessoas distintas (CT-E5-27/31/38/39) continuam pendentes.
