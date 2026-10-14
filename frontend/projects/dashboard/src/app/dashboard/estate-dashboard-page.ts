import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ChartComponent } from '@shared/chart.component';
import { PALETTE, SEMANTIC } from '@shared/render';
import type { ChartData, ChartSpec, QueryResult } from '@shared/types';
import { ApiService } from '../core/api.service';
import { ThemeService } from '../core/theme.service';
import { Spinner } from '../core/loading';
import { Topbar } from '../core/topbar';

const REFRESH_MS = 60_000;

const SEVERITIES = [
  { key: 'CRITICAL', label: 'Critical' },
  { key: 'HIGH', label: 'High' },
  { key: 'MEDIUM', label: 'Medium' },
  { key: 'LOW', label: 'Low' },
] as const;

type Severity = (typeof SEVERITIES)[number]['key'];
type SeverityCounts = Record<Severity, number>;

export interface TenantRow {
  name: string;
  specie: string;
  environment: string;
  farms: number;
  counts: SeverityCounts;
  total: number;
}

export interface SpecieGroup {
  specie: string;
  tenantCount: number;
  counts: SeverityCounts;
  total: number;
}

export interface Overview {
  counts: SeverityCounts;
  tenants: TenantRow[];
  species: SpecieGroup[];
}

type SortKey = 'name' | 'farms' | Severity | 'total';

const ALERTS_BY_SEVERITY: ChartSpec = {
  title: 'Alerts by Severity',
  chartType: 'donut',
  query: { dataset: 'issues', metric: 'open_count', groupBy: ['severity'], order: 'dimension' },
};

/**
 * The estate dashboard — fixed, as nms-core-ui's dashboard page is: severity summary cards, the tenants overview,
 * alerts by severity and the specie groups. Nothing is added to it from the chat; questions are answered there.
 * It reads the same semantic query layer the chat does, so it only ever shows the signed-in user's tenants.
 */
