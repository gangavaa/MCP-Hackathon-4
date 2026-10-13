import { Component, computed, input, signal } from '@angular/core';
import { SEMANTIC, humanize } from '@shared/render';
import type { ChartData, ChartType, QueryColumn, Row } from '@shared/types';

/*
 * What the estate dashboard, the tenant page and the farm page share: nms-core-ui's severity and connectivity
 * vocabulary, the severity split bar, sortable table headers, and charts drawn from rows a page already holds.
 */

export const SEVERITIES = [
  { key: 'CRITICAL', label: 'Critical' },
  { key: 'HIGH', label: 'High' },
  { key: 'MEDIUM', label: 'Medium' },
  { key: 'LOW', label: 'Low' },
] as const;

export type Severity = (typeof SEVERITIES)[number]['key'];
export type SeverityCounts = Record<Severity, number>;

export const emptyCounts = (): SeverityCounts => ({ CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 });
export const isSeverity = (value: unknown): value is Severity => SEVERITIES.some((s) => s.key === value);
export const severityColor = (severity: string): string => SEMANTIC[severity] ?? '#707070';
export const severityTint = (severity: Severity): string => `color-mix(in srgb, ${SEMANTIC[severity]} 14%, var(--color-surface))`;

/** The counts a farm row carries as `critical`, `high`, `medium`, `low`. */
export function countsOf(row: { critical: number; high: number; medium: number; low: number }): SeverityCounts {
  return { CRITICAL: row.critical, HIGH: row.high, MEDIUM: row.medium, LOW: row.low };
}

export const totalOf = (counts: SeverityCounts): number => counts.CRITICAL + counts.HIGH + counts.MEDIUM + counts.LOW;

/** nms-core-ui's connectivity order, best to worst. */
export const CONNECTIVITY = ['ONLINE', 'PARTIALLY_ONLINE', 'WARNING', 'OFFLINE', 'UNKNOWN'] as const;

/** A coloured bar of the four severities, each as wide as its share. */
@Component({
  selector: 'nms-severity-bar',
  host: { class: 'flex h-1.5 w-full overflow-hidden rounded-full bg-surface-hover' },
  template: `
    @for (s of segments(); track s.key) {
      <div [style.width.%]="s.width" [style.background]="s.color" [title]="s.label + ': ' + s.count"></div>
    }
  `,
})
export class SeverityBar {
  readonly counts = input.required<SeverityCounts>();

  protected readonly segments = computed(() => {
    const counts = this.counts();
    const total = totalOf(counts);
    if (!total) return [];
    return SEVERITIES.filter((s) => counts[s.key] > 0).map((s) => ({
      key: s.key,
      label: s.label,
      count: counts[s.key],
      width: (counts[s.key] / total) * 100,
      color: severityColor(s.key),
    }));
  });
}

/** A connectivity or device status: a coloured dot and its name. */
@Component({
  selector: 'nms-status',
  host: { class: 'inline-flex items-center gap-1.5 whitespace-nowrap' },
  template: `
    <span class="size-2 shrink-0 rounded-full" aria-hidden="true" [style.background]="color()"></span>
    <span>{{ label() }}</span>
  `,
})
export class StatusDot {
  readonly status = input.required<string>();
  protected readonly color = computed(() => SEMANTIC[this.status()] ?? '#707070');
  protected readonly label = computed(() => humanize(this.status()));
}

/** A table's sort: one column at a time, a click on it again flips the direction. */
export class TableSort<K extends string> {
  readonly state;

  constructor(key: K, dir: 1 | -1) {
    this.state = signal({ key, dir });
  }

  by(key: K, firstDir: 1 | -1 = -1): void {
    this.state.update((s) => ({ key, dir: s.key === key ? (s.dir === 1 ? -1 : 1) : firstDir }));
  }

  arrow(key: K): string {
    const s = this.state();
    return s.key !== key ? '' : s.dir === 1 ? '↑' : '↓';
  }

  aria(key: K): string | null {
    const s = this.state();
    return s.key !== key ? null : s.dir === 1 ? 'ascending' : 'descending';
  }

  apply<T>(rows: readonly T[], value: (row: T, key: K) => string | number | null | undefined): T[] {
    const { key, dir } = this.state();
    return [...rows].sort((a, b) => {
      const [x, y] = [value(a, key), value(b, key)];
      if (x == null || y == null) return x == null ? (y == null ? 0 : 1) : -1;
      return dir * (typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y)));
    });
  }
}

/** Chart data from rows the page already holds, for the shared chart component. Nothing is queried. */
export function localChart(title: string, chartType: ChartType, columns: QueryColumn[], rows: Row[]): ChartData {
  return {
    spec: { title, chartType, query: { dataset: '', metric: columns.find((c) => c.kind === 'metric')?.key ?? '' } },
    result: { columns, rows, sql: '', anchor: '' },
  };
}
