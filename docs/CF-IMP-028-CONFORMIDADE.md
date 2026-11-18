# Comprovação de poderes no sandbox

O proprietário do rascunho registra a solicitação e os comprovantes no projeto em **Comprovação de poderes**. A equipe da plataforma usa **/workspace/compliance** no mesmo ambiente. A conta da equipe deve ser distinta da conta solicitante e estar autorizada para a organização.

## Configuração da equipe

- Habilite o ambiente de contas do sandbox.
- Configure `CARBONFLOW_COMPLIANCE_STAFF` no gerenciador de segredos. O formato de cada entrada é `UUID_DA_CONTA:SEGREDO_BASE64URL:UUID_DA_ORGANIZACAO`. Separe organizações autorizadas para a mesma conta por vírgulas e entradas de contas por ponto e vírgula.
- Gere um segredo aleatório individual de pelo menos 20 bytes para cada conta. Provisionar o autenticador requer o mesmo segredo em Base32. Use TOTP SHA-1, seis dígitos e período de 30 segundos. O acesso dura até dez minutos e fica vinculado à sessão da conta. [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238)
- Configure `CARBONFLOW_COMPLIANCE_DOCUMENT_KEY` com 32 bytes aleatórios em Base64URL. Preserve a chave no backup de segredos junto dos backups correspondentes. Alterar a chave exige migrar os documentos já armazenados.

A página aceita até cinco PDFs de até 2 MiB por solicitação. O servidor valida o tamanho e a assinatura do formato, cifra os bytes e entrega o arquivo apenas como download autenticado. O sandbox usa documentos de teste.

A fila, a validação MFA e os downloads pela equipe têm trilha de acesso. O solicitante pode retirar um pedido pendente. A retirada mantém o histórico e encerra o acesso aos comprovantes.

## Decisões e vigência

A equipe pode pedir complemento, indeferir, conceder um poder com comprovante e vigência ou revogar um poder concedido. Cada decisão registra o autor, a edição e o motivo. A concessão de representação abrange só o projeto analisado. A capacidade de enviar convites tem pedido e vigência próprios.

Um poder aparece como vigente somente enquanto o vínculo com a organização, o pedido e a decisão correspondente estiverem ativos. Após revogação o histórico permanece consultável e o acesso aos comprovantes do pedido é encerrado. A ativação de convites é tratada na [CF-IMP-013](https://github.com/leonardobrain42/carbonflow/issues/24).
