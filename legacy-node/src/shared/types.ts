// The contracts every part shares: the API answers them, the MCP server forwards them, the panel and the
// dashboard render them. Change one and all four move together.

export type FilterOp = 'eq' | 'neq' | 'in' | 'contains' | 'gte' | 'lte';

export interface QueryFilter {
  field: string;
  op: FilterOp;
  value: string | number | (string | number)[];
}

export interface TimeRange {
  /** How many days the window spans, ending at the data's anchor ("now"). */
  lastDays: number;
  /** Shift the window back this many days — 7 with lastDays 7 is "the week before last week". */
  offsetDays?: number;
}

export interface QueryRequest {
  dataset: string;
  metric: string;
  /** At most two dimensions: the category axis, then (optionally) the series. */
  groupBy?: string[];
  filters?: QueryFilter[];
  timeRange?: TimeRange;
  /** Adds a leading `period` dimension, bucketing the dataset's time column. */
  timeBucket?: 'day' | 'week';
  order?: 'metric_desc' | 'metric_asc' | 'dimension';
  limit?: number;
}

export interface QueryColumn {
  key: string;
  label: string;
  kind: 'dimension' | 'metric';
}

export type Row = Record<string, string | number | null>;

export interface QueryResult {
  columns: QueryColumn[];
  rows: Row[];
  /** The SQL that answered it, parameters inlined for reading only — shown behind "Show SQL". */
  sql: string;
  anchor: string;
  window?: { from: string; to: string };
}

export type ChartType = 'bar' | 'hbar' | 'line' | 'area' | 'pie' | 'donut' | 'table' | 'kpi';

export interface ChartSpec {
  title: string;
  chartType: ChartType;
  query: QueryRequest;
  /** Runs the same query over the window just before this one, as a second series. Needs a timeRange. */
  compare?: 'previous_period';
  /** One sentence of what the chart shows, written by the model. Rendered under the title. */
  insight?: string;
}

export interface ChartData {
  spec: ChartSpec;
  result: QueryResult;
  previous?: QueryResult;
}

export interface DatasetField {
  key: string;
  label: string;
  description?: string;
  /** For a dimension: the values it holds, when there are few enough to list. */
  values?: string[];
}

export interface DatasetInfo {
  id: string;
  label: string;
  description: string;
  timeColumn?: string;
  dimensions: DatasetField[];
  metrics: DatasetField[];
  /** Which dimension a click on a category drills into next, per dimension. */
  drill: Record<string, string>;
}

export interface Widget {
  id: string;
  title: string;
  spec: ChartSpec;
  x: number;
  y: number;
  cols: number;
  rows: number;
  source: 'ai' | 'user';
  createdAt: string;
}

export type DashboardEvent =
  | { type: 'widget-added'; widget: Widget }
  | { type: 'widget-removed'; id: string }
  | { type: 'cleared' };
