import { render, type Rendered, type Theme } from '../shared/render.ts';
import type { ChartData, DashboardEvent, Widget } from '../shared/types.ts';

/*
 * The live dashboard — the stand-in for nms-core-ui's. Widgets hold a query, not data: each one runs its spec
 * against the API when it lands and every minute after, and new ones arrive over /api/events the moment they
 * are pinned from the chat.
 */

const REFRESH_MS = 60_000;

const grid = document.getElementById('grid')!;
const empty = document.getElementById('empty')!;
const count = document.getElementById('count')!;
const live = document.getElementById('live')!;
const themeButton = document.getElementById('theme')!;

const cards = new Map<string, { el: HTMLElement; widget: Widget; chart?: Rendered }>();
let theme: Theme = localStorage.getItem('nms-theme') === 'dark' ? 'dark' : 'light';
applyTheme();

themeButton.addEventListener('click', () => {
  theme = theme === 'dark' ? 'light' : 'dark';
  localStorage.setItem('nms-theme', theme);
  applyTheme();
  for (const card of cards.values()) void draw(card.widget);
});

const widgets = await api<Widget[]>('GET', '/api/widgets');
for (const widget of widgets) addCard(widget, false);
updateChrome();
connect();
setInterval(() => cards.forEach(({ widget }) => void draw(widget)), REFRESH_MS);

// ---------------------------------------------------------------------------------------------- cards

function addCard(widget: Widget, landed: boolean): void {
  if (cards.has(widget.id)) return;
  const el = document.createElement('article');
  el.className = `card${landed ? ' landed' : ''}`;
  el.style.gridColumn = `${widget.x + 1} / span ${widget.cols}`;
  el.style.gridRow = `${widget.y + 1} / span ${widget.rows}`;
  el.innerHTML = `
    <header class="card-head">
      <h3></h3>
      ${widget.source === 'ai' ? '<span class="ai-badge" title="Created from a question to Claude">✦ AI</span>' : ''}
      <button class="remove" type="button" aria-label="Remove widget">×</button>
    </header>
    <p class="card-insight" hidden></p>
    <div class="card-body"></div>`;
  el.querySelector('h3')!.textContent = widget.title;
  const insight = el.querySelector<HTMLElement>('.card-insight')!;
  insight.textContent = widget.spec.insight ?? '';
  insight.hidden = !widget.spec.insight;
  el.querySelector('.remove')!.addEventListener('click', () => void api('DELETE', `/api/widgets/${widget.id}`));

  grid.append(el);
  cards.set(widget.id, { el, widget });
  void draw(widget);
  if (landed) {
    el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    setTimeout(() => el.classList.remove('landed'), 2600);
  }
}

async function draw(widget: Widget): Promise<void> {
  const card = cards.get(widget.id);
  if (!card) return;
  const body = card.el.querySelector<HTMLElement>('.card-body')!;
  try {
    const data = await api<ChartData>('POST', '/api/chart', widget.spec);
    card.chart?.dispose();
    card.chart = data.result.rows.length
      ? render(body, data, widget.spec.chartType, { theme, compact: widget.cols <= 4 })
      : (body.replaceChildren(Object.assign(document.createElement('p'), { className: 'empty', textContent: 'No data' })), undefined);
  } catch (error) {
    body.replaceChildren(Object.assign(document.createElement('p'), { className: 'empty', textContent: `Couldn't load: ${(error as Error).message}` }));
  }
}

function removeCard(id: string): void {
  const card = cards.get(id);
  if (!card) return;
  card.chart?.dispose();
  card.el.remove();
  cards.delete(id);
}

function updateChrome(): void {
  empty.hidden = cards.size > 0;
  count.textContent = cards.size ? `${cards.size} widget${cards.size === 1 ? '' : 's'}` : '';
}

// ---------------------------------------------------------------------------------------------- live

function connect(): void {
  const events = new EventSource('/api/events');
  events.onopen = () => setLive(true);
  events.onerror = () => setLive(false); // EventSource reconnects by itself
  events.onmessage = (message) => {
    const event = JSON.parse(message.data) as DashboardEvent;
    if (event.type === 'widget-added') addCard(event.widget, true);
    else if (event.type === 'widget-removed') removeCard(event.id);
    else if (event.type === 'cleared') [...cards.keys()].forEach(removeCard);
    updateChrome();
  };
}

function setLive(on: boolean): void {
  live.textContent = on ? 'Live' : 'Reconnecting…';
  live.classList.toggle('on', on);
}

function applyTheme(): void {
  document.documentElement.dataset.theme = theme;
}

async function api<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body ? { 'content-type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error ?? res.statusText);
  return data as T;
}
