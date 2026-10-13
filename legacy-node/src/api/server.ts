import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadEnv } from './env.ts';
import { openDb, type Db } from './db.ts';
import { seedDatabase } from './seed.ts';
import { describeDatasets, QueryError, runQuery } from './datasets.ts';
import { addWidget, clearWidgets, getAnchor, listWidgets, removeWidget, runChart, subscribe } from './charts.ts';
import type { ChartSpec, QueryRequest } from '../shared/types.ts';

/*
 * The stand-in for the NMS backend: the only process that touches Postgres. The MCP server and the dashboard are
 * both clients of it, which is also why PGlite works here — one process owns the database.
 */

loadEnv();
const PORT = Number(process.env.PORT ?? 4300);
const DASHBOARD_DIR = fileURLToPath(new URL('../../dist/dashboard', import.meta.url));
const PANEL_FILE = fileURLToPath(new URL('../../dist/panel/index.html', import.meta.url));

const db = await openDb();
const anchor = await getAnchor(db).catch(async () => {
  console.log('Database is empty — seeding the estate…');
  const estate = await seedDatabase(db);
  console.log(`Seeded ${estate.farms.length} farms, ${estate.devices.length} devices, ${estate.issues.length} issues.`);
  return estate.anchor;
});

type Handler = (req: IncomingMessage, res: ServerResponse, params: Record<string, string>) => Promise<unknown>;
const routes: { method: string; pattern: RegExp; keys: string[]; handler: Handler }[] = [];

function route(method: string, path: string, handler: Handler): void {
  const keys: string[] = [];
  const pattern = new RegExp(`^${path.replace(/:(\w+)/g, (_, key) => (keys.push(key), '([^/]+)'))}$`);
  routes.push({ method, pattern, keys, handler });
}

route('GET', '/api/health', async () => ({ ok: true, db: db.kind, anchor: anchor.toISOString() }));
route('GET', '/api/datasets', async () => describeDatasets(db));
route('POST', '/api/query', async (req) => runQuery(db, await body<QueryRequest>(req), anchor));
route('POST', '/api/chart', async (req) => runChart(db, await body<ChartSpec>(req), anchor));
route('GET', '/api/widgets', async () => listWidgets(db));
route('POST', '/api/widgets', async (req) => {
  const { spec, cols, rows, source } = await body<{ spec: ChartSpec; cols?: number; rows?: number; source?: 'ai' | 'user' }>(req);
  await runChart(db, spec, anchor); // a widget whose query fails is refused here, not discovered on the dashboard
  return addWidget(db, spec, { cols, rows }, source);
});
route('DELETE', '/api/widgets', async () => (await clearWidgets(db), { ok: true }));
route('DELETE', '/api/widgets/:id', async (_req, _res, { id }) => ({ ok: await removeWidget(db, id) }));
// The built MCP App, for the local test host (/host.html) — Claude Desktop gets it from the MCP server instead.
route('GET', '/panel.html', async (_req, res) => {
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }).end(await readFile(PANEL_FILE));
  return STREAMING;
});
route('GET', '/api/events', async (req, res) => {
  res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
  res.write(': connected\n\n');
  const unsubscribe = subscribe((event) => res.write(`data: ${JSON.stringify(event)}\n\n`));
  const ping = setInterval(() => res.write(': ping\n\n'), 15_000);
  req.on('close', () => (clearInterval(ping), unsubscribe()));
  return STREAMING;
});

const STREAMING = Symbol('streaming');

const server = createServer(async (req, res) => {
  res.setHeader('access-control-allow-origin', '*');
  res.setHeader('access-control-allow-headers', 'content-type');
  res.setHeader('access-control-allow-methods', 'GET, POST, DELETE, OPTIONS');
  if (req.method === 'OPTIONS') return void res.writeHead(204).end();

  const url = new URL(req.url ?? '/', 'http://localhost');
  for (const r of routes) {
    const match = r.method === req.method ? r.pattern.exec(url.pathname) : null;
    if (!match) continue;
    try {
      const params = Object.fromEntries(r.keys.map((key, i) => [key, decodeURIComponent(match[i + 1])]));
      const result = await r.handler(req, res, params);
      if (result !== STREAMING) json(res, 200, result);
    } catch (error) {
      const status = error instanceof QueryError || error instanceof SyntaxError ? 400 : 500;
      if (status === 500) console.error(error);
      json(res, status, { error: error instanceof Error ? error.message : String(error) });
    }
    return;
  }
  if (req.method === 'GET' && !url.pathname.startsWith('/api/')) return serveStatic(url.pathname, res);
  json(res, 404, { error: `No route for ${req.method} ${url.pathname}` });
});

server.listen(PORT, () => {
  console.log(`NMS Ask-the-Dashboard API on http://localhost:${PORT}  (database: ${db.kind}, anchor ${anchor.toISOString().slice(0, 10)})`);
  console.log(`Live dashboard:             http://localhost:${PORT}/`);
});

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, async () => {
    server.close();
    await db.close();
    process.exit(0);
  });
}

// ---------------------------------------------------------------------------------------------- helpers

async function body<T>(req: IncomingMessage): Promise<T> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}') as T;
}

function json(res: ServerResponse, status: number, data: unknown): void {
  res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(data));
}

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2',
};

async function serveStatic(pathname: string, res: ServerResponse): Promise<void> {
  const file = normalize(join(DASHBOARD_DIR, pathname === '/' ? 'index.html' : pathname));
  if (!file.startsWith(DASHBOARD_DIR)) return void res.writeHead(403).end();
  try {
    const content = await readFile(file);
    res.writeHead(200, { 'content-type': MIME[extname(file)] ?? 'application/octet-stream' }).end(content);
  } catch {
    if (pathname === '/') {
      res.writeHead(200, { 'content-type': 'text/html' }).end('<p>The dashboard is not built yet — run <code>npm run build</code>.</p>');
    } else {
      res.writeHead(404).end();
    }
  }
}

