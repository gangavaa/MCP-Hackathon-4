import type { Db } from './db.ts';
import type { DatasetInfo, QueryColumn, QueryRequest, QueryResult, Row } from '../shared/types.ts';

/*
 * The semantic layer. The model never writes SQL: it names a dataset, a metric, up to two dimensions and some
 * filters, and this file turns that into one parameterised statement. Every identifier in the SQL comes from the
 * catalogue below; every value the caller sends travels as a parameter. A field the catalogue does not hold is a
 * QueryError whose message lists the ones it does, which is what lets the model correct itself.
 */

export class QueryError extends Error {}

interface Field {
  label: string;
  sql: string;
  description?: string;
  /** Sort key when ordering by this dimension — severity reads CRITICAL→LOW, not alphabetically. */
  orderSql?: string;
  /** Don't list its values in the catalogue (too many to be useful). */
  noValues?: boolean;
}

interface Dataset {
  label: string;
  description: string;
  from: string;
  timeColumn?: { sql: string; label: string };
  dimensions: Record<string, Field>;
  metrics: Record<string, Field>;
  /** Numeric fields that can be filtered on (gte/lte) but not grouped by. */
  numericFilters?: Record<string, Field>;
  drill: Record<string, string>;
}

const SEVERITY_ORDER = (column: string) =>
  `CASE ${column} WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 4 ELSE 5 END`;

/* The dimensions every dataset reaches through its farm. */
const FARM_DIMENSIONS: Record<string, Field> = {
  tenant: { label: 'Tenant', sql: 't.name' },
  environment: { label: 'Environment', sql: 'f.environment' },
  specie: { label: 'Species', sql: 'f.specie' },
  country: { label: 'Country', sql: 'f.country' },
  town: { label: 'Town', sql: 'f.address', noValues: true },
  farm: { label: 'Farm', sql: 'f.name', noValues: true },
};