@Component({
  selector: 'nms-estate-dashboard-page',
  imports: [Topbar, RouterLink, ChartComponent, DatePipe, DecimalPipe, Spinner],
  template: `
    <nms-topbar />
    <main class="mx-auto max-w-[1600px] space-y-6 p-6">
      <header>
        <h1 class="m-0 font-heading text-2xl font-semibold">Dashboard</h1>
        @if (lastUpdated(); as at) {
          <p class="mt-1 mb-0 text-sm text-text-secondary">Last updated: {{ at | date: 'short' }}</p>
        }
      </header>

      @if (error(); as message) {
        <p class="rounded-xl bg-surface p-4 text-sm text-status-offline">Couldn't load the dashboard: {{ message }}</p>
      }

      <!-- Severity summary -->
      <section class="grid grid-cols-2 gap-4 md:grid-cols-4" aria-label="Open alerts by severity">
        @for (s of severities; track s.key) {
          <div role="group" class="rounded-xl p-5 shadow-sm" [attr.aria-label]="s.label + ' severity'" [style.background]="tint(s.key)">
            <div class="mb-2 flex items-center gap-2">
              <span class="size-3 rounded-full" aria-hidden="true" [style.background]="color(s.key)"></span>
              <span class="text-sm font-medium text-text-secondary">{{ s.label }}</span>
            </div>
            <span class="text-3xl font-bold tabular-nums">{{ (overview()?.counts?.[s.key] ?? 0) | number }}</span>
          </div>
        }
      </section>

      <!-- Tenants overview -->
      <section>
        <div class="flex items-center gap-3 px-1 pb-4">
          <h2 class="m-0 text-sm font-medium text-text-secondary">Tenants Overview</h2>
          <span class="rounded-full bg-link/10 px-2.5 py-0.5 text-xs font-medium text-link">
            {{ tenants().length }} tenant{{ tenants().length === 1 ? '' : 's' }}
          </span>
        </div>
        <div class="overflow-auto rounded-lg border border-border bg-surface">
          <table class="w-full min-w-[960px] border-separate border-spacing-0 text-sm">
            <thead class="sticky top-0 bg-surface-hover">
              <tr>
                <th scope="col" [class]="th + ' text-left'" [attr.aria-sort]="ariaSort('name')">
                  <button type="button" [class]="sortBtn" (click)="sortBy('name')">Name {{ arrow('name') }}</button>
                </th>
                <th scope="col" [class]="th + ' text-left'">Specie</th>
                <th scope="col" [class]="th + ' text-left'">Environment</th>
                <th scope="col" [class]="th + ' text-right'" [attr.aria-sort]="ariaSort('farms')">
                  <button type="button" [class]="sortBtn" (click)="sortBy('farms')">Farms {{ arrow('farms') }}</button>
                </th>
                @for (s of severities; track s.key) {
                  <th scope="col" [class]="th + ' text-right'" [attr.aria-sort]="ariaSort(s.key)">
                    <button type="button" [class]="sortBtn" (click)="sortBy(s.key)">{{ s.label }} {{ arrow(s.key) }}</button>
                  </th>
                }
                <th scope="col" [class]="th + ' text-right'" [attr.aria-sort]="ariaSort('total')">
                  <button type="button" [class]="sortBtn" (click)="sortBy('total')">Total {{ arrow('total') }}</button>
                </th>
                <th scope="col" [class]="th + ' w-[160px] text-left'">Severity Split</th>
              </tr>
            </thead>
            <tbody>
              @for (row of tenants(); track row.name) {
                <tr class="odd:bg-surface even:bg-surface-hover/50">
                  <th scope="row" class="px-3 py-2 text-left font-medium">
                    @if (tenantIds().get(row.name); as id) {
                      <a class="text-link no-underline hover:underline" [routerLink]="['/tenants', id]">{{ row.name }}</a>
                    } @else {
                      {{ row.name }}
                    }
                  </th>
                  <td class="px-3 py-2">{{ row.specie }}</td>
                  <td class="px-3 py-2">{{ row.environment }}</td>
                  <td class="px-3 py-2 text-right tabular-nums">{{ row.farms | number }}</td>
                  @for (s of severities; track s.key) {
                    <td class="px-3 py-2 text-right tabular-nums">{{ row.counts[s.key] | number }}</td>
                  }
                  <td class="px-3 py-2 text-right font-semibold tabular-nums">{{ row.total | number }}</td>
                  <td class="px-3 py-2">
                    <div class="flex h-1.5 w-full overflow-hidden rounded-full bg-surface-hover">
                      @for (s of segments(row); track s.key) {
                        <div [style.width.%]="s.width" [style.background]="color(s.key)" [title]="s.key"></div>
                      }
                    </div>
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="10" class="px-3 py-8 text-center text-text-secondary">
                    @if (overview()) {
                      No tenants to show.
                    } @else {
                      <nms-spinner label="Loading tenants…" />
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <div class="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <!-- Alerts by severity -->
        <section class="flex flex-col rounded-2xl bg-surface shadow-sm">
          <div class="border-b border-border px-6 py-4">
            <h2 class="m-0 text-sm font-medium text-text-secondary">Alerts by Severity</h2>
          </div>
          <div class="h-64 flex-1 p-6">
            @if (alertsChart(); as data) {
              <nms-chart [data]="data" type="donut" [theme]="theme.theme()" />
            }
          </div>
        </section>

        <!-- Specie groups -->
        <section aria-label="Specie groups">
          <ul role="list" class="m-0 flex list-none flex-col gap-4 p-0">
            @for (group of overview()?.species ?? []; track group.specie; let i = $index) {
              <li class="rounded-xl bg-surface p-4 shadow-sm">
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-3">
                    <span class="grid size-12 place-items-center rounded-full text-lg font-bold text-white" [style.background]="palette[i % palette.length]" aria-hidden="true">
                      {{ group.specie.charAt(0) }}
                    </span>
                    <div>
                      <span class="text-sm font-medium text-text-secondary">{{ group.specie }}</span>
                      <p class="mt-1 mb-0 text-sm font-semibold">{{ group.total | number }}</p>
                      <p class="m-0 text-xs text-text-secondary">Open alerts monitored</p>
                    </div>
                  </div>
                  <span class="text-xs text-text-secondary">{{ group.tenantCount }} tenant{{ group.tenantCount === 1 ? '' : 's' }}</span>
                </div>
                <div class="mt-3 flex h-1.5 w-full overflow-hidden rounded-full bg-surface-hover">
                  @for (s of segments(group); track s.key) {
                    <div [style.width.%]="s.width" [style.background]="color(s.key)" [title]="s.key"></div>
                  }
                </div>
              </li>
            } @empty {
              @if (overview()) {
                <li class="rounded-xl bg-surface p-4 text-center text-sm text-text-secondary">No specie groups available</li>
              }
            }
          </ul>
        </section>
      </div>
    </main>
  `,
})
export class EstateDashboardPage {
  protected readonly theme = inject(ThemeService);
  protected readonly severities = SEVERITIES;
  protected readonly palette = PALETTE;
  protected readonly th = 'border-b border-border px-3 py-2 text-xs font-medium whitespace-nowrap';
  protected readonly sortBtn = 'cursor-pointer border-0 bg-transparent p-0 font-medium text-text-primary';

  protected readonly overview = signal<Overview | undefined>(undefined);
  protected readonly alertsChart = signal<ChartData | undefined>(undefined);
  /** Tenant name → id, for the link to each tenant's page. */
  protected readonly tenantIds = signal(new Map<string, string>());
  protected readonly lastUpdated = signal<Date | undefined>(undefined);
  protected readonly error = signal<string | undefined>(undefined);
  protected readonly sort = signal<{ key: SortKey; dir: 1 | -1 }>({ key: 'total', dir: -1 });

