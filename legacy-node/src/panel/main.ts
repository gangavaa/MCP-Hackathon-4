import { App, applyDocumentTheme } from '@modelcontextprotocol/ext-apps';
import { formatNumber, humanize, render, toModel, type Rendered, type Theme } from '../shared/render.ts';
import type { ChartData, ChartSpec, ChartType } from '../shared/types.ts';

/*
 * The MCP App: the panel `show_chart` renders inside the conversation. The person and the model drive the same
 * view — the model through tool calls, the person through these buttons — and every change the person makes is
 * told back to the model with updateModelContext, so "why are these offline?" knows what "these" are.
 */

interface PanelPayload extends ChartData {
  apiUrl?: string;
  drill?: Record<string, string>;
}

interface Level {
  data: ChartData;
  /** The category clicked to get here, for the breadcrumb. */
  via?: string;
}

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const ui = {
  title: $('title'), insight: $('insight'), crumbs: $('crumbs'), types: $('types'), hint: $('hint'),
  chart: $('chart'), sql: $('sql'), pin: $<HTMLButtonElement>('pin'), explain: $<HTMLButtonElement>('explain'),
  open: $<HTMLButtonElement>('open'), toast: $('toast'),
};

const state = {
  stack: [] as Level[],
  type: 'bar' as ChartType,
  theme: 'light' as Theme,
  apiUrl: 'http://localhost:4300',
  drill: {} as Record<string, string>,
  busy: false,
};
let rendered: Rendered | undefined;

const app = new App({ name: 'NMS Ask the Dashboard', version: '0.1.0' });

/*
 * Some hosts — Claude Desktop's agent mode among them — deliver the tool input but drop structuredContent from
 * the result. So the panel keeps the spec it was asked for, and when no chart data arrives with the result (or
 * no result arrives at all) it fetches the chart itself through run_chart.
 */
let requested: ChartSpec | undefined;
let fallback: ReturnType<typeof setTimeout> | undefined;

app.ontoolinput = ({ arguments: args }) => {
  requested = args as unknown as ChartSpec;
  if (requested?.title) status(requested.title, 'Loading chart…');
  clearTimeout(fallback);
  fallback = setTimeout(() => void loadRequested(), 4000);
};

app.ontoolresult = (result) => {
  clearTimeout(fallback);
  const payload = payloadOf(result);
  if (payload) show(payload);
  else if (result.isError) status(requested?.title ?? 'Chart', textOf(result));
  else void loadRequested();
};

function show(payload: PanelPayload): void {
  state.apiUrl = payload.apiUrl ?? state.apiUrl;
  state.drill = payload.drill ?? state.drill;
  state.stack = [{ data: payload }];
  state.type = payload.spec.chartType;
  draw();
}

async function loadRequested(): Promise<void> {
  clearTimeout(fallback);
  if (state.stack.length) return;
  if (!requested) return status('No chart', 'The host sent no chart request to this panel.');
  try {
    const result = await app.callServerTool({ name: 'run_chart', arguments: requested as unknown as Record<string, unknown> });
    if (result.isError) throw new Error(textOf(result));
    const payload = payloadOf(result);
    if (!payload) throw new Error('run_chart returned no chart data.');
    show(payload);
  } catch (error) {
    status(requested.title, `Couldn't load the chart: ${error instanceof Error ? error.message : String(error)}`);
  }
}

/** The chart data from structuredContent, or — when a host dropped it — from the JSON run_chart also sends as text. */
function payloadOf(result: { structuredContent?: unknown; content?: { type: string; text?: string }[] }): PanelPayload | undefined {
  const isPayload = (v: unknown): v is PanelPayload => !!v && typeof v === 'object' && 'spec' in v && 'result' in v;
  if (isPayload(result.structuredContent)) return result.structuredContent;
  for (const block of result.content ?? []) {
    if (block.type !== 'text' || !block.text?.startsWith('{')) continue;
    try {
      const parsed: unknown = JSON.parse(block.text);
      if (isPayload(parsed)) return parsed;
    } catch {
      // not JSON — a summary for the model
    }
  }
  return undefined;
}

function status(title: string, message: string): void {
  ui.title.textContent = title;
  ui.chart.replaceChildren(Object.assign(document.createElement('p'), { className: 'empty', textContent: message }));
}

app.onhostcontextchanged = (context) => {
  if (context.theme) setTheme(context.theme);
};

// ---------------------------------------------------------------------------------------------- drawing

const current = () => state.stack[state.stack.length - 1];

function draw(): void {
  const level = current();
  if (!level) return;
  const { spec, result } = level.data;
  const model = toModel(level.data);

  ui.title.textContent = spec.title;
  ui.insight.textContent = state.stack.length === 1 ? spec.insight ?? '' : '';
  ui.insight.hidden = !ui.insight.textContent;
  ui.sql.textContent = result.sql;

  drawCrumbs();
  drawTypes(model.categoryKey == null ? ['kpi', 'table'] : model.isTime ? ['line', 'area', 'bar', 'table'] : ['bar', 'hbar', 'pie', 'donut', 'table']);

  const drillTo = model.categoryKey ? state.drill[model.categoryKey] : undefined;
  ui.hint.textContent = drillTo && !model.isTime && state.type !== 'table' ? `Click a ${model.categoryLabel.toLowerCase()} to see it by ${drillTo.replaceAll('_', ' ')}` : '';

  rendered?.dispose();
  if (result.rows.length === 0) {
    ui.chart.replaceChildren(Object.assign(document.createElement('p'), { className: 'empty', textContent: 'No data matches this question.' }));
    rendered = undefined;
  } else {
    rendered = render(ui.chart, level.data, state.type, { theme: state.theme, onSelect: drillTo ? (raw) => drillInto(raw, drillTo) : undefined });
  }
  void tellModel();
}

