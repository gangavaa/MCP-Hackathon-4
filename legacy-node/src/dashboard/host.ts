import { AppBridge, PostMessageTransport } from '@modelcontextprotocol/ext-apps/app-bridge';
import type { ChartData, ChartSpec, DatasetInfo, Widget } from '../shared/types.ts';

/*
 * A local MCP Apps host for rehearsing and for working on the panel without Claude Desktop. It plays the host's
 * half of the protocol — sandboxed iframe, AppBridge, tool input and result — and answers the panel's tool calls
 * from the API directly, which is all the MCP server would do with them.
 */

const QUESTIONS: { ask: string; spec: ChartSpec }[] = [
  {
    ask: 'Which farms have the most offline devices?',
    spec: {
      title: 'Offline devices by farm', chartType: 'hbar',
      insight: 'Hoeve De Linde (NL) has 44 devices offline — almost twice the next farm.',
      query: { dataset: 'devices', metric: 'offline_count', groupBy: ['farm'], limit: 10 },
    },
  },
  {
    ask: 'Is it a firmware problem?',
    spec: {
      title: 'Offline % by firmware', chartType: 'bar',
      insight: 'Devices on 5.16.0 are offline 40% of the time; 5.14 and 5.15 sit near 4%.',
      query: { dataset: 'devices', metric: 'offline_pct', groupBy: ['firmware'] },
    },
  },
  {
    ask: 'Show estate offline % over the last 30 days',
    spec: {
      title: 'Estate offline % — last 30 days', chartType: 'area',
      insight: 'Offline doubled from ~4.7% to ~9% after 5 Oct, the day 5.16.0 rolled out.',
      query: { dataset: 'connectivity', metric: 'offline_pct', timeBucket: 'day', timeRange: { lastDays: 30 } },
    },
  },
  {
    ask: 'Gateway issues by country — this week vs last',
    spec: {
      title: 'Gateway issues by country', chartType: 'bar', compare: 'previous_period',
      insight: 'Germany raised 91 gateway issues this week against 19 the week before.',
      query: { dataset: 'issues', metric: 'issue_count', groupBy: ['country'], filters: [{ field: 'issue_type', op: 'eq', value: 'Gateway not answering' }], timeRange: { lastDays: 7 } },
    },
  },
  {
    ask: 'Open issues by severity',
    spec: {
      title: 'Open issues by severity', chartType: 'donut',
      query: { dataset: 'issues', metric: 'open_count', groupBy: ['severity'], order: 'dimension' },
    },
  },
  {
    ask: 'Critical issues this week',
    spec: {
      title: 'Critical issues — last 7 days', chartType: 'kpi', compare: 'previous_period',
      query: { dataset: 'issues', metric: 'critical_count', timeRange: { lastDays: 7 } },
    },
  },
];

const slot = document.getElementById('frame-slot')!;
const bubble = document.getElementById('bubble')!;
const log = document.getElementById('log')!;
const dark = document.getElementById('dark') as HTMLInputElement;
const questions = document.getElementById('questions')!;

const panelHtml = fetch('/panel.html').then((r) => (r.ok ? r.text() : Promise.reject(new Error('Panel not built — run npm run build.'))));
const datasets = api<DatasetInfo[]>('GET', '/api/datasets');
let bridge: AppBridge | undefined;

for (const q of QUESTIONS) {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = q.ask;
  button.addEventListener('click', () => void ask(q.ask, q.spec));
  questions.append(button);
}
dark.addEventListener('change', () => bridge?.sendHostContextChange({ theme: dark.checked ? 'dark' : 'light' }));

// ?spec=<base64url ChartSpec> — the "interactive version" link show_chart hands out — opens that chart directly.
const linked = new URLSearchParams(location.search).get('spec');
if (linked) {
  try {
    const spec = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(linked.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0)))) as ChartSpec;
    void ask(spec.title, spec);
  } catch {
    entry('error', 'The chart link is damaged — ask Claude for the chart again.');
  }
}
dark.addEventListener('change', () => (document.documentElement.dataset.theme = dark.checked ? 'dark' : 'light'));

