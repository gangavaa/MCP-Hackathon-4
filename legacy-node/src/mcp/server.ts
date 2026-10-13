import { readFile } from 'node:fs/promises';
import { McpServer } from '@modelcontextprotocol/server';
import { StdioServerTransport } from '@modelcontextprotocol/server/stdio';
import { registerAppResource, registerAppTool, RESOURCE_MIME_TYPE } from '@modelcontextprotocol/ext-apps/server';
import { z } from 'zod';
import { loadEnv } from '../api/env.ts';
import { renderChartPng } from './image.ts';
import type { ChartData, DatasetInfo, QueryResult, Widget } from '../shared/types.ts';

/*
 * The MCP server Claude talks to. It holds no data: every tool is a call to the API (src/api/server.ts), which
 * is the one process that owns Postgres. `show_chart` is the MCP App — its result renders the panel in
 * src/panel/ inside the chat. Logs go to stderr; stdout is the MCP channel.
 */

loadEnv();
const API_URL = process.env.API_URL ?? `http://localhost:${process.env.PORT ?? 4300}`;
const PANEL_URI = 'ui://nms/ask-dashboard.html';
const PANEL_FILE = new URL('../../dist/panel/index.html', import.meta.url);
/** A PNG of every chart, for hosts that cannot frame the panel. CHART_IMAGES=0 turns it off. */
const CHART_IMAGES = process.env.CHART_IMAGES !== '0';

// ---------------------------------------------------------------------------------------------- schemas

const filterSchema = z.object({
  field: z.string().describe('A dimension key of the dataset (or a numeric filter field such as battery_pct).'),
  op: z.enum(['eq', 'neq', 'in', 'contains', 'gte', 'lte']),
  value: z.union([z.string(), z.number(), z.array(z.union([z.string(), z.number()]))]),
});

const querySchema = z.object({
  dataset: z.string().describe('farms | devices | issues | connectivity — see list_datasets.'),
  metric: z.string().describe('A metric key of the dataset, e.g. offline_count.'),
  groupBy: z.array(z.string()).max(2).optional().describe('Up to two dimension keys: the category axis, then the series.'),
  filters: z.array(filterSchema).optional().describe('Use the exact values list_datasets lists for a dimension.'),
  timeRange: z
    .object({
      lastDays: z.number().int().min(1).max(366),
      offsetDays: z.number().int().min(0).optional().describe('Shift the window back, e.g. 7 for "the week before".'),
    })
    .optional()
    .describe("Relative to the data's anchor date. Omit for all time (60 days of history)."),
  timeBucket: z.enum(['day', 'week']).optional().describe('Adds a leading period dimension — use for trends.'),
  order: z.enum(['metric_desc', 'metric_asc', 'dimension']).optional(),
  limit: z.number().int().min(1).max(500).optional(),
});

const chartSpecSchema = z.object({
  title: z.string().describe('Short, specific, e.g. "Offline devices by farm".'),
  chartType: z.enum(['bar', 'hbar', 'line', 'area', 'pie', 'donut', 'table', 'kpi']).describe(
    'bar/hbar for rankings (hbar when labels are long, e.g. farms), line/area for timeBucket trends, pie/donut for a share of up to ~6 parts, kpi for one number, table for detail.',
  ),
  query: querySchema,
  compare: z.literal('previous_period').optional().describe('Adds the window just before as a second series — needs query.timeRange.'),
  insight: z.string().optional().describe('One sentence on what the data shows, written after looking at it. Shown under the title.'),
});

// ---------------------------------------------------------------------------------------------- server

const server = new McpServer({ name: 'nms-ask-dashboard', version: '0.1.0' });

server.registerTool(
  'list_datasets',
  {
    title: 'List NMS datasets',
    description:
      'Lists the NMS estate data that can be charted: datasets (farms, devices, issues, connectivity history), their dimensions (with the exact values filters must use) and metrics. Call this first, before query_data or show_chart.',
    inputSchema: z.object({}),
    annotations: { readOnlyHint: true },
  },
  async () => {
    const datasets = await api<DatasetInfo[]>('GET', '/api/datasets');
    return text(JSON.stringify(datasets, null, 1));
  },
);