function drawCrumbs(): void {
  ui.crumbs.hidden = state.stack.length < 2;
  ui.crumbs.replaceChildren();
  state.stack.forEach((level, i) => {
    const last = i === state.stack.length - 1;
    const crumb = document.createElement(last ? 'span' : 'button');
    crumb.className = 'crumb';
    crumb.textContent = i === 0 ? 'All' : humanize(level.via);
    if (!last) crumb.addEventListener('click', () => ((state.stack = state.stack.slice(0, i + 1)), (state.type = current().data.spec.chartType), draw()));
    ui.crumbs.append(crumb);
    if (!last) ui.crumbs.append(Object.assign(document.createElement('span'), { className: 'sep', textContent: '›' }));
  });
}

const TYPE_LABELS: Record<ChartType, string> = { bar: 'Bar', hbar: 'Ranking', line: 'Line', area: 'Area', pie: 'Pie', donut: 'Donut', table: 'Table', kpi: 'KPI' };

function drawTypes(types: ChartType[]): void {
  if (!types.includes(state.type)) state.type = types[0];
  ui.types.replaceChildren(
    ...types.map((type) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = TYPE_LABELS[type];
      button.setAttribute('aria-pressed', String(type === state.type));
      button.addEventListener('click', () => ((state.type = type), draw()));
      return button;
    }),
  );
}

function setTheme(theme: Theme): void {
  state.theme = theme;
  applyDocumentTheme(theme);
  if (current()) draw();
}

// ---------------------------------------------------------------------------------------------- actions

async function drillInto(raw: string, dimension: string): Promise<void> {
  if (state.busy) return;
  const { spec } = current().data;
  const model = toModel(current().data);
  const next: ChartSpec = {
    title: `${spec.title.replace(/ — .*$/, '')} — ${humanize(raw)}`,
    chartType: dimension === 'farm' ? 'hbar' : state.type === 'kpi' ? 'bar' : state.type,
    compare: spec.compare,
    query: {
      ...spec.query,
      groupBy: [dimension],
      filters: [...(spec.query.filters ?? []), { field: model.categoryKey!, op: 'eq', value: raw }],
    },
  };
  await busy(async () => {
    const result = await app.callServerTool({ name: 'run_chart', arguments: next as unknown as Record<string, unknown> });
    if (result.isError) throw new Error(textOf(result));
    const payload = payloadOf(result);
    if (!payload) throw new Error('run_chart returned no chart data.');
    state.stack.push({ data: payload, via: raw });
    state.type = next.chartType;
    draw();
  });
}

ui.pin.addEventListener('click', () =>
  busy(async () => {
    const spec: ChartSpec = { ...current().data.spec, chartType: state.type, insight: state.stack.length === 1 ? current().data.spec.insight : undefined };
    const result = await app.callServerTool({ name: 'pin_to_dashboard', arguments: spec as unknown as Record<string, unknown> });
    if (result.isError) throw new Error(textOf(result));
    toast(`Pinned “${spec.title}” to the dashboard`);
  }),
);

ui.explain.addEventListener('click', () =>
  busy(async () => {
    const { spec } = current().data;
    await app.sendMessage({
      role: 'user',
      content: [{ type: 'text', text: `Explain the "${spec.title}" chart I'm looking at${pathText()}: what stands out, what is the likely cause, and what should I check next?` }],
    });
  }),
);

ui.open.addEventListener('click', () => void app.openLink({ url: state.apiUrl }));

async function busy(work: () => Promise<void>): Promise<void> {
  state.busy = true;
  document.body.classList.add('busy');
  try {
    await work();
  } catch (error) {
    toast(error instanceof Error ? error.message : String(error), true);
  } finally {
    state.busy = false;
    document.body.classList.remove('busy');
  }
}

let toastTimer: ReturnType<typeof setTimeout> | undefined;
function toast(message: string, error = false): void {
  ui.toast.textContent = message;
  ui.toast.classList.toggle('error', error);
  ui.toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (ui.toast.hidden = true), 3500);
}

// ---------------------------------------------------------------------------------------------- model context

const pathText = () => (state.stack.length > 1 ? ` (drilled into ${state.stack.slice(1).map((l) => humanize(l.via)).join(' › ')})` : '');

/** Keeps the model's picture of the panel current: what is on screen, how it is drawn, the top of the data. */
async function tellModel(): Promise<void> {
  const { spec, result } = current().data;
  const metric = result.columns.find((c) => c.kind === 'metric')!;
  const dims = result.columns.filter((c) => c.kind === 'dimension');
  const top = result.rows.slice(0, 10).map((row) => `${dims.map((d) => humanize(row[d.key])).join(' / ') || 'total'}: ${formatNumber(row[metric.key])}`);
  try {
    await app.updateModelContext({
      content: [{ type: 'text', text: `The user is viewing "${spec.title}"${pathText()} as a ${TYPE_LABELS[state.type].toLowerCase()} chart. ${metric.label} — ${top.join('; ')}.` }],
      structuredContent: { spec: { ...spec, chartType: state.type }, drillPath: state.stack.slice(1).map((l) => l.via) },
    });
  } catch {
    // a host without model-context support still renders the panel
  }
}

function textOf(result: { content?: { type: string; text?: string }[] }): string {
  return result.content?.find((c) => c.type === 'text')?.text ?? 'The tool failed.';
}

// ---------------------------------------------------------------------------------------------- start

// Last, so every handler above is defined before the host can call one.
await app.connect();
const theme = app.getHostContext()?.theme;
if (theme) setTheme(theme);
