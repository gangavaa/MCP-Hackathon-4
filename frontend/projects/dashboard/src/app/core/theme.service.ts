import { Injectable, effect, signal } from '@angular/core';
import type { Theme } from '@shared/render';

/** Light or dark, as in nms-core-ui: a data-theme attribute the tokens switch on, remembered across visits. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<Theme>(localStorage.getItem('nms-theme') === 'dark' ? 'dark' : 'light');

  constructor() {
    effect(() => {
      document.documentElement.dataset['theme'] = this.theme();
      localStorage.setItem('nms-theme', this.theme());
    });
  }

  toggle(): void {
    this.theme.update((t) => (t === 'dark' ? 'light' : 'dark'));
  }
}