  protected readonly tenants = computed(() => {
    const { key, dir } = this.sort();
    const value = (row: TenantRow) => (key === 'name' ? row.name : key === 'farms' ? row.farms : key === 'total' ? row.total : row.counts[key]);
    return [...(this.overview()?.tenants ?? [])].sort((a, b) => {
      const [x, y] = [value(a), value(b)];
      return dir * (typeof x === 'string' ? x.localeCompare(String(y)) : x - (y as number));
    });
  });

  readonly #api = inject(ApiService);

  constructor() {
    void this.#load();
    const timer = setInterval(() => void this.#load(), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected color(severity: Severity): string {
    return SEMANTIC[severity];
  }

  protected tint(severity: Severity): string {
    return `color-mix(in srgb, ${SEMANTIC[severity]} 14%, var(--color-surface))`;
  }

  protected segments(item: { counts: SeverityCounts; total: number }): { key: Severity; width: number }[] {
    if (!item.total) return [];
    return SEVERITIES.map((s) => ({ key: s.key, width: (item.counts[s.key] / item.total) * 100 })).filter((s) => s.width > 0);
  }

  protected sortBy(key: SortKey): void {
    this.sort.update((s) => ({ key, dir: s.key === key ? (s.dir === 1 ? -1 : 1) : key === 'name' ? 1 : -1 }));
  }

  protected arrow(key: SortKey): string {
    const s = this.sort();
    return s.key !== key ? '' : s.dir === 1 ? '↑' : '↓';
  }

  protected ariaSort(key: SortKey): string | null {
    const s = this.sort();
    return s.key !== key ? null : s.dir === 1 ? 'ascending' : 'descending';
  }

  async #load(): Promise<void> {
    try {
      const [alerts, species, environments, chart, tenants] = await Promise.all([
        this.#api.query({ dataset: 'issues', metric: 'open_count', groupBy: ['tenant', 'severity'], limit: 500 }),
        this.#api.query({ dataset: 'farms', metric: 'farm_count', groupBy: ['tenant', 'specie'], limit: 500 }),
        this.#api.query({ dataset: 'farms', metric: 'farm_count', groupBy: ['tenant', 'environment'], limit: 500 }),
        this.#api.chart(ALERTS_BY_SEVERITY),
        this.#api.tenants(),
      ]);
      this.tenantIds.set(new Map(tenants.map((t) => [t.name, t.id])));
      this.overview.set(toOverview(alerts, species, environments));
      this.alertsChart.set(chart);
      this.lastUpdated.set(new Date());
      this.error.set(undefined);
    } catch (e) {
      this.error.set(e instanceof Error ? e.message : String(e));
    }
  }
}

const emptyCounts = (): SeverityCounts => ({ CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 });
const isSeverity = (value: unknown): value is Severity => SEVERITIES.some((s) => s.key === value);

/**
 * Open alerts per tenant and severity, and farms per tenant by specie and by environment, folded into the rows the
 * page shows. A tenant is listed when it has farms; its specie and environment are those of its farms.
 */
export function toOverview(alerts: QueryResult, species: QueryResult, environments: QueryResult): Overview {
  const tenants = new Map<string, TenantRow>();
  const tenant = (name: string): TenantRow => {
    let row = tenants.get(name);
    if (!row) tenants.set(name, (row = { name, specie: '', environment: '', farms: 0, counts: emptyCounts(), total: 0 }));
    return row;
  };
  const join = (current: string, value: string) => (!current ? value : current.split(', ').includes(value) ? current : `${current}, ${value}`);

  for (const r of species.rows) {
    const row = tenant(String(r['tenant']));
    row.specie = join(row.specie, String(r['specie']));
    row.farms += Number(r['farm_count'] ?? 0);
  }
  for (const r of environments.rows) {
    const row = tenant(String(r['tenant']));
    row.environment = join(row.environment, String(r['environment']));
  }
  const counts = emptyCounts();
  for (const r of alerts.rows) {
    const severity = r['severity'];
    if (!isSeverity(severity)) continue;
    const n = Number(r['open_count'] ?? 0);
    const row = tenant(String(r['tenant']));
    row.counts[severity] += n;
    row.total += n;
    counts[severity] += n;
  }

  const groups = new Map<string, SpecieGroup>();
  for (const row of tenants.values()) {
    for (const specie of row.specie ? row.specie.split(', ') : []) {
      let group = groups.get(specie);
      if (!group) groups.set(specie, (group = { specie, tenantCount: 0, counts: emptyCounts(), total: 0 }));
      group.tenantCount++;
      for (const s of SEVERITIES) group.counts[s.key] += row.counts[s.key];
      group.total += row.total;
    }
  }

  return {
    counts,
    tenants: [...tenants.values()],
    species: [...groups.values()].sort((a, b) => b.total - a.total),
  };
}
