import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ChartComponent } from '@shared/chart.component';
import type { ChartData, Row } from '@shared/types';
import { ApiService, type FarmRow, type Tenant } from '../core/api.service';
import { ThemeService } from '../core/theme.service';
import { Spinner } from '../core/loading';
import { Topbar } from '../core/topbar';
import {
  CONNECTIVITY,
  SEVERITIES,
  SeverityBar,
  StatusDot,
  TableSort,
  countsOf,
  emptyCounts,
  isSeverity,
  localChart,
  severityColor,
  totalOf,
  type Severity,
  type SeverityCounts,
} from './estate-ui';

const REFRESH_MS = 60_000;
const TREND_DAYS = 14;

type FarmSortKey = 'name' | 'address' | 'country' | 'connectivityStatus' | 'devices' | 'offlineDevices' | 'lowBatteryDevices' | Severity | 'total';

interface AttentionRow {
  farm: FarmRow;
  reason: string;
}

/**
 * One tenant — nms-core-ui's tenant dashboard: Farm Overview, Alert Summary, Issue Definitions and Farms Needing
 * Attention, then every farm of the tenant, each opening its farm page.
 */
@Component({
  selector: 'nms-tenant-page',
  imports: [Topbar, RouterLink, ChartComponent, DatePipe, DecimalPipe, SeverityBar, StatusDot, Spinner],
  template: `
    <nms-topbar />
    <main class="mx-auto max-w-[1600px] space-y-6 p-6">
      <header>
        <nav class="flex items-center gap-2 text-sm text-text-secondary" aria-label="Breadcrumb">
          <a routerLink="/" class="text-text-secondary hover:text-link">Dashboard</a>
          <span>/</span>
          <span class="font-medium text-text-primary">{{ tenant()?.name ?? '…' }}</span>
        </nav>
        <h1 class="mt-1 mb-0 font-heading text-2xl font-semibold">{{ tenant()?.name ?? 'Tenant' }}</h1>
        @if (tenant(); as t) {
          <p class="mt-1 mb-0 text-sm text-text-secondary">
            {{ t.specie }} · {{ t.environment }} · {{ t.farms }} farms
            @if (lastUpdated(); as at) {
              · Last updated: {{ at | date: 'short' }}
            }
          </p>
        }
      </header>

      @if (error(); as message) {
        <p class="rounded-xl bg-surface p-4 text-sm text-status-offline">Couldn't load this tenant: {{ message }}</p>
      }

      <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <!-- Farm Overview -->
        <section [class]="card">
          <h2 [class]="cardTitle">Farm Overview</h2>
          <div class="h-64 p-4">
            @if (farmOverview(); as data) {
              <nms-chart [data]="data" type="donut" [theme]="theme.theme()" />
            }
          </div>
        </section>

        <!-- Alert Summary -->
        <section [class]="card + ' lg:col-span-2'">
          <h2 [class]="cardTitle">Alert Summary</h2>
          <div class="flex flex-col gap-3 p-4">
            <span class="text-3xl font-extrabold tabular-nums">{{ totalOpen() | number }}</span>
            <dl class="m-0">
              @for (s of severities; track s.key) {
                <div class="flex items-center border-b border-border py-2 last:border-b-0">
                  <dt class="w-16 shrink-0 text-xs font-medium">{{ s.label }}</dt>
                  <dd class="m-0 w-12 shrink-0 text-lg font-bold tabular-nums" [style.color]="color(s.key)">{{ open()[s.key] | number }}</dd>
                  <dd class="relative m-0 ml-4 min-w-0 flex-1">
                    <svg class="block h-8 w-full" [attr.viewBox]="'0 0 ' + (trendDays - 1) + ' 1'" preserveAspectRatio="none" role="img"
                      [attr.aria-label]="s.label + ' issues raised per day, last ' + trendDays + ' days'">
                      <polyline fill="none" vector-effect="non-scaling-stroke" stroke-width="2" [attr.stroke]="color(s.key)" [attr.points]="sparkline(s.key)" />
                    </svg>
                    <span class="absolute right-0 bottom-0 text-[8px] leading-none text-text-secondary">{{ trendDays }} days</span>
                  </dd>
                </div>
              }
            </dl>
          </div>
        </section>

        <!-- Issue Definitions -->
        <section [class]="card + ' lg:col-span-2'">
          <h2 [class]="cardTitle">Issue Definitions — open issues by type</h2>
          <div class="h-80 p-4">
            @if (issueTypes(); as data) {
              <nms-chart [data]="data" type="hbar" [theme]="theme.theme()" />
            }
          </div>
        </section>

        <!-- Farms Needing Attention -->
        <section [class]="card">
          <h2 [class]="cardTitle">Farms Needing Attention</h2>
          <ol role="list" class="m-0 flex list-none flex-col gap-1 p-2">
            @for (row of attention(); track row.farm.id) {
              <li>
                <a class="flex items-center justify-between gap-3 rounded-md px-2 py-1.5 text-text-primary no-underline hover:bg-surface-hover" [routerLink]="['/farms', row.farm.id]">
                  <span class="truncate text-sm font-medium">{{ row.farm.name }}</span>
                  <span class="flex shrink-0 items-center gap-2 text-xs text-text-secondary">
                    <span>{{ row.reason }}</span>
                    <nms-status [status]="row.farm.connectivityStatus" />
                  </span>
                </a>
              </li>
            } @empty {
              <li class="px-2 py-6 text-center text-sm text-text-secondary">
                @if (farms().length) {
                  No farms need attention
                } @else {
                  <nms-spinner />
                }
              </li>
            }
          </ol>
        </section>
      </div>

      <!-- Every farm -->
      <section>
        <div class="flex items-center gap-3 px-1 pb-4">
          <h2 class="m-0 text-sm font-medium text-text-secondary">Farms</h2>
          <span class="rounded-full bg-link/10 px-2.5 py-0.5 text-xs font-medium text-link">{{ farms().length }} farms</span>
        </div>
        <div class="overflow-auto rounded-lg border border-border bg-surface">
          <table class="w-full min-w-[1200px] border-separate border-spacing-0 text-sm">
            <thead class="sticky top-0 bg-surface-hover">
              <tr>
                @for (c of columns; track c.key) {
                  <th scope="col" [class]="th + (c.numeric ? ' text-right' : ' text-left')" [attr.aria-sort]="sort.aria(c.key)">
                    <button type="button" [class]="sortBtn" (click)="sort.by(c.key, c.numeric ? -1 : 1)">{{ c.label }} {{ sort.arrow(c.key) }}</button>
                  </th>
                }
                <th scope="col" [class]="th + ' w-[140px] text-left'">Severity Split</th>
                <th scope="col" [class]="th + ' text-left'">Version</th>
              </tr>
            </thead>
            <tbody>
              @for (farm of sortedFarms(); track farm.id) {
                <tr class="odd:bg-surface even:bg-surface-hover/50">
                  <th scope="row" class="px-3 py-2 text-left font-medium">
                    <a class="text-link no-underline hover:underline" [routerLink]="['/farms', farm.id]">{{ farm.name }}</a>
                  </th>
                  <td class="px-3 py-2">{{ farm.address }}</td>
                  <td class="px-3 py-2">{{ farm.country }}</td>
                  <td class="px-3 py-2"><nms-status [status]="farm.connectivityStatus" /></td>
                  <td class="px-3 py-2 text-right tabular-nums">{{ farm.devices | number }}</td>
                  <td class="px-3 py-2 text-right tabular-nums">{{ farm.offlineDevices | number }}</td>
                  <td class="px-3 py-2 text-right tabular-nums">{{ farm.lowBatteryDevices | number }}</td>
                  @for (s of severities; track s.key) {
                    <td class="px-3 py-2 text-right tabular-nums">{{ countsOf(farm)[s.key] | number }}</td>
                  }
                  <td class="px-3 py-2 text-right font-semibold tabular-nums">{{ totalOf(countsOf(farm)) | number }}</td>
                  <td class="px-3 py-2"><nms-severity-bar [counts]="countsOf(farm)" /></td>
                  <td class="px-3 py-2 text-text-secondary">{{ farm.version }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="14" class="px-3 py-8 text-center text-text-secondary">
                    @if (tenant()) {
                      No farms.
                    } @else {
                      <nms-spinner label="Loading farms…" />
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </main>
  `,
})
export class TenantPage {
  /** From the route: /tenants/:tenantId. */
  readonly tenantId = input.required<string>();

