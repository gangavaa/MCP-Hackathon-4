import type { ChartSpec } from '@shared/types';

/** The demo's questions, with the spec Claude would write for each — the numbers in their insights are pinned by the backend's EstateGeneratorTest. */
export const QUESTIONS: { ask: string; spec: ChartSpec }[] = [
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
