import { HttpInterceptorFn } from '@angular/common/http';
import { Component, Injectable, computed, inject, input, signal } from '@angular/core';
import { finalize } from 'rxjs';

/** How many calls to our own API are in flight; the progress bar shows while any is. */
@Injectable({ providedIn: 'root' })
export class LoadingService {
  readonly #pending = signal(0);
  readonly busy = computed(() => this.#pending() > 0);

  start(): void {
    this.#pending.update((n) => n + 1);
  }

  stop(): void {
    this.#pending.update((n) => Math.max(0, n - 1));
  }
}

export const loadingInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) return next(req);
  const loading = inject(LoadingService);
  loading.start();
  return next(req).pipe(finalize(() => loading.stop()));
};

/** A thin accent bar across the top of the page while the API is answering. */
@Component({
  selector: 'nms-progress-bar',
  host: { class: 'pointer-events-none fixed inset-x-0 top-0 z-50 h-0.5 overflow-hidden' },
  template: `
    @if (loading.busy()) {
      <div class="h-full w-1/3 bg-accent animate-[progress_1.1s_ease-in-out_infinite]" role="progressbar" aria-label="Loading"></div>
    }
  `,
})
export class ProgressBar {
  protected readonly loading = inject(LoadingService);
}

/** A spinning ring with a label, for a section still waiting on its data. */
@Component({
  selector: 'nms-spinner',
  host: { class: 'inline-flex items-center gap-2 text-text-secondary', role: 'status' },
  template: `
    <span class="size-4 shrink-0 animate-spin rounded-full border-2 border-border border-t-accent" aria-hidden="true"></span>
    <span>{{ label() }}</span>
  `,
})
export class Spinner {
  readonly label = input('Loading…');
}
