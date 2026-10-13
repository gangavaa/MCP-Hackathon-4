import { Component, DestroyRef, ElementRef, effect, inject, input, output } from '@angular/core';
import { render, type Rendered, type Theme } from './render';
import type { ChartData, ChartType } from './types';

/**
 * One chart — ECharts, a KPI tile or a table, per `type` — drawn by the shared renderer, so the panel in the chat
 * and the pinned dashboard widget look exactly alike. Emits `select` with the raw category value on a click when
 * `drillable`.
 */
@Component({
  selector: 'nms-chart',
  template: '',
  host: { class: 'block h-full w-full' },
})
export class ChartComponent {
  readonly data = input.required<ChartData>();
  readonly type = input.required<ChartType>();
  readonly theme = input<Theme>('light');
  readonly compact = input(false);
  readonly drillable = input(false);
  readonly select = output<string>();

  readonly #el: HTMLElement = inject(ElementRef).nativeElement;
  #rendered: Rendered | undefined;

  constructor() {
    effect(() => {
      const data = this.data();
      const type = this.type();
      const options = {
        theme: this.theme(),
        compact: this.compact(),
        onSelect: this.drillable() ? (raw: string) => this.select.emit(raw) : undefined,
      };
      this.#rendered?.dispose();
      this.#rendered = undefined;
      if (data.result.rows.length === 0) {
        this.#el.replaceChildren(Object.assign(document.createElement('p'), { className: 'empty', textContent: 'No data matches this question.' }));
      } else {
        this.#rendered = render(this.#el, data, type, options);
      }
    });
    inject(DestroyRef).onDestroy(() => this.#rendered?.dispose());
  }
}
