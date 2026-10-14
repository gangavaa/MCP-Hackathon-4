import { DatePipe, DecimalPipe, NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ChartComponent } from '@shared/chart.component';
import { humanize } from '@shared/render';
import { ApiService, type Device, type FarmDetail, type Issue } from '../core/api.service';
import { ThemeService } from '../core/theme.service';
import { Spinner } from '../core/loading';
import { Topbar } from '../core/topbar';
import { SEVERITIES, StatusDot, TableSort, emptyCounts, isSeverity, localChart, severityColor, severityTint } from './estate-ui';

const REFRESH_MS = 60_000;

const TABS = [
  { key: 'overview', label: 'Overview' },
  { key: 'devices', label: 'Devices' },
  { key: 'issues', label: 'Issues' },
  { key: 'statistics', label: 'Statistics' },
  { key: 'properties', label: 'Properties' },
] as const;

type Tab = (typeof TABS)[number]['key'];
type DeviceSortKey = 'id' | 'type' | 'hardware' | 'firmware' | 'status' | 'battery' | 'lastSeen' | 'openIssues';
type IssueSortKey = 'severity' | 'description' | 'category' | 'deviceId' | 'createdAt' | 'resolvedAt';

const SEVERITY_RANK: Record<string, number> = { CRITICAL: 1, HIGH: 2, MEDIUM: 3, LOW: 4 };

/**
 * One farm — nms-core-ui's farm card: who and where it is, its open alerts by severity, its devices online and
 * offline by type, then the farm's devices, issues, connectivity history and properties.
 */
