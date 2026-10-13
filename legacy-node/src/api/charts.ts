import type { Db } from './db.ts';
import { runQuery } from './datasets.ts';
import type { ChartData, ChartSpec, ChartType, DashboardEvent, Widget } from '../shared/types.ts';

export async function getAnchor(db: Db): Promise<Date> {
  const [row] = await db.query<{ anchor: Date | string }>('SELECT anchor FROM meta LIMIT 1');
  if (!row) throw new Error('The database is not seeded — run `npm run db:reset`.');
  return new Date(row.anchor);
}

/** The data behind a chart: its query, and the same query one window earlier when it compares. */
export async function runChart(db: Db, spec: ChartSpec, anchor: Date): Promise<ChartData> {
  const result = await runQuery(db, spec.query, anchor);
  if (spec.compare !== 'previous_period' || !spec.query.timeRange) return { spec, result };
  const { lastDays, offsetDays = 0 } = spec.query.timeRange;
  const previous = await runQuery(db, { ...spec.query, timeRange: { lastDays, offsetDays: offsetDays + lastDays } }, anchor);
  return { spec, result, previous };
}

// ---------------------------------------------------------------------------------------------- widgets

const GRID_COLUMNS = 12;

const DEFAULT_SIZE: Record<ChartType, { cols: number; rows: number }> = {
  kpi: { cols: 3, rows: 2 },
  pie: { cols: 4, rows: 3 },
  donut: { cols: 4, rows: 3 },
  bar: { cols: 6, rows: 3 },
  hbar: { cols: 6, rows: 4 },
  line: { cols: 6, rows: 3 },
  area: { cols: 6, rows: 3 },
  table: { cols: 6, rows: 4 },
};

interface WidgetRow {
  id: string; title: string; spec: ChartSpec | string; x: number; y: number; cols: number; rows: number;
  source: 'ai' | 'user'; created_at: Date | string;
}

const toWidget = (r: WidgetRow): Widget => ({
  id: r.id,
  title: r.title,
  spec: typeof r.spec === 'string' ? JSON.parse(r.spec) : r.spec,
  x: r.x, y: r.y, cols: r.cols, rows: r.rows,
  source: r.source,
  createdAt: new Date(r.created_at).toISOString(),
});

export async function listWidgets(db: Db): Promise<Widget[]> {
  const rows = await db.query<WidgetRow>('SELECT * FROM dashboard_widgets ORDER BY y, x');
  return rows.map(toWidget);
}

/** First free slot, scanning top-to-bottom then left-to-right — what a person dropping a card would pick. */
export function place(existing: Pick<Widget, 'x' | 'y' | 'cols' | 'rows'>[], cols: number, rows: number): { x: number; y: number } {
  const taken = (x: number, y: number) =>
    existing.some((w) => x < w.x + w.cols && x + cols > w.x && y < w.y + w.rows && y + rows > w.y);
  for (let y = 0; ; y++) {
    for (let x = 0; x + cols <= GRID_COLUMNS; x++) {
      if (!taken(x, y)) return { x, y };
    }
  }
}

export async function addWidget(db: Db, spec: ChartSpec, size?: { cols?: number; rows?: number }, source: 'ai' | 'user' = 'ai'): Promise<Widget> {
  const defaults = DEFAULT_SIZE[spec.chartType] ?? DEFAULT_SIZE.bar;
  const cols = Math.min(GRID_COLUMNS, Math.max(2, size?.cols ?? defaults.cols));
  const rows = Math.min(8, Math.max(1, size?.rows ?? defaults.rows));
  const { x, y } = place(await listWidgets(db), cols, rows);
  const id = `w-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
  const [row] = await db.query<WidgetRow>(
    'INSERT INTO dashboard_widgets (id, title, spec, x, y, cols, rows, source) VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING *',
    [id, spec.title, JSON.stringify(spec), x, y, cols, rows, source],
  );
  const widget = toWidget(row);
  broadcast({ type: 'widget-added', widget });
  return widget;
}

export async function removeWidget(db: Db, id: string): Promise<boolean> {
  const rows = await db.query('DELETE FROM dashboard_widgets WHERE id = $1 RETURNING id', [id]);
  if (rows.length) broadcast({ type: 'widget-removed', id });
  return rows.length > 0;
}

export async function clearWidgets(db: Db): Promise<void> {
  await db.query('DELETE FROM dashboard_widgets');
  broadcast({ type: 'cleared' });
}

// ---------------------------------------------------------------------------------------------- live events

type Listener = (event: DashboardEvent) => void;
const listeners = new Set<Listener>();

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function broadcast(event: DashboardEvent): void {
  for (const listener of listeners) listener(event);
}
