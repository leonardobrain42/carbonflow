import {Component, computed, input} from '@angular/core';

interface Term {
  name: string;
  definition: string;
  sections: readonly string[];
}

const TERMS: readonly Term[] = [
  {name: 'Linha de base', definition: 'Cenário de referência que descreve o que ocorreria sem a atividade do projeto. Os dados e comparáveis sustentam essa hipótese.', sections: ['baseline', 'quantification']},
  {name: 'BE · Emissões de linha de base', definition: 'Emissões estimadas no cenário de referência para a fronteira e o período analisados.', sections: ['baseline', 'quantification']},
  {name: 'PE · Emissões do projeto', definition: 'Emissões atribuídas à atividade do projeto, conforme as fontes e os limites definidos para o cálculo.', sections: ['quantification', 'monitoring']},
  {name: 'LE · Fugas', definition: 'Mudanças de emissões fora da fronteira atribuíveis ao projeto. Sua incidência e tratamento dependem das regras aplicáveis.', sections: ['baseline', 'quantification']},
  {name: 'ER · Reduções de emissões', definition: 'Resultado da comparação entre linha de base, projeto e fugas conforme o método aplicável, expresso em tCO₂e.', sections: ['quantification']},
  {name: 'ED · Energia entregue', definition: 'Energia elétrica entregue ao veículo. Registre o medidor, a unidade e o intervalo de medição.', sections: ['monitoring', 'quantification']},
  {name: 'EC · Energia consumida', definition: 'Energia elétrica consumida pelo sistema de recarga na fronteira definida. Preserve as leituras separadas da energia entregue.', sections: ['monitoring', 'quantification']},
  {name: 'PD · Documento de descrição do projeto', definition: 'Descreve a atividade, a linha de base, a adicionalidade e o plano de monitoramento, junto com suas referências.', sections: ['applicability', 'baseline', 'additionality', 'safeguards']},
  {name: 'MR · Relatório de monitoramento', definition: 'Consolida, para um período definido, os dados monitorados, as evidências e os resultados de quantificação.', sections: ['monitoring', 'quantification']},
  {name: 'VVB · Organismo de validação e verificação', definition: 'Organização independente que avalia o projeto ou os resultados monitorados dentro do escopo contratado.', sections: ['applicability', 'safeguards', 'monitoring']},
  {name: 'VCU · Unidade de carbono verificada', definition: 'Unidade emitida no programa VCS e identificada no registro. A emissão deve ser comprovada com a referência do registro.', sections: ['quantification', 'applicability']},
  {name: 'Vintage · Ano de referência', definition: 'Ano ao qual se atribui a redução ou remoção. Um período de monitoramento pode abranger mais de um vintage.', sections: ['monitoring', 'quantification']},
];

@Component({
  selector: 'carbonflow-technical-glossary',
  template: `
    <details class="cf-journey-context">
      <summary>Glossário técnico</summary>
      <p>Explicações de apoio do CarbonFlow.</p>
      <dl>
        @for (term of terms(); track term.name) {
          <dt><strong>{{ term.name }}</strong></dt>
          <dd>{{ term.definition }}</dd>
        }
      </dl>
    </details>
  `,
  styles: ['dd { margin: .25rem 0 .8rem; color: #d1dfd7; }'],
})
export class TechnicalGlossaryComponent {
  readonly section = input<string | null>(null);
  readonly terms = computed(() => {
    const section = this.section();
    return section ? TERMS.filter(term => term.sections.includes(section)) : TERMS;
  });
}
