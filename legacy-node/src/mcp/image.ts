import * as echarts from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';
import { existsSync } from 'node:fs';
import { Resvg } from '@resvg/resvg-js';
import { formatNumber, humanize, toModel, toOption } from '../shared/render.ts';
import type { ChartData, ChartType } from '../shared/types.ts';

echarts.use([SVGRenderer]);

/*
 * The chart as a PNG, drawn on this machine. Hosts that cannot frame the MCP App panel — Claude Desktop behind a
 * proxy that intercepts its sandbox domain — still show an image block natively, so every show_chart carries one.
 * Same model and options as the panel (src/shared/render.ts), so the picture matches the interactive chart.
 */

const WIDTH = 900;
const HEIGHT = 500;
const HEADER = 76;
const INK = '#1f2937';
const MUTED = '#637085';
const ACCENT = '#df117e';

/* Loading every system font costs ~1.5 s a chart; two files on macOS cover the text and the ▲▼ arrows. Elsewhere,
   fall back to the system fonts. */
const FONT_FILES = ['/System/Library/Fonts/Helvetica.ttc', '/System/Library/Fonts/Supplemental/Arial Unicode.ttf'].filter((f) => existsSync(f));
const FONTS = FONT_FILES.length
  ? { fontFiles: FONT_FILES, loadSystemFonts: false, defaultFontFamily: 'Helvetica' }
  : { loadSystemFonts: true, defaultFontFamily: 'Helvetica' };

export function renderChartPng(data: ChartData, type: ChartType = data.spec.chartType): Buffer {
  const body = type === 'kpi' ? kpiSvg(data) : type === 'table' ? tableSvg(data) : echartsSvg(data, type);
  const { title, insight } = data.spec;
  const height = type === 'kpi' ? 330 : type === 'table' ? Math.min(HEIGHT, HEADER + 70 + Math.min(data.result.rows.length, 12) * 28 + (insight ? 20 : 0)) : HEIGHT;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${WIDTH}" height="${height}" viewBox="0 0 ${WIDTH} ${height}" font-family="Helvetica, Arial, sans-serif">
  <rect width="100%" height="100%" rx="12" fill="#ffffff"/>
  <rect x="24" y="22" width="34" height="16" rx="3" fill="#004a75"/>
  <text x="41" y="34" font-size="10" font-weight="700" fill="#fff" text-anchor="middle">NMS</text>
  <text x="66" y="34" font-size="11" font-weight="600" fill="${MUTED}" letter-spacing="0.6">ASK THE DASHBOARD</text>
  <text x="24" y="60" font-size="20" font-weight="700" fill="${INK}">${xml(title)}</text>
  ${insight ? `<rect x="24" y="70" width="3" height="18" fill="${ACCENT}"/><text x="34" y="84" font-size="13" fill="${INK}">${xml(clip(insight, 120))}</text>` : ''}
  <g transform="translate(16 ${insight ? HEADER + 20 : HEADER})">${body}</g>
</svg>`;
  return new Resvg(svg, { fitTo: { mode: 'width', value: WIDTH * 2 }, font: FONTS }).render().asPng();
}

function echartsSvg(data: ChartData, type: ChartType): string {
  const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: WIDTH - 32, height: HEIGHT - HEADER - 36 });
  chart.setOption({ ...toOption(toModel(data), type, 'light'), animation: false });
  const svg = chart.renderToSVGString();
  chart.dispose();
  return svg;
}

function kpiSvg(data: ChartData): string {
  const model = toModel(data);
  const sum = (values: (number | null)[] = []) => values.reduce<number>((total, v) => total + (v ?? 0), 0);
  const current = sum(model.series[0]?.data);
  let delta = '';
  if (data.previous) {
    const previous = sum(model.series[1]?.data);
    const pct = previous ? ((current - previous) / previous) * 100 : null;
    if (pct != null) delta = `<text x="12" y="210" font-size="22" font-weight="700" font-style="normal" fill="${pct > 0 ? '#d84c57' : '#31c446'}">${pct > 0 ? '▲' : '▼'} ${formatNumber(Math.abs(pct))}% vs previous period</text>`;
  }
  return `<text x="10" y="130" font-size="96" font-weight="700" fill="#004a75">${formatNumber(current)}</text>
  <text x="12" y="170" font-size="20" fill="${MUTED}">${xml(model.metricLabel)}</text>${delta}`;
}

function tableSvg(data: ChartData): string {
  const { columns, rows } = data.result;
  const colWidth = (WIDTH - 48) / columns.length;
  const cell = (text: string, col: number, y: number, bold: boolean, numeric: boolean) =>
    `<text x="${numeric ? (col + 1) * colWidth - 12 : col * colWidth + 8}" y="${y}" font-size="14" ${bold ? `font-weight="600" fill="${MUTED}"` : `fill="${INK}"`} ${numeric ? 'text-anchor="end"' : ''}>${xml(clip(text, 40))}</text>`;
  const head = columns.map((c, i) => cell(c.label, i, 20, true, c.kind === 'metric')).join('');
  const shown = rows.slice(0, 12);
  const body = shown
    .map((row, r) => {
      const y = 50 + r * 28;
      return `<line x1="0" x2="${WIDTH - 48}" y1="${y - 19}" y2="${y - 19}" stroke="#e5e7eb"/>` +
        columns.map((c, i) => cell(c.kind === 'metric' ? formatNumber(row[c.key]) : humanize(row[c.key]), i, y, false, c.kind === 'metric')).join('');
    })
    .join('');
  const more = rows.length > shown.length ? `<text x="8" y="${50 + shown.length * 28}" font-size="13" fill="${MUTED}">… ${rows.length - shown.length} more rows</text>` : '';
  return head + body + more;
}

function xml(text: string): string {
  return text.replace(/[<>&"']/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' })[c]!);
}

function clip(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}
