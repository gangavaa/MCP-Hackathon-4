import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { ChartData, ChartType, QueryResult, Row } from './types';

echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

/*
 * One renderer for the panel and the dashboard, so a pinned chart looks exactly like the one that was pinned.
 * Colours are nms-core-ui's: the severity and status tokens of src/styles.css where a value has a meaning,
 * the brand blues otherwise.
 */

export type Theme = 'light' | 'dark';

/** MSD's palette, teal first: MSD Teal, light and deep teal, then navy, lime, sky and violet for further series. */
export const PALETTE = ['#00857c', '#6eceb2', '#005c55', '#0c2340', '#bfed33', '#69b8f7', '#5450e4', '#7a8c8a', '#a6dcd6'];
const PREVIOUS_COLOR = { light: '#b8c4d3', dark: '#4a5470' };

export const SEMANTIC: Record<string, string> = {
  CRITICAL: '#f87171', HIGH: '#fb923c', MEDIUM: '#fbbf24', LOW: '#6eceb2',
  ONLINE: '#00857c', OFFLINE: '#d84c57', WARNING: '#c88849', PARTIALLY_ONLINE: '#a1a131', UNKNOWN: '#707070',
  LOW_BATTERY: '#c88849', NOT_ON_COW: '#7048cc',
  OPEN: '#d84c57', CLOSED: '#7a8c8a',
  '5.16.0': '#d84c57',
};

const INK = {
  light: { text: '#1f2937', muted: '#637085', grid: '#e5e7eb' },
  dark: { text: '#e2e8f0', muted: '#8a96ab', grid: '#2d3352' },
};

const number = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 1 });