const DATASETS: Record<string, Dataset> = {
  farms: {
    label: 'Farms',
    description: 'Every farm in the estate, one row each: where it is, its tenant, its current connectivity.',
    from: 'farms f JOIN tenants t ON t.id = f.tenant_id',
    timeColumn: { sql: 'f.registered_at', label: 'registration date' },
    dimensions: {
      ...FARM_DIMENSIONS,
      connectivity_status: { label: 'Connectivity', sql: 'f.connectivity_status' },
      version: { label: 'Farm server version', sql: 'f.version' },
      protocol: { label: 'Protocol', sql: 'f.protocol' },
    },
    metrics: {
      farm_count: { label: 'Farms', sql: 'count(*)::int' },
      animals: { label: 'Animals', sql: 'sum(f.animals_on_farm)::int' },
      avg_animals: { label: 'Avg animals per farm', sql: 'round(avg(f.animals_on_farm))::int' },
    },
    numericFilters: { animals_on_farm: { label: 'Animals on farm', sql: 'f.animals_on_farm' } },
    drill: { tenant: 'country', environment: 'tenant', specie: 'country', country: 'farm', connectivity_status: 'farm', version: 'farm', protocol: 'farm' },
  },

  devices: {
    label: 'Devices',
    description: 'Every device (collars, ear tags, gateways, sensors) with its CURRENT status, firmware and battery. Status is a snapshot of now, not a history — use `connectivity` for trends over time.',
    from: 'devices d JOIN farms f ON f.id = d.farm_id JOIN tenants t ON t.id = f.tenant_id',
    timeColumn: { sql: 'd.last_seen_at', label: 'last seen' },
    dimensions: {
      ...FARM_DIMENSIONS,
      device_type: { label: 'Device type', sql: 'd.device_type' },
      hardware: { label: 'Hardware', sql: 'd.hardware_type' },
      firmware: { label: 'Firmware', sql: 'd.firmware_version' },
      status: { label: 'Status', sql: 'd.status' },
    },
    metrics: {
      device_count: { label: 'Devices', sql: 'count(*)::int' },
      offline_count: { label: 'Offline devices', sql: "(count(*) FILTER (WHERE d.status = 'OFFLINE'))::int" },
      offline_pct: { label: 'Offline %', sql: "round(100.0 * count(*) FILTER (WHERE d.status = 'OFFLINE') / nullif(count(*), 0), 1)::float8" },
      low_battery_count: { label: 'Low-battery devices', sql: "(count(*) FILTER (WHERE d.status = 'LOW_BATTERY'))::int" },
      avg_battery: { label: 'Avg battery %', sql: 'round(avg(d.battery_pct))::int' },
    },
    numericFilters: { battery_pct: { label: 'Battery %', sql: 'd.battery_pct' } },
    drill: { tenant: 'country', environment: 'tenant', specie: 'country', country: 'farm', town: 'farm', farm: 'firmware', firmware: 'farm', device_type: 'firmware', hardware: 'farm', status: 'farm' },
  },

  issues: {
    label: 'Issues',
    description: 'Alerts raised on devices over the last 60 days: severity, type, the farm and device, when it was raised and resolved.',
    from: 'issues i JOIN alert_definitions a ON a.id = i.definition_id JOIN farms f ON f.id = i.farm_id JOIN tenants t ON t.id = i.tenant_id LEFT JOIN devices d ON d.id = i.resource_id',
    timeColumn: { sql: 'i.created_at', label: 'raised at' },
    dimensions: {
      ...FARM_DIMENSIONS,
      severity: { label: 'Severity', sql: 'i.severity', orderSql: SEVERITY_ORDER('i.severity') },
      issue_type: { label: 'Issue type', sql: 'a.description' },
      category: { label: 'Category', sql: 'a.category' },
      alert_state: { label: 'State', sql: 'i.alert_state', description: 'OPEN or CLOSED' },
      device_type: { label: 'Device type', sql: 'd.device_type' },
      firmware: { label: 'Device firmware', sql: 'd.firmware_version' },
    },
    metrics: {
      issue_count: { label: 'Issues', sql: 'count(*)::int' },
      open_count: { label: 'Open issues', sql: '(count(*) FILTER (WHERE i.resolved_at IS NULL))::int' },
      critical_count: { label: 'Critical issues', sql: "(count(*) FILTER (WHERE i.severity = 'CRITICAL'))::int" },
      avg_resolution_hours: { label: 'Avg hours to resolve', sql: 'round((avg(extract(epoch FROM (i.resolved_at - i.created_at))) / 3600)::numeric, 1)::float8' },
    },
    drill: { tenant: 'country', environment: 'tenant', specie: 'country', country: 'farm', town: 'farm', farm: 'issue_type', issue_type: 'farm', severity: 'issue_type', category: 'issue_type', alert_state: 'issue_type', device_type: 'issue_type', firmware: 'issue_type' },
  },

  connectivity: {
    label: 'Connectivity history',
    description: 'One snapshot per farm per day for 60 days: how many of its devices were online and offline. Use with timeBucket for trends.',
    from: 'connectivity_snapshots c JOIN farms f ON f.id = c.farm_id JOIN tenants t ON t.id = f.tenant_id',
    timeColumn: { sql: 'c.day', label: 'day' },
    dimensions: { ...FARM_DIMENSIONS },
    metrics: {
      online_pct: { label: 'Online %', sql: 'round(100.0 * sum(c.online) / nullif(sum(c.total), 0), 1)::float8' },
      offline_pct: { label: 'Offline %', sql: 'round(100.0 * sum(c.offline) / nullif(sum(c.total), 0), 1)::float8' },
      offline_devices: { label: 'Offline devices', sql: 'sum(c.offline)::int', description: 'Summed over the days in the group — bucket by day to read it as "offline that day".' },
      avg_offline_devices: { label: 'Avg offline devices per farm-day', sql: 'round(avg(c.offline), 1)::float8' },
    },
    drill: { tenant: 'country', environment: 'tenant', specie: 'country', country: 'farm', town: 'farm' },
  },
};