server.registerTool(
  'query_data',
  {
    title: 'Query NMS data',
    description:
      'Runs an aggregate query over the NMS estate and returns the rows as data for you to read — no chart. Use it to explore, check a hypothesis or find the interesting cut before charting it with show_chart.',
    inputSchema: querySchema,
    annotations: { readOnlyHint: true },
  },
  async (query) => {
    const result = await api<QueryResult>('POST', '/api/query', query);
    return text(describeResult(result));
  },
);

registerAppTool(
  server,
  'show_chart',
  {
    title: 'Show chart',
    description:
      'Renders an interactive chart of NMS data in the conversation. The user can switch chart type, click a category to drill down, and pin the chart to their live NMS dashboard. Use this whenever the user asks to see, show, compare or chart something. Look at the data with query_data first when you need it to write the insight.',
    inputSchema: chartSpecSchema,
    _meta: { ui: { resourceUri: PANEL_URI } },
    annotations: { readOnlyHint: true },
  },
  async (spec) => {
    const data = await api<ChartData>('POST', '/api/chart', spec);
    const interactive = `${API_URL}/host.html?spec=${Buffer.from(JSON.stringify(spec)).toString('base64url')}`;
    const content: ({ type: 'text'; text: string } | { type: 'image'; data: string; mimeType: string })[] = [
      { type: 'text', text: `Showing "${spec.title}".\n${describeResult(data.result)}${data.previous ? `\nPrevious period:\n${describeResult(data.previous)}` : ''}` },
    ];
    if (CHART_IMAGES) {
      try {
        content.push({ type: 'image', data: renderChartPng(data).toString('base64'), mimeType: 'image/png' });
      } catch (error) {
        console.error('Chart image failed:', error);
      }
    }
    content.push({ type: 'text', text: `Interactive version (drill down, pin to dashboard) — give the user this link: ${interactive}` });
    return {
      content,
      structuredContent: { ...data, apiUrl: API_URL, drill: await drillFor(spec.query.dataset) } as unknown as Record<string, unknown>,
    };
  },
);

// Called by the panel only — a drill-down or a type switch re-runs the chart without a model turn.
server.registerTool(
  'run_chart',
  {
    title: 'Run chart (panel)',
    description: 'Re-runs a chart spec for the Ask-the-Dashboard panel. Used by the panel for drill-downs.',
    inputSchema: chartSpecSchema,
    _meta: { ui: { visibility: ['app'] } },
    annotations: { readOnlyHint: true },
  },
  async (spec) => {
    const data = await api<ChartData>('POST', '/api/chart', spec);
    const payload = { ...data, apiUrl: API_URL, drill: await drillFor(spec.query.dataset) };
    // The JSON is in the text too: some hosts hand the panel the text but not structuredContent. The model never
    // sees this tool's result, so the size costs nothing.
    return { content: [{ type: 'text', text: JSON.stringify(payload) }], structuredContent: payload as unknown as Record<string, unknown> };
  },
);

server.registerTool(
  'pin_to_dashboard',
  {
    title: 'Pin to dashboard',
    description:
      "Adds a chart to the user's live NMS dashboard, where it appears immediately and keeps its data live. Takes the same spec as show_chart. Only pin when the user asks to.",
    inputSchema: chartSpecSchema.extend({
      cols: z.number().int().min(2).max(12).optional().describe('Width on a 12-column grid.'),
      rows: z.number().int().min(1).max(8).optional(),
    }),
  },
  async ({ cols, rows, ...spec }) => {
    const widget = await api<Widget>('POST', '/api/widgets', { spec, cols, rows });
    return text(`Pinned "${widget.title}" to the dashboard at column ${widget.x + 1}, row ${widget.y + 1} (${widget.cols}x${widget.rows}). Live at ${API_URL}/`);
  },
);