export function humanize(value: unknown): string {
  if (value == null || value === '') return '—';
  const text = String(value);
  if (/^[A-Z_]+$/.test(text)) return text.charAt(0) + text.slice(1).toLowerCase().replaceAll('_', ' ');
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return new Date(`${text}T00:00:00Z`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' });
  return text;
}

export function formatNumber(value: unknown): string {
  return typeof value === 'number' ? number.format(value) : value == null ? '—' : String(value);
}

// ---------------------------------------------------------------------------------------------- model

export interface ChartModel {
  categoryKey: string | null;
  categoryLabel: string;
  /** Raw values — what a drill-down filters on. */
  categories: string[];
  metricKey: string;
  metricLabel: string;
  series: { name: string; data: (number | null)[]; color?: string; previous?: boolean }[];
  isTime: boolean;
}

export function toModel(data: ChartData): ChartModel {
  const { result, previous, spec } = data;
  const dims = result.columns.filter((c) => c.kind === 'dimension');
  const metric = result.columns.find((c) => c.kind === 'metric')!;
  const [cat, split] = dims;
  const isTime = cat?.key === 'period';

  if (!cat) {
    const series = [{ name: metric.label, data: [num(result.rows[0]?.[metric.key])] }];
    if (previous) series.push({ name: previousLabel(spec.query.timeRange?.lastDays), data: [num(previous.rows[0]?.[metric.key])] });
    return { categoryKey: null, categoryLabel: '', categories: [metric.label], metricKey: metric.key, metricLabel: metric.label, series, isTime: false };
  }

  const categories = unique(result.rows.map((r) => String(r[cat.key])));

  if (split) {
    const names = unique(result.rows.map((r) => String(r[split.key])));
    const series = names.map((name, i) => ({
      name: humanize(name),
      color: SEMANTIC[name] ?? PALETTE[i % PALETTE.length],
      data: categories.map((c) => num(result.rows.find((r) => String(r[cat.key]) === c && String(r[split.key]) === name)?.[metric.key]) ?? 0),
    }));
    return { categoryKey: cat.key, categoryLabel: cat.label, categories, metricKey: metric.key, metricLabel: metric.label, series, isTime };
  }

  const series: ChartModel['series'] = [{ name: previous ? currentLabel(spec.query.timeRange?.lastDays) : metric.label, data: categories.map((c) => valueAt(result, cat.key, c, metric.key)) }];
  if (previous) {
    // A trend compares day n with day n of the window before; a ranking compares the same category.
    const prevData = isTime
      ? categories.map((_, i) => num(previous.rows[i]?.[metric.key]))
      : categories.map((c) => valueAt(previous, cat.key, c, metric.key) ?? 0);
    series.push({ name: previousLabel(spec.query.timeRange?.lastDays), data: prevData, previous: true });
  }
  return { categoryKey: cat.key, categoryLabel: cat.label, categories, metricKey: metric.key, metricLabel: metric.label, series, isTime };
}

const currentLabel = (days?: number) => (days === 7 ? 'This week' : days ? `Last ${days} days` : 'Current');
const previousLabel = (days?: number) => (days === 7 ? 'Week before' : days ? `Previous ${days} days` : 'Previous');

function valueAt(result: QueryResult, catKey: string, category: string, metricKey: string): number | null {
  return num(result.rows.find((r: Row) => String(r[catKey]) === category)?.[metricKey]);
}

function num(value: unknown): number | null {
  return typeof value === 'number' ? value : value == null ? null : Number(value);
}

function unique<T>(items: T[]): T[] {
  return [...new Set(items)];
}

// ---------------------------------------------------------------------------------------------- echarts

export function toOption(model: ChartModel, type: ChartType, theme: Theme, compact = false): echarts.EChartsCoreOption {
  const ink = INK[theme];
  const labels = model.categories.map(humanize);
  const single = model.series.length === 1;
  const colorFor = (i: number, raw: string) => SEMANTIC[raw] ?? PALETTE[i % PALETTE.length];
  const tooltip = { trigger: type === 'pie' || type === 'donut' ? 'item' : 'axis', valueFormatter: formatNumber, confine: true };
  const legend = { show: model.series.length > 1 || type === 'pie' || type === 'donut', bottom: 0, textStyle: { color: ink.muted }, icon: 'roundRect', itemWidth: 10, itemHeight: 10 };
  const base = { backgroundColor: 'transparent', textStyle: { fontFamily: 'Montserrat, system-ui, sans-serif', color: ink.text }, animationDuration: 600 };

  if (type === 'pie' || type === 'donut') {
    const s = model.series[0];
    return {
      ...base, tooltip, legend: { ...legend, type: 'scroll' },
      series: [{
        type: 'pie',
        radius: type === 'donut' ? ['48%', '72%'] : [0, '72%'],
        center: ['50%', '45%'],
        itemStyle: { borderColor: theme === 'dark' ? '#222839' : '#fff', borderWidth: 2, borderRadius: 4 },
        label: { show: !compact, color: ink.text, formatter: '{b}: {d}%' },
        data: model.categories.map((raw, i) => ({ name: labels[i], value: s.data[i], raw, itemStyle: { color: colorFor(i, raw) } })),
      }],
    };
  }

  const horizontal = type === 'hbar';
  const categoryAxis = {
    type: 'category', data: horizontal ? [...labels].reverse() : labels,
    axisLabel: { color: ink.muted, width: horizontal ? 130 : undefined, overflow: 'truncate', hideOverlap: true },
    axisLine: { lineStyle: { color: ink.grid } }, axisTick: { show: false },
  };
  const valueAxis = { type: 'value', axisLabel: { color: ink.muted, formatter: formatNumber }, splitLine: { lineStyle: { color: ink.grid } } };
  const order = <T>(values: T[]) => (horizontal ? [...values].reverse() : values);

  return {
    ...base, tooltip, legend,
    grid: { left: 8, right: 16, top: 16, bottom: legend.show ? 36 : 8, containLabel: true },
    xAxis: horizontal ? valueAxis : categoryAxis,
    yAxis: horizontal ? categoryAxis : valueAxis,
    series: model.series.map((s, si) => {
      const color = s.previous ? PREVIOUS_COLOR[theme] : s.color ?? PALETTE[si % PALETTE.length];
      if (type === 'line' || type === 'area') {
        return {
          type: 'line', name: s.name, data: s.data, smooth: true, showSymbol: model.categories.length < 20, symbolSize: 6,
          lineStyle: { width: s.previous ? 2 : 3, type: s.previous ? 'dashed' : 'solid', color }, itemStyle: { color },
          areaStyle: type === 'area' && !s.previous ? { opacity: 0.15, color } : undefined,
        };
      }
      return {
        type: 'bar', name: s.name, barMaxWidth: 36,
        itemStyle: { borderRadius: horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0], color }, // series colour feeds the legend
        data: order(s.data.map((value, i) => ({
          value,
          raw: model.categories[i],
          itemStyle: { color: single ? (SEMANTIC[model.categories[i]] ?? color) : color },
        }))),
      };
    }),
  };
}