// ---------------------------------------------------------------------------------------------- catalogue

let catalogue: DatasetInfo[] | undefined;

/** The datasets as the model and the panel see them, with the values of every short-listed dimension. */
export async function describeDatasets(db: Db): Promise<DatasetInfo[]> {
  if (catalogue) return catalogue;
  const out: DatasetInfo[] = [];
  for (const [id, ds] of Object.entries(DATASETS)) {
    const dimensions = [];
    for (const [key, field] of Object.entries(ds.dimensions)) {
      let values: string[] | undefined;
      if (!field.noValues) {
        const rows = await db.query<{ v: string }>(`SELECT DISTINCT ${field.sql} AS v FROM ${ds.from} WHERE ${field.sql} IS NOT NULL ORDER BY 1 LIMIT 31`);
        if (rows.length <= 30) values = rows.map((r) => r.v);
      }
      dimensions.push({ key, label: field.label, description: field.description, values });
    }
    out.push({
      id,
      label: ds.label,
      description: ds.description,
      timeColumn: ds.timeColumn?.label,
      dimensions,
      metrics: Object.entries(ds.metrics).map(([key, f]) => ({ key, label: f.label, description: f.description })),
      drill: ds.drill,
    });
  }
  return (catalogue = out);
}

// ---------------------------------------------------------------------------------------------- building

export interface BuiltQuery {
  sql: string;
  params: unknown[];
  columns: QueryColumn[];
  window?: { from: string; to: string };
}

const DAY_MS = 86_400_000;

