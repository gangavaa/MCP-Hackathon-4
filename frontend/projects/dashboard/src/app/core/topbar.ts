import { Component, inject, input, signal } from '@angular/core';
import { ApiService, type Me } from './api.service';
import { AuthService } from './auth.service';
import { ThemeService } from './theme.service';

@Component({
  selector: 'nms-topbar',
  template: `
    <header class="sticky top-0 z-10 flex h-14 items-center justify-between bg-header px-6 text-white">
      <div class="font-semibold tracking-tight">
        <span class="mr-2 rounded-md bg-accent px-2 py-0.5 font-bold">NMS</span>{{ title() }}
      </div>
      <div class="flex items-center gap-4 text-sm">
        <ng-content />
        @if (live() !== undefined) {
          <span class="inline-flex items-center gap-1.5" [class.opacity-70]="!live()">
            <span
              class="size-2 rounded-full"
              [class]="live() ? 'bg-status-online animate-[pulse-dot_2s_infinite]' : 'bg-gray-400'"
            ></span>
            {{ live() ? 'Live' : 'Connecting…' }}
          </span>
        }
        @if (me(); as user) {
          <span class="text-right leading-tight" [title]="user.tenants.join(', ')">
            <span class="block font-semibold">{{ user.name }}</span>
            <span class="block text-[0.72rem] opacity-80">{{ user.tenants.join(', ') }}</span>
          </span>
          @if (auth.enabled()) {
            <button type="button" class="cursor-pointer rounded-lg border border-white/30 bg-transparent px-2.5 py-1 text-white" (click)="auth.logout()">
              Sign out
            </button>
          }
        }
        <button
          type="button"
          class="size-8 cursor-pointer rounded-lg border border-white/30 text-base"
          aria-label="Toggle dark mode"
          (click)="theme.toggle()"
        >
          ◐
        </button>
      </div>
    </header>
  `,
})
export class Topbar {
  readonly title = input('Network Management System');
  readonly live = input<boolean>();
  protected readonly theme = inject(ThemeService);
  protected readonly auth = inject(AuthService);
  protected readonly me = signal<Me | undefined>(undefined);

  constructor() {
    void inject(ApiService).me().then((me) => this.me.set(me), () => {});
  }
}