@Component({
  selector: 'nms-farm-page',
  imports: [Topbar, RouterLink, ChartComponent, DatePipe, DecimalPipe, NgTemplateOutlet, StatusDot, Spinner],
  template: `
    <nms-topbar />
    <main class="mx-auto max-w-[1600px] space-y-4 p-6">
      @if (error(); as message) {
        <p class="rounded-xl bg-surface p-4 text-sm text-status-offline">Couldn't load this farm: {{ message }}</p>
      }

      @if (detail(); as d) {
        <!-- Header -->
        <section class="grid gap-4 rounded-2xl bg-surface p-4 shadow-sm">
          <nav class="flex items-center gap-2 text-sm text-text-secondary" aria-label="Breadcrumb">
            <a routerLink="/" class="text-text-secondary hover:text-link">Dashboard</a>
            <span>/</span>
            <a [routerLink]="['/tenants', d.farm.tenantId]" class="text-text-secondary hover:text-link">{{ d.farm.tenantName }}</a>
            <span>/</span>
            <span class="font-medium text-text-primary">{{ d.farm.name }}</span>
          </nav>

          <div class="grid grid-cols-1 gap-4 lg:grid-cols-[150px_minmax(0,1fr)_minmax(0,1.3fr)] lg:items-start">
            <div class="flex flex-col items-center gap-2 rounded-lg bg-surface-hover p-3 text-center">
              <span class="grid size-14 place-items-center rounded-xl text-xl font-bold text-white" aria-hidden="true" [style.background]="d.farm.tenantColor">
                {{ initials(d.farm.tenantName) }}
              </span>
              <span class="text-xs font-semibold">{{ d.farm.tenantName }}</span>
            </div>

            <div class="rounded-lg bg-surface-hover p-3">
              <div class="flex items-center gap-3">
                <h1 class="m-0 font-heading text-xl font-semibold">{{ d.farm.name }}</h1>
                <nms-status class="text-sm text-text-secondary" [status]="d.farm.connectivityStatus" />
              </div>
              <div class="mt-3 space-y-1.5 text-sm text-text-secondary">
                <p class="m-0"><span class="font-medium">ID:</span> {{ d.farm.id }} <span class="mx-2">•</span> <span class="font-medium">Tenant:</span> {{ d.farm.tenantName }}</p>
                <p class="m-0"><span class="mr-1 font-medium">Owner:</span> <span class="text-text-primary">{{ d.farm.contractUser }}</span></p>
                <p class="m-0"><span class="mr-1 font-medium">Location:</span> <span class="text-text-primary">{{ d.farm.address }}, {{ d.farm.country }}</span></p>
                <p class="m-0">{{ d.farm.specie }} · {{ d.farm.environment }} · {{ d.farm.animals | number }} animals</p>
              </div>
            </div>

            <div class="grid gap-3">
              <div class="grid grid-cols-2 gap-3 sm:grid-cols-4" aria-label="Open alerts by severity">
                @for (s of severities; track s.key) {
                  <div class="rounded-lg p-3" [style.background]="tint(s.key)">
                    <div class="flex items-center gap-1.5 text-xs font-medium text-text-secondary">
                      <span class="size-2 rounded-full" [style.background]="color(s.key)"></span>{{ s.label }}
                    </div>
                    <span class="text-2xl font-bold tabular-nums">{{ openBySeverity()[s.key] | number }}</span>
                  </div>
                }
              </div>
              <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
                @for (m of deviceMetrics(); track m.type) {
                  <div class="rounded-lg border border-border p-3">
                    <div class="text-xs font-medium text-text-secondary">{{ m.type }}</div>
                    <div class="mt-1 flex items-baseline gap-3 text-sm">
                      <span><span class="text-lg font-bold tabular-nums text-status-online">{{ m.online }}</span> online</span>
                      <span><span class="text-lg font-bold tabular-nums" [class]="m.offline ? 'text-status-offline' : 'text-text-secondary'">{{ m.offline }}</span> offline</span>
                    </div>
                    @if (m.detail) {
                      <div class="mt-0.5 text-[0.72rem] text-text-secondary">{{ m.detail }}</div>
                    }
                  </div>
                }
              </div>
            </div>
          </div>
        </section>

        <!-- Menu -->
        <nav class="flex gap-1 border-b border-border" aria-label="Farm sections">
          @for (t of tabs; track t.key) {
            <button
              type="button"
              class="-mb-px cursor-pointer border-0 border-b-2 bg-transparent px-4 py-2 text-sm font-medium"
              [class]="tab() === t.key ? 'border-accent text-text-primary' : 'border-transparent text-text-secondary hover:text-text-primary'"
              [attr.aria-current]="tab() === t.key ? 'page' : null"
              (click)="tab.set(t.key)"
            >
              {{ t.label }}
              @if (t.key === 'devices') { <span class="text-text-secondary">({{ d.devices.length }})</span> }
              @if (t.key === 'issues') { <span class="text-text-secondary">({{ openIssues().length }} open)</span> }
            </button>
          }
        </nav>

        @switch (tab()) {
          @case ('overview') {
            <div class="grid grid-cols-1 gap-4 xl:grid-cols-2">
              <section>
                <h3 class="m-0 mb-2 text-base font-semibold">Devices needing attention</h3>
                <ng-container [ngTemplateOutlet]="devicesTable" [ngTemplateOutletContext]="{ $implicit: attentionDevices(), compact: true }" />
              </section>
              <section>
                <h3 class="m-0 mb-2 text-base font-semibold">Open issues</h3>
                <ng-container [ngTemplateOutlet]="issuesTable" [ngTemplateOutletContext]="{ $implicit: openIssues().slice(0, 15), compact: true }" />
              </section>
            </div>
          }
          @case ('devices') {
            <div class="mb-2 flex flex-wrap items-center gap-2">
              @for (s of statusFilters(); track s.status) {
                <button
                  type="button"
                  class="cursor-pointer rounded-full border px-3 py-1 text-xs font-medium"
                  [class]="deviceStatus() === s.status ? 'border-link bg-link/10 text-link' : 'border-border bg-surface text-text-secondary'"
                  (click)="deviceStatus.set(s.status)"
                >
                  {{ s.label }} ({{ s.count }})
                </button>
              }
            </div>
            <ng-container [ngTemplateOutlet]="devicesTable" [ngTemplateOutletContext]="{ $implicit: filteredDevices(), compact: false }" />
          }
          @case ('issues') {
            <div class="mb-2 inline-flex rounded-lg bg-surface-hover p-1">
              @for (o of issueScopes; track o.key) {
                <button
                  type="button"
                  class="cursor-pointer rounded-md border-0 px-3 py-1 text-xs font-medium"
                  [class]="issueScope() === o.key ? 'bg-surface text-text-primary shadow-sm' : 'bg-transparent text-text-secondary'"
                  (click)="issueScope.set(o.key)"
                >
                  {{ o.label }}
                </button>
              }
            </div>
            <ng-container [ngTemplateOutlet]="issuesTable" [ngTemplateOutletContext]="{ $implicit: scopedIssues(), compact: false }" />
          }
          @case ('statistics') {
            <div class="grid grid-cols-1 gap-4 xl:grid-cols-3">
              <section [class]="card + ' xl:col-span-2'">
                <h3 [class]="cardTitle">Devices online and offline — last 30 days</h3>
                <div class="h-80 p-4">
                  @if (connectivityChart(); as data) {
                    <nms-chart [data]="data" type="area" [theme]="theme.theme()" />
                  }
                </div>
              </section>
              <section [class]="card">
                <h3 [class]="cardTitle">Devices by status</h3>
                <div class="h-80 p-4">
                  @if (statusChart(); as data) {
                    <nms-chart [data]="data" type="donut" [theme]="theme.theme()" />
                  }
                </div>
              </section>
              <section [class]="card + ' xl:col-span-3'">
                <h3 [class]="cardTitle">Devices by firmware and status</h3>
                <div class="h-72 p-4">
                  @if (firmwareChart(); as data) {
                    <nms-chart [data]="data" type="bar" [theme]="theme.theme()" />
                  }
                </div>
              </section>
            </div>
          }
          @case ('properties') {
            <section [class]="card">
              <dl class="m-0 grid grid-cols-1 gap-x-8 p-4 sm:grid-cols-2">
                @for (p of properties(); track p.label) {
                  <div class="flex justify-between gap-4 border-b border-border py-2 text-sm">
                    <dt class="text-text-secondary">{{ p.label }}</dt>
                    <dd class="m-0 text-right font-medium">{{ p.value }}</dd>
                  </div>
                }
              </dl>
            </section>
          }
        }
      } @else if (!error()) {
        <nms-spinner class="py-6 text-sm" label="Loading farm…" />
      }
    </main>

    <ng-template #devicesTable let-devices let-compact="compact">
      <div class="overflow-auto rounded-lg border border-border bg-surface" [style.max-height.px]="compact ? 420 : null">
        <table class="w-full border-separate border-spacing-0 text-sm" [style.min-width.px]="compact ? null : 900">
          <thead class="sticky top-0 bg-surface-hover">
            <tr>
              @for (c of deviceColumns; track c.key) {
                @if (!compact || c.compact) {
                  <th scope="col" [class]="th + (c.numeric ? ' text-right' : ' text-left')" [attr.aria-sort]="deviceSort.aria(c.key)">
                    <button type="button" [class]="sortBtn" (click)="deviceSort.by(c.key, c.numeric ? -1 : 1)">{{ c.label }} {{ deviceSort.arrow(c.key) }}</button>
                  </th>
                }
              }
            </tr>
          </thead>
          <tbody>
            @for (dev of devices; track dev.id) {
              <tr class="odd:bg-surface even:bg-surface-hover/50">
                <th scope="row" class="px-3 py-2 text-left font-mono text-xs font-medium">{{ dev.id }}</th>
                <td class="px-3 py-2">{{ dev.type }}</td>
                @if (!compact) {
                  <td class="px-3 py-2">{{ dev.hardware }}</td>
                }
                <td class="px-3 py-2">{{ dev.firmware }}</td>
                <td class="px-3 py-2"><nms-status [status]="dev.status" /></td>
                <td class="px-3 py-2 text-right tabular-nums" [class.text-status-offline]="dev.battery < 20">{{ dev.battery }}%</td>
                @if (!compact) {
                  <td class="px-3 py-2 whitespace-nowrap text-text-secondary">{{ dev.lastSeen | date: 'd MMM, HH:mm' : 'UTC' }}</td>
                }
                <td class="px-3 py-2 text-right tabular-nums">{{ dev.openIssues }}</td>
              </tr>
            } @empty {
              <tr><td colspan="8" class="px-3 py-6 text-center text-text-secondary">No devices.</td></tr>
            }
          </tbody>
        </table>
      </div>
    </ng-template>

    <ng-template #issuesTable let-issues let-compact="compact">
      <div class="overflow-auto rounded-lg border border-border bg-surface" [style.max-height.px]="compact ? 420 : null">
        <table class="w-full border-separate border-spacing-0 text-sm" [style.min-width.px]="compact ? null : 900">
          <thead class="sticky top-0 bg-surface-hover">
            <tr>
              @for (c of issueColumns; track c.key) {
                @if (!compact || c.compact) {
                  <th scope="col" [class]="th + ' text-left'" [attr.aria-sort]="issueSort.aria(c.key)">
                    <button type="button" [class]="sortBtn" (click)="issueSort.by(c.key, c.key === 'createdAt' || c.key === 'resolvedAt' ? -1 : 1)">{{ c.label }} {{ issueSort.arrow(c.key) }}</button>
                  </th>
                }
              }
            </tr>
          </thead>
          <tbody>
            @for (issue of issues; track issue.id) {
              <tr class="odd:bg-surface even:bg-surface-hover/50">
                <td class="px-3 py-2">
                  <span class="inline-flex items-center gap-1.5 rounded px-1.5 py-0.5 text-xs font-semibold" [style.background]="tint(issue.severity)">
                    <span class="size-2 rounded-full" [style.background]="color(issue.severity)"></span>{{ humanize(issue.severity) }}
                  </span>
                </td>
                <th scope="row" class="px-3 py-2 text-left font-medium">{{ issue.description }}</th>
                @if (!compact) {
                  <td class="px-3 py-2">{{ issue.category }}</td>
                }
                <td class="px-3 py-2 font-mono text-xs">{{ issue.deviceId }}@if (issue.deviceType) { <span class="font-sans text-text-secondary"> · {{ issue.deviceType }}</span> }</td>
                <td class="px-3 py-2 whitespace-nowrap text-text-secondary">{{ issue.createdAt | date: 'd MMM, HH:mm' : 'UTC' }}</td>
                @if (!compact) {
                  <td class="px-3 py-2 whitespace-nowrap text-text-secondary">{{ issue.resolvedAt ? (issue.resolvedAt | date: 'd MMM, HH:mm' : 'UTC') : 'Open' }}</td>
                }
              </tr>
            } @empty {
              <tr><td colspan="6" class="px-3 py-6 text-center text-text-secondary">No issues.</td></tr>
            }
          </tbody>
        </table>
      </div>
    </ng-template>
  `,
})
export class FarmPage {
  /** From the route: /farms/:farmId. */
  readonly farmId = input.required<string>();