  protected readonly theme = inject(ThemeService);
  protected readonly severities = SEVERITIES;
  protected readonly trendDays = TREND_DAYS;
  protected readonly countsOf = countsOf;
  protected readonly totalOf = totalOf;
  protected readonly color = severityColor;
  protected readonly card = 'flex flex-col rounded-2xl bg-surface shadow-sm';
  protected readonly cardTitle = 'm-0 border-b border-border px-6 py-4 text-sm font-medium text-text-secondary';
  protected readonly th = 'border-b border-border px-3 py-2 text-xs font-medium whitespace-nowrap';
  protected readonly sortBtn = 'cursor-pointer border-0 bg-transparent p-0 font-medium text-text-primary';
  protected readonly columns: { key: FarmSortKey; label: string; numeric: boolean }[] = [
    { key: 'name', label: 'Farm', numeric: false },
    { key: 'address', label: 'Town', numeric: false },
    { key: 'country', label: 'Country', numeric: false },
    { key: 'connectivityStatus', label: 'Connectivity', numeric: false },
    { key: 'devices', label: 'Devices', numeric: true },
    { key: 'offlineDevices', label: 'Offline', numeric: true },
    { key: 'lowBatteryDevices', label: 'Low battery', numeric: true },
    ...SEVERITIES.map((s) => ({ key: s.key, label: s.label, numeric: true })),
    { key: 'total', label: 'Total', numeric: true },
  ];
  protected readonly sort = new TableSort<FarmSortKey>('total', -1);

