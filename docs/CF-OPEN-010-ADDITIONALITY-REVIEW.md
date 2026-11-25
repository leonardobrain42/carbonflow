# CF-OPEN-010 — Parecer interno independente por critério de adicionalidade

**Issue dona:** [#99](https://github.com/leonardobrain42/carbonflow/issues/99). **Recorte:** VM0038 v1.1, OPEN BR LDV BEV DCFC, projetos SANDBOX.
**Estado:** implementação preparatória; **não conclui adicionalidade**, não admite rota, não habilita E8.

## O que este contrato adiciona
O `GET /v1/projects/{p}/technical/open-additionality` já devolvia três critérios (`POSITIVE_LIST`, `REGULATORY_SURPLUS`, `CHRONOLOGY`) como "parecer humano" sem lugar para registrá-lo. Agora cada critério tem uma **linha do tempo versionada de pareceres internos**, no campo `criteria[].review`.

`POST /v1/projects/{p}/technical/open-additionality/{criterion}/reviews` (`If-Match: "<versão atual>"`, `Idempotency-Key`), corpo `{outcome: CHECKED_INTERNAL|RETURNED, rationale (30–2000), evidenceReference (15–600)}`.

| Regra | Comportamento |
|---|---|
| Quem registra | Colaborador `REV` com a atividade `ADDITIONALITY` (ação `REVIEW_OPEN_ADDITIONALITY`, auditada). Dono do projeto e quem está fora recebem 404. Critério desconhecido: 404. |
| Independência | O revisor não pode ser quem registrou a última revisão do guia de adicionalidade nem a última declaração de aplicabilidade OPEN (403). |
| Pré-condição | Precisa existir declaração ou guia; senão 409. |
| Versão | Otimista por critério e imutável (gatilho): `If-Match` desatualizado é 412; um novo parecer é uma nova versão, nunca reescrita. |
| Idempotência | Mesma chave e mesmo conteúdo repete (200, sem nova versão); outro conteúdo com a mesma chave é 409. |
| Estados de `review.state` | `PENDING_HUMAN_OPINION`, `CHECKED_INTERNAL_NOT_ADMITTED`, `RETURNED_NOT_ADMITTED`, `STALE_NOT_ADMITTED`. |
| Obsolescência | O parecer guarda o contexto visto (versões da declaração e do guia). Se qualquer uma mudar depois, o estado passa a `STALE_NOT_ADMITTED`; não é reaproveitado em silêncio. |

Em toda resposta `concludesAdditionality`, `usableForE8` e `criteria[].concludes` continuam `false`.

## Limites (o que isto não é)
- Não é decisão da Verra/VVB nem parecer jurídico. Registra que uma pessoa independente da equipe preparadora conferiu um critério e onde está a evidência.
- Não decide a rota (lista positiva versus projeto/VT0008, #103), a validade do excedente regulatório nem a cronologia; não avalia o conteúdo da justificativa.
- O contexto verificado é só "versão da declaração e do guia"; mudanças em dados fora deles (direitos #93, mercado) não tornam o parecer obsoleto.
- Hoje a leitura `VIEW_OPEN_APPLICABILITY` só era possível ao dono: a atividade `APPLICABILITY` citada no código de permissões não existe na constraint de grants. Concedi leitura ao `REV` com `ADDITIONALITY`; a atividade inexistente permanece como estava.
- Sem tela Angular: o frontend ainda não consome `review`.

## Propagação para o diagnóstico
`GET .../technical/requirements` consome o estado do parecer (lista positiva e excedente) e marca `RECHECK_REQUIRED` quando ele fica obsoleto. Ver [CF-UX-TECH-001-UX08](CF-UX-TECH-001-UX08-REQUIREMENT-PROJECTION.md). Nada admite rota nem E8.

## Persistência e testes
Migração aditiva V100: `app_open_additionality_opinion` (PK projeto+critério+versão, imutável), `app_open_additionality_command` (idempotência, imutável) e a ação `REVIEW_OPEN_ADDITIONALITY` na lista auditada. O passo de recuperação histórica V79 do CI foi estendido à V100.
Teste: `OpenAdditionalityReviewIntegrationTest` (permissões, independência, validação, ETag, idempotência, versões, obsolescência, imutabilidade, flags sempre falsos).