  protected readonly theme = inject(ThemeService);
  protected readonly severities = SEVERITIES;
  protected readonly tabs = TABS;
  protected readonly humanize = humanize;
  protected readonly color = severityColor;
  protected readonly card = 'flex flex-col rounded-2xl bg-surface shadow-sm';
  protected readonly cardTitle = 'm-0 border-b border-border px-6 py-4 text-sm font-medium text-text-secondary';
  protected readonly th = 'border-b border-border px-3 py-2 text-xs font-medium whitespace-nowrap';
  protected readonly sortBtn = 'cursor-pointer border-0 bg-transparent p-0 font-medium text-text-primary';
  protected readonly issueScopes = [
    { key: 'open', label: 'Open' },
    { key: 'all', label: 'All (60 days)' },
  ] as const;
  protected readonly deviceColumns: { key: DeviceSortKey; label: string; numeric: boolean; compact: boolean }[] = [
    { key: 'id', label: 'Device', numeric: false, compact: true },
    { key: 'type', label: 'Type', numeric: false, compact: true },
    { key: 'hardware', label: 'Hardware', numeric: false, compact: false },
    { key: 'firmware', label: 'Firmware', numeric: false, compact: true },
    { key: 'status', label: 'Status', numeric: false, compact: true },
    { key: 'battery', label: 'Battery', numeric: true, compact: true },
    { key: 'lastSeen', label: 'Last seen', numeric: false, compact: false },
    { key: 'openIssues', label: 'Open issues', numeric: true, compact: true },
  ];
  protected readonly issueColumns: { key: IssueSortKey; label: string; compact: boolean }[] = [
    { key: 'severity', label: 'Severity', compact: true },
    { key: 'description', label: 'Issue', compact: true },
    { key: 'category', label: 'Category', compact: false },
    { key: 'deviceId', label: 'Device', compact: true },
    { key: 'createdAt', label: 'Raised', compact: true },
    { key: 'resolvedAt', label: 'Resolved', compact: false },
  ];
  protected readonly deviceSort = new TableSort<DeviceSortKey>('status', 1);
  protected readonly issueSort = new TableSort<IssueSortKey>('severity', 1);

