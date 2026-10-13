import { describe, expect, it } from 'vitest';
import { buildQuery, QueryError } from '../src/api/datasets.ts';
import { place } from '../src/api/charts.ts';

const anchor = new Date('2026-10-12T00:00:00Z');

describe('buildQuery', () => {
  it('passes every caller value as a parameter, never in the SQL', () => {
    const evil = "'; DROP TABLE farms; --";
    const { sql, params } = buildQuery(
      { dataset: 'devices', metric: 'offline_count', groupBy: ['farm'], filters: [{ field: 'country', op: 'eq', value: evil }, { field: 'farm', op: 'contains', value: evil }] },
      anchor,
    );
    expect(sql).not.toContain('DROP');
    expect(params).toEqual([evil, `%${evil}%`]);
  });

  it('refuses identifiers outside the catalogue, listing the ones it has', () => {
    expect(() => buildQuery({ dataset: 'devices', metric: 'offline_count', groupBy: ['farm; DROP TABLE x'] }, anchor)).toThrow(QueryError);
    expect(() => buildQuery({ dataset: 'devices', metric: 'nope' }, anchor)).toThrow(/Available: device_count, offline_count/);
    expect(() => buildQuery({ dataset: 'users', metric: 'x' }, anchor)).toThrow(/Available: farms, devices, issues, connectivity/);
    expect(() => buildQuery({ dataset: 'devices', metric: 'device_count', filters: [{ field: 'secret', op: 'eq', value: 1 }] }, anchor)).toThrow(/battery_pct/);
  });

  it('expands an in filter to one parameter per value', () => {
    const { sql, params } = buildQuery({ dataset: 'farms', metric: 'farm_count', filters: [{ field: 'country', op: 'in', value: ['Germany', 'France'] }] }, anchor);
    expect(sql).toContain('f.country IN ($1, $2)');
    expect(params).toEqual(['Germany', 'France']);
  });

  it('places a time window relative to the anchor, shifted by offsetDays', () => {
    const { params, window } = buildQuery({ dataset: 'issues', metric: 'issue_count', timeRange: { lastDays: 7, offsetDays: 7 } }, anchor);
    expect(params).toEqual(['2026-09-28T00:00:00.000Z', '2026-10-05T00:00:00.000Z']);
    expect(window).toEqual({ from: '2026-09-28', to: '2026-10-04' });
  });

  it('orders severities CRITICAL to LOW, not alphabetically', () => {
    const { sql } = buildQuery({ dataset: 'issues', metric: 'open_count', groupBy: ['severity'], order: 'dimension' }, anchor);
    expect(sql).toMatch(/ORDER BY CASE i\.severity WHEN 'CRITICAL' THEN 1/);
  });

  it('caps groupBy at two dimensions and the limit at 500', () => {
    expect(() => buildQuery({ dataset: 'issues', metric: 'issue_count', groupBy: ['tenant', 'severity', 'farm'] }, anchor)).toThrow(/at most two/);
    expect(buildQuery({ dataset: 'issues', metric: 'issue_count', groupBy: ['farm'], limit: 10_000 }, anchor).sql).toMatch(/LIMIT 500$/);
  });
});

describe('place', () => {
  it('fills the first free slot left to right, then top to bottom', () => {
    expect(place([], 6, 3)).toEqual({ x: 0, y: 0 });
    expect(place([{ x: 0, y: 0, cols: 6, rows: 3 }], 6, 3)).toEqual({ x: 6, y: 0 });
    expect(place([{ x: 0, y: 0, cols: 6, rows: 3 }, { x: 6, y: 0, cols: 6, rows: 3 }], 4, 2)).toEqual({ x: 0, y: 3 });
    expect(place([{ x: 0, y: 0, cols: 3, rows: 2 }], 12, 2)).toEqual({ x: 0, y: 2 });
  });
});