  protected readonly tenant = signal<Tenant | undefined>(undefined);
  protected readonly farms = signal<FarmRow[]>([]);
  protected readonly issueTypes = signal<ChartData | undefined>(undefined);
  protected readonly trend = signal<Row[]>([]);
  protected readonly lastUpdated = signal<Date | undefined>(undefined);
  protected readonly error = signal<string | undefined>(undefined);

  protected readonly open = computed<SeverityCounts>(() => {
    const counts = emptyCounts();
    for (const farm of this.farms()) for (const s of SEVERITIES) counts[s.key] += countsOf(farm)[s.key];
    return counts;
  });
  protected readonly totalOpen = computed(() => totalOf(this.open()));

  protected readonly farmOverview = computed(() => {
    const farms = this.farms();
    if (!farms.length) return undefined;
    const rows = CONNECTIVITY.map((status) => ({ connectivity_status: status, farm_count: farms.filter((f) => f.connectivityStatus === status).length }))
      .filter((r) => r.farm_count > 0);
    return localChart('Farm Overview', 'donut', [
      { key: 'connectivity_status', label: 'Connectivity', kind: 'dimension' },
      { key: 'farm_count', label: 'Farms', kind: 'metric' },
    ], rows);
  });

  /** Critical and high issues first, then the most devices offline; a farm that is fully online and quiet isn't listed. */
  protected readonly attention = computed<AttentionRow[]>(() =>
    this.farms()
      .filter((f) => f.critical + f.high > 0 || f.connectivityStatus === 'OFFLINE' || f.connectivityStatus === 'PARTIALLY_ONLINE')
      .sort((a, b) => b.critical - a.critical || b.high - a.high || b.offlineDevices - a.offlineDevices)
      .slice(0, 8)
      .map((farm) => ({
        farm,
        reason: [
          farm.critical && `${farm.critical} critical`,
          farm.high && `${farm.high} high`,
          farm.offlineDevices && `${farm.offlineDevices} offline`,
        ].filter(Boolean).join(' · '),
      })),
  );

  protected readonly sortedFarms = computed(() =>
    this.sort.apply(this.farms(), (farm, key) => (isSeverity(key) ? countsOf(farm)[key] : key === 'total' ? totalOf(countsOf(farm)) : farm[key])),
  );

  readonly #api = inject(ApiService);

  constructor() {
    effect(() => void this.#load(this.tenantId()));
    const timer = setInterval(() => void this.#load(this.tenantId()), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  /** Issues raised per day over the trend window, as an SVG polyline in a 0…1 box. */
  protected sparkline(severity: Severity): string {
    const byDay = new Map<string, number>();
    for (const row of this.trend()) if (row['severity'] === severity) byDay.set(String(row['period']), Number(row['issue_count'] ?? 0));
    const days = [...new Set(this.trend().map((r) => String(r['period'])))].sort().slice(-TREND_DAYS);
    const values = days.map((d) => byDay.get(d) ?? 0);
    const max = Math.max(1, ...values);
    return values.map((v, i) => `${i},${1 - v / max}`).join(' ');
  }

  async #load(id: string): Promise<void> {
    try {
      const { tenant, farms } = await this.#api.tenant(id);
      const byTenant = [{ field: 'tenant', op: 'eq' as const, value: tenant.name }];
      const [issueTypes, trend] = await Promise.all([
        this.#api.chart({
          title: 'Open issues by type',
          chartType: 'hbar',
          query: { dataset: 'issues', metric: 'open_count', groupBy: ['issue_type'], filters: byTenant, order: 'metric_desc', limit: 10 },
        }),
        this.#api.query({ dataset: 'issues', metric: 'issue_count', groupBy: ['severity'], filters: byTenant, timeBucket: 'day', timeRange: { lastDays: TREND_DAYS }, limit: 500 }),
      ]);
      this.tenant.set(tenant);
      this.farms.set(farms);
      this.issueTypes.set(issueTypes);
      this.trend.set(trend.rows);
      this.lastUpdated.set(new Date());
      this.error.set(undefined);
    } catch (e) {
      this.error.set(e instanceof Error ? e.message : String(e));
    }
  }
}