// ---------------------------------------------------------------------------------------------- dom

export interface RenderOptions {
  theme: Theme;
  compact?: boolean;
  /** Called with the raw category value when the user clicks a bar or slice. */
  onSelect?: (category: string) => void;
}

export interface Rendered {
  resize(): void;
  dispose(): void;
}

/** Draws `data` as `type` into `el`, replacing whatever was there. */
export function render(el: HTMLElement, data: ChartData, type: ChartType, options: RenderOptions): Rendered {
  el.replaceChildren();
  const model = toModel(data);

  if (type === 'kpi') {
    el.append(kpi(model, data));
    return { resize() {}, dispose() {} };
  }
  if (type === 'table') {
    el.append(table(data.result));
    return { resize() {}, dispose() {} };
  }

  const canvas = document.createElement('div');
  canvas.className = 'chart-canvas';
  el.append(canvas);
  const chart = echarts.init(canvas, undefined, { renderer: 'canvas' });
  chart.setOption(toOption(model, type, options.theme, options.compact));
  if (options.onSelect && model.categoryKey && !model.isTime) {
    chart.on('click', (params) => {
      const raw = (params.data as { raw?: string } | undefined)?.raw;
      if (raw != null) options.onSelect!(raw);
    });
  }
  const observer = new ResizeObserver(() => chart.resize());
  observer.observe(canvas);
  return {
    resize: () => chart.resize(),
    dispose: () => (observer.disconnect(), chart.dispose()),
  };
}

function kpi(model: ChartModel, data: ChartData): HTMLElement {
  const current = model.series[0].data.reduce<number>((sum, v) => sum + (v ?? 0), 0);
  const wrap = el('div', 'kpi');
  wrap.append(el('div', 'kpi-value', formatNumber(current)), el('div', 'kpi-label', model.metricLabel));
  if (data.previous) {
    const previous = model.series[1]?.data.reduce<number>((sum, v) => sum + (v ?? 0), 0) ?? 0;
    const delta = previous === 0 ? null : ((current - previous) / previous) * 100;
    const up = delta != null && delta > 0;
    const text = delta == null ? 'no previous data' : `${up ? '▲' : delta < 0 ? '▼' : '■'} ${number.format(Math.abs(delta))}% vs ${previousLabel(data.spec.query.timeRange?.lastDays).toLowerCase()}`;
    wrap.append(el('div', `kpi-delta ${up ? 'up' : 'down'}`, text));
  }
  return wrap;
}

function table(result: QueryResult): HTMLElement {
  const wrap = el('div', 'table-wrap');
  const t = document.createElement('table');
  const head = t.createTHead().insertRow();
  for (const c of result.columns) {
    const th = document.createElement('th');
    th.textContent = c.label;
    if (c.kind === 'metric') th.className = 'num';
    head.append(th);
  }
  const body = t.createTBody();
  for (const row of result.rows) {
    const tr = body.insertRow();
    for (const c of result.columns) {
      const td = tr.insertCell();
      td.textContent = c.kind === 'metric' ? formatNumber(row[c.key]) : humanize(row[c.key]);
      if (c.kind === 'metric') td.className = 'num';
    }
  }
  wrap.append(t);
  return wrap;
}

function el(tag: string, className: string, text?: string): HTMLElement {
  const node = document.createElement(tag);
  node.className = className;
  if (text != null) node.textContent = text;
  return node;
}