export function buildQuery(req: QueryRequest, anchor: Date): BuiltQuery {
  const ds = DATASETS[req.dataset];
  if (!ds) throw new QueryError(`Unknown dataset '${req.dataset}'. Available: ${Object.keys(DATASETS).join(', ')}.`);

  const metric = ds.metrics[req.metric];
  if (!metric) throw new QueryError(`Unknown metric '${req.metric}' for ${req.dataset}. Available: ${Object.keys(ds.metrics).join(', ')}.`);

  const groupBy = req.groupBy ?? [];
  if (groupBy.length > 2) throw new QueryError('groupBy takes at most two dimensions (category, then series).');
  const dims = groupBy.map((key) => {
    const field = ds.dimensions[key];
    if (!field) throw new QueryError(`Unknown dimension '${key}' for ${req.dataset}. Available: ${Object.keys(ds.dimensions).join(', ')}.`);
    return { key, field };
  });

  const params: unknown[] = [];
  const param = (value: unknown) => {
    params.push(value);
    return `$${params.length}`;
  };

  const select: string[] = [];
  const columns: QueryColumn[] = [];
  if (req.timeBucket) {
    if (!ds.timeColumn) throw new QueryError(`${req.dataset} has no time column to bucket.`);
    const unit = req.timeBucket === 'week' ? 'week' : 'day';
    select.push(`to_char(date_trunc('${unit}', ${ds.timeColumn.sql}), 'YYYY-MM-DD') AS "period"`);
    columns.push({ key: 'period', label: unit === 'week' ? 'Week' : 'Day', kind: 'dimension' });
  }
  for (const { key, field } of dims) {
    select.push(`${field.sql} AS "${key}"`);
    columns.push({ key, label: field.label, kind: 'dimension' });
  }
  select.push(`${metric.sql} AS "${req.metric}"`);
  columns.push({ key: req.metric, label: metric.label, kind: 'metric' });

  const where: string[] = [];
  for (const filter of req.filters ?? []) {
    const field = ds.dimensions[filter.field] ?? ds.numericFilters?.[filter.field];
    if (!field) {
      const all = [...Object.keys(ds.dimensions), ...Object.keys(ds.numericFilters ?? {})];
      throw new QueryError(`Unknown filter field '${filter.field}' for ${req.dataset}. Available: ${all.join(', ')}.`);
    }
    const values = Array.isArray(filter.value) ? filter.value : [filter.value];
    switch (filter.op) {
      case 'eq': where.push(`${field.sql} = ${param(values[0])}`); break;
      case 'neq': where.push(`${field.sql} <> ${param(values[0])}`); break;
      case 'in':
        if (values.length === 0) throw new QueryError(`Filter '${filter.field}' with op 'in' needs at least one value.`);
        where.push(`${field.sql} IN (${values.map(param).join(', ')})`);
        break;
      case 'contains': where.push(`${field.sql}::text ILIKE ${param(`%${values[0]}%`)}`); break;
      case 'gte': where.push(`${field.sql} >= ${param(values[0])}`); break;
      case 'lte': where.push(`${field.sql} <= ${param(values[0])}`); break;
      default: throw new QueryError(`Unknown filter op '${(filter as { op: string }).op}'. Use eq, neq, in, contains, gte or lte.`);
    }
  }

  let window: BuiltQuery['window'];
  if (req.timeRange) {
    if (!ds.timeColumn) throw new QueryError(`${req.dataset} has no time column for a timeRange.`);
    const { lastDays, offsetDays = 0 } = req.timeRange;
    if (!(lastDays > 0 && lastDays <= 366)) throw new QueryError('timeRange.lastDays must be between 1 and 366.');
    const to = new Date(anchor.getTime() - offsetDays * DAY_MS);
    const from = new Date(to.getTime() - lastDays * DAY_MS);
    where.push(`${ds.timeColumn.sql} >= ${param(from.toISOString())}::timestamptz`);
    where.push(`${ds.timeColumn.sql} < ${param(to.toISOString())}::timestamptz`);
    window = { from: from.toISOString().slice(0, 10), to: new Date(to.getTime() - 1).toISOString().slice(0, 10) };
  }

  const dimCount = select.length - 1;
  const order: string[] = [];
  if (req.timeBucket) order.push('1');
  const byDimension = () => dims.map(({ field }, i) => field.orderSql ?? `${(req.timeBucket ? 2 : 1) + i}`);
  if (req.order === 'dimension') order.push(...byDimension());
  else if (req.timeBucket) order.push(...byDimension());
  else if (dimCount > 0) order.push(`${dimCount + 1} ${req.order === 'metric_asc' ? 'ASC' : 'DESC'} NULLS LAST`, ...byDimension());

  const limit = Math.min(Math.max(1, Math.floor(req.limit ?? (req.timeBucket ? 400 : 25))), 500);

  const sql = [
    `SELECT ${select.join(', ')}`,
    `FROM ${ds.from}`,
    where.length ? `WHERE ${where.join(' AND ')}` : '',
    dimCount > 0 ? `GROUP BY ${Array.from({ length: dimCount }, (_, i) => i + 1).join(', ')}` : '',
    order.length ? `ORDER BY ${order.join(', ')}` : '',
    dimCount > 0 ? `LIMIT ${limit}` : '',
  ].filter(Boolean).join('\n');

  return { sql, params, columns, window };
}

export async function runQuery(db: Db, req: QueryRequest, anchor: Date): Promise<QueryResult> {
  const built = buildQuery(req, anchor);
  const rows = await db.query<Row>(built.sql, built.params);
  return {
    columns: built.columns,
    rows,
    sql: inlineParams(built.sql, built.params),
    anchor: anchor.toISOString(),
    window: built.window,
  };
}

/** For display only — never executed. */
function inlineParams(sql: string, params: unknown[]): string {
  return params.reduceRight<string>(
    (text, value, i) => text.replaceAll(`$${i + 1}`, typeof value === 'number' ? String(value) : `'${String(value).replaceAll("'", "''")}'`),
    sql,
  );
}