server.registerTool(
  'create_dashboard',
  {
    title: 'Create dashboard',
    description:
      'Builds a whole dashboard at once: pins several charts in order, each appearing live as it lands. Set clearFirst to start from an empty dashboard. Put KPIs first, then trends, then rankings.',
    inputSchema: z.object({
      clearFirst: z.boolean().optional(),
      widgets: z.array(chartSpecSchema.extend({ cols: z.number().int().min(2).max(12).optional(), rows: z.number().int().min(1).max(8).optional() })).min(1).max(12),
    }),
  },
  async ({ clearFirst, widgets }) => {
    if (clearFirst) await api('DELETE', '/api/widgets');
    const pinned: string[] = [];
    for (const { cols, rows, ...spec } of widgets) {
      const widget = await api<Widget>('POST', '/api/widgets', { spec, cols, rows });
      pinned.push(`- ${widget.title} (${widget.cols}x${widget.rows})`);
      await new Promise((resolve) => setTimeout(resolve, 600)); // lands one by one on screen
    }
    return text(`Built the dashboard with ${pinned.length} widgets:\n${pinned.join('\n')}\nLive at ${API_URL}/`);
  },
);

server.registerTool(
  'list_dashboard',
  {
    title: 'List dashboard widgets',
    description: "Lists the widgets on the user's live NMS dashboard, with their ids and specs.",
    inputSchema: z.object({}),
    annotations: { readOnlyHint: true },
  },
  async () => {
    const widgets = await api<Widget[]>('GET', '/api/widgets');
    if (widgets.length === 0) return text('The dashboard is empty.');
    return text(widgets.map((w) => `${w.id}: "${w.title}" (${w.spec.chartType}, ${w.cols}x${w.rows} at ${w.x},${w.y})`).join('\n'));
  },
);

server.registerTool(
  'remove_widget',
  {
    title: 'Remove dashboard widget',
    description: 'Removes one widget from the live dashboard by id (see list_dashboard), or every widget with all: true.',
    inputSchema: z.object({ id: z.string().optional(), all: z.boolean().optional() }),
    annotations: { destructiveHint: true },
  },
  async ({ id, all }) => {
    if (all) return (await api('DELETE', '/api/widgets'), text('Cleared the dashboard.'));
    if (!id) return { isError: true, content: [{ type: 'text', text: 'Pass an id, or all: true.' }] };
    const { ok } = await api<{ ok: boolean }>('DELETE', `/api/widgets/${encodeURIComponent(id)}`);
    return text(ok ? `Removed ${id}.` : `No widget ${id}.`);
  },
);

registerAppResource(server, 'Ask the Dashboard', PANEL_URI, { mimeType: RESOURCE_MIME_TYPE }, async () => {
  const html = await readFile(PANEL_FILE, 'utf8').catch(() => {
    throw new Error('The panel is not built — run `npm run build` in nms-ask-dashboard.');
  });
  return { contents: [{ uri: PANEL_URI, mimeType: RESOURCE_MIME_TYPE, text: html }] };
});

await server.connect(new StdioServerTransport());
console.error(`nms-ask-dashboard MCP server ready (API ${API_URL})`);

// ---------------------------------------------------------------------------------------------- helpers

async function api<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_URL}${path}`, {
      method,
      headers: body ? { 'content-type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new Error(`The NMS API is not running at ${API_URL} — start it with \`npm run api\` in nms-ask-dashboard.`);
  }
  const data = (await res.json()) as T & { error?: string };
  if (!res.ok) throw new Error(data.error ?? `${method} ${path} failed with ${res.status}`);
  return data;
}

let datasets: Promise<DatasetInfo[]> | undefined;

/** Which dimension a click drills into next, for the panel. Cached — the catalogue doesn't change while running. */
async function drillFor(dataset: string): Promise<Record<string, string>> {
  datasets ??= api<DatasetInfo[]>('GET', '/api/datasets').catch((error) => ((datasets = undefined), Promise.reject(error)));
  return (await datasets).find((d) => d.id === dataset)?.drill ?? {};
}

function text(value: string) {
  return { content: [{ type: 'text' as const, text: value }] };
}

/** The rows as a compact table the model can read — capped, so a 400-row trend doesn't flood the context. */
function describeResult(result: QueryResult, max = 40): string {
  const keys = result.columns.map((c) => c.key);
  const lines = [keys.join(' | '), ...result.rows.slice(0, max).map((row) => keys.map((k) => row[k] ?? '').join(' | '))];
  const more = result.rows.length > max ? `\n… ${result.rows.length - max} more rows` : '';
  const window = result.window ? ` Window ${result.window.from} to ${result.window.to}.` : '';
  return `${result.rows.length} rows.${window} Data as of ${result.anchor.slice(0, 10)}.\n${lines.join('\n')}${more}`;
}