/** One question, one tool call, one fresh panel — as the chat would render it. */
async function ask(question: string, spec: ChartSpec): Promise<void> {
  bubble.hidden = false;
  bubble.textContent = question;
  await bridge?.close().catch(() => {});

  const iframe = document.createElement('iframe');
  iframe.sandbox.add('allow-scripts');
  iframe.title = 'Ask the Dashboard panel';
  slot.replaceChildren(iframe);

  bridge = new AppBridge(null, { name: 'NMS test host', version: '0.1.0' }, { openLinks: {}, serverTools: {}, logging: {} }, {
    hostContext: { theme: dark.checked ? 'dark' : 'light', platform: 'web', displayMode: 'inline' },
  });
  bridge.oncalltool = async ({ name, arguments: args }) => callTool(name, (args ?? {}) as Record<string, unknown>);
  bridge.onmessage = async ({ content }) => (entry('message', content.map((c) => ('text' in c ? c.text : '')).join(' ')), {});
  bridge.onupdatemodelcontext = async ({ content }) => (entry('context', content?.map((c) => ('text' in c ? c.text : '')).join(' ') ?? ''), {});
  bridge.onopenlink = async ({ url }) => (window.open(url, 'nms-dashboard'), {});
  bridge.onsizechange = ({ height }) => {
    if (height) iframe.style.height = `${height}px`;
  };
  const current = bridge;
  bridge.oninitialized = async () => {
    await current.sendToolInput({ arguments: spec as unknown as Record<string, unknown> });
    const result = await showChart(spec);
    // ?agent=1 plays a host that drops structuredContent (as Claude Desktop's agent mode does), to test the fallback.
    await current.sendToolResult(new URLSearchParams(location.search).has('agent') ? { content: result.content } : result);
  };

  await bridge.connect(new PostMessageTransport(iframe.contentWindow!, iframe.contentWindow!));
  iframe.srcdoc = await panelHtml;
}

async function showChart(spec: ChartSpec) {
  const data = await api<ChartData>('POST', '/api/chart', spec);
  const drill = (await datasets).find((d) => d.id === spec.query.dataset)?.drill ?? {};
  return { content: [{ type: 'text' as const, text: `Showing "${spec.title}"` }], structuredContent: { ...data, apiUrl: location.origin, drill } };
}

async function callTool(name: string, args: Record<string, unknown>) {
  entry('tool', `${name}(${(args as { title?: string }).title ?? ''})`);
  if (name === 'run_chart') {
    const data = await api<ChartData>('POST', '/api/chart', args);
    const drill = (await datasets).find((d) => d.id === (args as unknown as ChartSpec).query.dataset)?.drill ?? {};
    const payload = { ...data, apiUrl: location.origin, drill };
    const agent = new URLSearchParams(location.search).has('agent');
    return agent
      ? { content: [{ type: 'text' as const, text: JSON.stringify(payload) }] }
      : { content: [{ type: 'text' as const, text: 'ok' }], structuredContent: payload as unknown as Record<string, unknown> };
  }
  if (name === 'pin_to_dashboard') {
    const { cols, rows, ...spec } = args;
    const widget = await api<Widget>('POST', '/api/widgets', { spec, cols, rows });
    return { content: [{ type: 'text' as const, text: `Pinned "${widget.title}"` }] };
  }
  return { isError: true, content: [{ type: 'text' as const, text: `The test host does not implement ${name}.` }] };
}

function entry(kind: string, text: string): void {
  const li = document.createElement('li');
  li.innerHTML = `<span class="kind ${kind}"></span>`;
  li.querySelector('.kind')!.textContent = kind;
  li.append(` ${text}`);
  log.prepend(li);
}

async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, { method, headers: body ? { 'content-type': 'application/json' } : undefined, body: body ? JSON.stringify(body) : undefined });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error ?? res.statusText);
  return data as T;
}
