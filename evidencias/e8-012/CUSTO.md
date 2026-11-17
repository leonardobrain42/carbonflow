# Comparação mensal de infraestrutura · PEN-008

Referência pública consultada em 16/11/2026 para o PIL-SYN-BR-001. O cálculo usa as mesmas premissas nas duas opções. O processamento principal fica em São Paulo e a cópia lógica vai para um bucket na Virgínia do Norte administrado separadamente. As duas opções preservam Quarkus, PostgreSQL 16, Angular e a aplicação sob uma origem. O provisionamento, a restauração do fornecedor e a latência entre serviços ficam para homologação. [Preço público do Lightsail](https://aws.amazon.com/lightsail/pricing/), [Regiões](https://docs.aws.amazon.com/lightsail/latest/userguide/understanding-regions-and-availability-zones-in-amazon-lightsail.html), [Versões do PostgreSQL](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-choosing-a-database.html)

| Componente no mês de referência | Host único com PostgreSQL local | Aplicação com PostgreSQL gerenciado |
| --- | ---: | ---: |
| Instância Linux 2 vCPU, 4 GB, 80 GB SSD, IPv4 | US$ 24,00 | US$ 24,00 |
| Banco padrão criptografado, 1 núcleo, 2 GB RAM, 80 GB | — | US$ 30,00 |
| Cópia lógica independente, plano de 100 GB e franquia de 250 GB | US$ 3,00 | US$ 3,00 |
| 20 GB-mês de snapshots locais a US$ 0,05 | US$ 1,00 | US$ 1,00 |
| Reserva de planejamento para logs, coleta e alertas | US$ 4,00 | US$ 4,00 |
| Sobretaxa de tráfego no cenário-base | US$ 0,00 | US$ 0,00 |
| **Subtotal** | **US$ 32,00** | **US$ 62,00** |
| Conversão a R$ 5,50/US$ | R$ 176,00 | R$ 341,00 |
| Provisão comercial/fiscal de 15% sobre a conversão | R$ 26,40 | R$ 51,15 |
| Margem de 20% após a provisão | R$ 40,48 | R$ 78,43 |
| **Total de planejamento** | **R$ 242,88** | **R$ 470,58** |
| Espaço sob o teto proposto de R$ 500,00 | R$ 257,12 | R$ 29,42 |

Tarifas de instância, banco, armazenamento e snapshots vêm da tabela pública. A linha de observabilidade é uma provisão, pois depende da retenção dos logs, número de métricas e região. O câmbio de R$ 5,50 e os 15% são premissas de sensibilidade. A cobrança final depende do cadastro e da fatura aplicável. A consulta foi de tabela pública, sem proposta comercial com garantia de vigência. [Franquias regionais do Lightsail](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-faq-data-transfer-allowance.html), [Preço do CloudWatch](https://aws.amazon.com/cloudwatch/pricing/), [Faturamento da AWS no Brasil](https://aws.amazon.com/tax-help/Brazil/)

As duas opções usam as mesmas hipóteses de tráfego e guarda.

- Três projetos com 6 GB ao todo no teto lógico de evidências. Sete cópias lógicas de 6 GB ocupariam 42 GB. Uma cópia por dia durante 30 dias mais um restauro representaria 186 GB transferidos no mês, antes de metadados e considerando o tamanho nominal. O ensaio E8-011 restaurou um arquivo de aproximadamente 1,08 MB. A distribuição real entre dados, índices, WAL, metadados e compressão precisa de medição em escala.
- App com 30 GB/mês de tráfego de usuário como cenário de comparação e banco gerenciado com 30 GB/mês de tráfego cobrável hipotético. A franquia da instância de São Paulo é de 2 TB nesse plano e a do banco é de 100 GB. Validar na conta a cobrança das transferências entre regiões e o volume do banco no ensaio, inclusive o tráfego de consultas.
- Câmbio de R$ 5,00 a R$ 6,00 levaria o host único a **R$ 220,80–264,96** e o banco gerenciado padrão a **R$ 427,80–513,36**, mantidas provisões e margens. A variante gerenciada de alta disponibilidade troca US$ 30 por US$ 60 no banco e eleva a estimativa a **R$ 698,28** no cenário de R$ 5,50.

O host único concentra a falha da aplicação e do PostgreSQL e deixa com a equipe patches, monitoramento do banco e restauração. O banco gerenciado padrão desloca manutenção da instância e do motor para o provedor. A recuperação coordenada da aplicação, a cópia lógica e o teste de restauração seguem com a equipe nas duas opções. A alternativa gerenciada padrão mantém uma instância de banco, conforme a modalidade. A implantação gerenciada exige testar conectividade privada, SSL, permissões do Flyway, versão do PostgreSQL, egress e regras de cópia entre contas e regiões. [Manutenção e backups do banco gerenciado](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-changing-preferred-maintenance-and-backup-windows.html)

Horas internas de operação, manutenção, recuperação e apoio aos ensaios, além de VVB, Verra e outras taxas externas, ficam fora da tabela. A série local não mediu essas horas nem autorizou contratações. Engenharia mede e apresenta premissas. Leonardo define teto, responsáveis e hospedagem em PEN-008.