  protected readonly tab = signal<Tab>('overview');
  protected readonly deviceStatus = signal<string>('ALL');
  protected readonly issueScope = signal<'open' | 'all'>('open');
  protected readonly detail = signal<FarmDetail | undefined>(undefined);
  protected readonly error = signal<string | undefined>(undefined);

  readonly #devices = computed(() => this.detail()?.devices ?? []);
  readonly #issues = computed(() => this.detail()?.issues ?? []);

  protected readonly openIssues = computed(() => this.#sortIssues(this.#issues().filter((i) => !i.resolvedAt)));
  protected readonly scopedIssues = computed(() => (this.issueScope() === 'open' ? this.openIssues() : this.#sortIssues(this.#issues())));

  protected readonly openBySeverity = computed(() => {
    const counts = emptyCounts();
    for (const issue of this.#issues()) if (!issue.resolvedAt && isSeverity(issue.severity)) counts[issue.severity]++;
    return counts;
  });

  /** One card per device type, as the farm card's metrics row: online against offline. */
  protected readonly deviceMetrics = computed(() => {
    const byType = new Map<string, Device[]>();
    for (const d of this.#devices()) byType.set(d.type, [...(byType.get(d.type) ?? []), d]);
    return [...byType.entries()].map(([type, devices]) => {
      const lowBattery = devices.filter((d) => d.status === 'LOW_BATTERY').length;
      const notOnCow = devices.filter((d) => d.status === 'NOT_ON_COW').length;
      return {
        type,
        online: devices.filter((d) => d.status !== 'OFFLINE').length,
        offline: devices.filter((d) => d.status === 'OFFLINE').length,
        detail: [lowBattery && `${lowBattery} low battery`, notOnCow && `${notOnCow} not on cow`].filter(Boolean).join(' · '),
      };
    });
  });

  protected readonly attentionDevices = computed(() => this.#sortDevices(this.#devices().filter((d) => d.status !== 'ONLINE' || d.openIssues > 0)));

  protected readonly statusFilters = computed(() => {
    const devices = this.#devices();
    const statuses = [...new Set(devices.map((d) => d.status))].sort();
    return [
      { status: 'ALL', label: 'All', count: devices.length },
      ...statuses.map((status) => ({ status, label: humanize(status), count: devices.filter((d) => d.status === status).length })),
    ];
  });

  protected readonly filteredDevices = computed(() => {
    const status = this.deviceStatus();
    return this.#sortDevices(status === 'ALL' ? this.#devices() : this.#devices().filter((d) => d.status === status));
  });

  protected readonly connectivityChart = computed(() => {
    const days = this.detail()?.connectivity ?? [];
    if (!days.length) return undefined;
    return localChart('Devices online and offline', 'area', [
      { key: 'period', label: 'Day', kind: 'dimension' },
      { key: 'status', label: 'Status', kind: 'dimension' },
      { key: 'devices', label: 'Devices', kind: 'metric' },
    ], days.flatMap((d) => [
      { period: d.day, status: 'ONLINE', devices: d.online },
      { period: d.day, status: 'OFFLINE', devices: d.offline },
    ]));
  });

  protected readonly statusChart = computed(() => {
    const filters = this.statusFilters().filter((s) => s.status !== 'ALL');
    if (!filters.length) return undefined;
    return localChart('Devices by status', 'donut', [
      { key: 'status', label: 'Status', kind: 'dimension' },
      { key: 'devices', label: 'Devices', kind: 'metric' },
    ], filters.map((s) => ({ status: s.status, devices: s.count })));
  });

  protected readonly firmwareChart = computed(() => {
    const devices = this.#devices();
    if (!devices.length) return undefined;
    const rows = new Map<string, { firmware: string; status: string; devices: number }>();
    for (const d of devices) {
      const key = `${d.firmware}|${d.status}`;
      const row = rows.get(key) ?? { firmware: d.firmware, status: d.status, devices: 0 };
      row.devices++;
      rows.set(key, row);
    }
    return localChart('Devices by firmware and status', 'bar', [
      { key: 'firmware', label: 'Firmware', kind: 'dimension' },
      { key: 'status', label: 'Status', kind: 'dimension' },
      { key: 'devices', label: 'Devices', kind: 'metric' },
    ], [...rows.values()].sort((a, b) => a.firmware.localeCompare(b.firmware, undefined, { numeric: true })));
  });

  protected readonly properties = computed(() => {
    const f = this.detail()?.farm;
    if (!f) return [];
    const date = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }) : '—');
    return [
      { label: 'Farm ID', value: f.id },
      { label: 'Farm name', value: f.name },
      { label: 'Family name', value: f.farmName },
      { label: 'Contract user', value: f.contractUser },
      { label: 'Tenant', value: f.tenantName },
      { label: 'Specie', value: f.specie },
      { label: 'Environment', value: f.environment },
      { label: 'Connectivity', value: humanize(f.connectivityStatus) },
      { label: 'Farm server version', value: f.version },
      { label: 'Protocol', value: f.protocol },
      { label: 'Animals on farm', value: f.animals.toLocaleString('en-GB') },
      { label: 'Address', value: f.address },
      { label: 'Country', value: f.country },
      { label: 'Latitude, longitude', value: `${f.latitude.toFixed(4)}, ${f.longitude.toFixed(4)}` },
      { label: 'Registered', value: date(f.registeredAt) },
      { label: 'Last upgrade', value: date(f.lastUpgradeAt) },
    ];
  });

  readonly #api = inject(ApiService);

  constructor() {
    effect(() => void this.#load(this.farmId()));
    const timer = setInterval(() => void this.#load(this.farmId()), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected tint(severity: string): string {
    return isSeverity(severity) ? severityTint(severity) : 'var(--color-surface-hover)';
  }

  protected initials(name: string): string {
    return name.split(/\s+/).map((w) => w[0]).join('').slice(0, 2).toUpperCase();
  }

  #sortDevices(devices: Device[]): Device[] {
    // Status sorts worst first: offline, then low battery, then the rest.
    const rank: Record<string, number> = { OFFLINE: 0, LOW_BATTERY: 1, NOT_ON_COW: 2, ONLINE: 3 };
    return this.deviceSort.apply(devices, (d, key) => (key === 'status' ? (rank[d.status] ?? 4) : d[key]));
  }

  #sortIssues(issues: Issue[]): Issue[] {
    return this.issueSort.apply(issues, (i, key) => (key === 'severity' ? (SEVERITY_RANK[i.severity] ?? 5) : i[key]));
  }

  async #load(id: string): Promise<void> {
    try {
      this.detail.set(await this.#api.farm(id));
      this.error.set(undefined);
    } catch (e) {
      this.error.set(e instanceof Error ? e.message : String(e));
    }
  }
}
