import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { fileURLToPath } from 'node:url';

/*
 * End to end through the MCP protocol, the way Claude Desktop drives it: launches the stdio bridge (as Desktop
 * does) against the running backend and calls every tool — as a real Keycloak user, then checks that tenants are
 * enforced. Start Keycloak (npm run db:up) and the backend (npm start) first.
 */

const KEYCLOAK = process.env.OIDC_ISSUER ?? 'http://localhost:8080/realms/nms';
const bridge = fileURLToPath(new URL('../bridge/McpStdioBridge.java', import.meta.url));
const java = process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java` : 'java';

/** A demo user's access token, through the local-only test client (password grant). */
async function tokenFor(user: string): Promise<string> {
  const res = await fetch(`${KEYCLOAK}/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({ grant_type: 'password', client_id: 'nms-cli', username: user, password: 'password' }),
  });
  const body = (await res.json()) as { access_token?: string; error_description?: string };
  if (!body.access_token) throw new Error(`No token for ${user}: ${body.error_description}`);
  return body.access_token;
}

async function connect(user: string): Promise<Client> {
  const client = new Client({ name: `smoke-${user}`, version: '0.0.0' });
  await client.connect(new StdioClientTransport({ command: java, args: [bridge], env: { ...process.env, NMS_TOKEN: await tokenFor(user) } as Record<string, string>, stderr: 'ignore' }));
  return client;
}

const client = await connect('admin');

let failures = 0;
const check = (label: string, ok: boolean, detail = '') => {
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures++;
};
const textOf = (r: { content?: unknown }) => ((r.content as { type: string; text?: string }[]) ?? []).map((c) => c.text ?? '').join('\n');

const { tools } = await client.listTools();
const names = tools.map((t) => t.name).sort();
check('tools listed — charts stay in the chat, nothing pins to the dashboard', names.join(',') === 'list_datasets,query_data,run_chart,show_chart', names.join(', '));
const showChart = tools.find((t) => t.name === 'show_chart');
check('show_chart declares the panel', (showChart?._meta as { ui?: { resourceUri?: string } })?.ui?.resourceUri === 'ui://nms/ask-dashboard.html');

const { resources } = await client.listResources();
const panel = await client.readResource({ uri: 'ui://nms/ask-dashboard.html' });
const html = (panel.contents[0] as { text?: string; mimeType?: string }) ?? {};
check('panel resource served', resources.length === 1 && (html.text?.length ?? 0) > 100_000, `${html.mimeType}, ${Math.round((html.text?.length ?? 0) / 1024)} KB`);

const datasets = await client.callTool({ name: 'list_datasets', arguments: {} });
check('list_datasets', textOf(datasets).includes('"connectivity"'));

const query = await client.callTool({ name: 'query_data', arguments: { dataset: 'devices', metric: 'offline_count', groupBy: ['farm'], limit: 3 } });
check('query_data finds Hoeve De Linde on top', textOf(query).split('\n')[2]?.startsWith('Hoeve De Linde') ?? false, textOf(query).split('\n')[2]);

const chartSpec = {
  title: 'Gateway issues by country',
  chartType: 'bar',
  compare: 'previous_period',
  insight: 'Germany quadrupled week on week.',
  query: { dataset: 'issues', metric: 'issue_count', groupBy: ['country'], filters: [{ field: 'issue_type', op: 'eq', value: 'Gateway not answering' }], timeRange: { lastDays: 7 } },
};
const chart = await client.callTool({ name: 'show_chart', arguments: chartSpec });
const sc = chart.structuredContent as { result?: { rows: unknown[] }; previous?: unknown; drill?: Record<string, string> } | undefined;
check('show_chart returns chart data for the panel', !!sc?.result?.rows.length && !!sc.previous && sc.drill?.country === 'farm', `${sc?.result?.rows.length} rows, drill country→${sc?.drill?.country}`);

const drill = await client.callTool({ name: 'run_chart', arguments: { ...chartSpec, query: { ...chartSpec.query, groupBy: ['farm'], filters: [...chartSpec.query.filters, { field: 'country', op: 'eq', value: 'Germany' }] } } });
check('run_chart drills into Germany', ((drill.structuredContent as { result?: { rows: unknown[] } })?.result?.rows.length ?? 0) > 0);

const bad = await client.callTool({ name: 'query_data', arguments: { dataset: 'devices', metric: 'offline_count', groupBy: ['region'] } });
check('bad field is a helpful error', bad.isError === true && textOf(bad).includes('Available:'), textOf(bad).slice(0, 80));

await client.close();

// Authorization: the same tools, as users who may see and do less -----------------------------------------------
const dairy = await connect('dairyprod');
const dairyDatasets = textOf(await dairy.callTool({ name: 'list_datasets', arguments: {} }));
const tenantValues = (JSON.parse(dairyDatasets) as { id: string; dimensions: { key: string; values?: string[] }[] }[])
  .find((d) => d.id === 'farms')!.dimensions.find((d) => d.key === 'tenant')!.values!;
check('dairyprod is offered only its own tenant', tenantValues.join(',') === 'Dairy Prod', tenantValues.join(', '));
const dairyFarms = textOf(await dairy.callTool({ name: 'query_data', arguments: { dataset: 'farms', metric: 'farm_count', groupBy: ['tenant'] } }));
check('dairyprod queries see only Dairy Prod farms', !/Feedlot|Poultry|Dairy Dev/.test(dairyFarms) && dairyFarms.includes('Dairy Prod | 40'), dairyFarms.split('\n').slice(2).join('; '));
const asked = textOf(await dairy.callTool({ name: 'query_data', arguments: { dataset: 'farms', metric: 'farm_count', filters: [{ field: 'tenant', op: 'eq', value: 'Feedlot Prod' }] } }));
check('dairyprod cannot filter its way into another tenant', /\n0$|\n$/.test(asked) || asked.split('\n')[2] === '0', asked.split('\n')[2]);
await dairy.close();

const feedlot = await connect('feedlotprod');
const feedlotTop = textOf(await feedlot.callTool({ name: 'query_data', arguments: { dataset: 'devices', metric: 'offline_count', groupBy: ['farm'], limit: 1 } }));
check('feedlotprod never sees Hoeve De Linde (a Dairy Prod farm)', !feedlotTop.includes('Hoeve De Linde'), feedlotTop.split('\n')[2]);
await feedlot.close();

// The dashboard's tenant and farm pages: the same tenant scope over REST ----------------------------------------
const api = async (path: string, user: string) => fetch(`http://localhost:4300${path}`, { headers: { authorization: `Bearer ${await tokenFor(user)}` } });
const tenants = (await (await api('/api/tenants', 'admin')).json()) as { id: string; name: string }[];
const dairyProd = tenants.find((t) => t.name === 'Dairy Prod')!;
const feedlotProd = tenants.find((t) => t.name === 'Feedlot Prod')!;
const tenantPage = (await (await api(`/api/tenants/${dairyProd.id}`, 'admin')).json()) as { farms: { id: string; name: string; offlineDevices: number }[] };
const linde = tenantPage.farms.find((f) => f.name === 'Hoeve De Linde');
check('tenant page lists Dairy Prod\'s 40 farms, Hoeve De Linde with 44 offline', tenantPage.farms.length === 40 && linde?.offlineDevices === 44, `${tenantPage.farms.length} farms, ${linde?.offlineDevices} offline`);
const farmPage = (await (await api(`/api/farms/${linde?.id}`, 'admin')).json()) as { devices: unknown[]; issues: unknown[]; connectivity: unknown[] };
check('farm page has devices, issues and 30 days of connectivity', farmPage.devices.length > 0 && farmPage.issues.length > 0 && farmPage.connectivity.length === 30, `${farmPage.devices.length} devices, ${farmPage.issues.length} issues`);
const feedlotFarm = ((await (await api(`/api/tenants/${feedlotProd.id}`, 'admin')).json()) as { farms: { id: string }[] }).farms[0].id;
const [otherTenant, otherFarm] = await Promise.all([api(`/api/tenants/${feedlotProd.id}`, 'dairyprod'), api(`/api/farms/${feedlotFarm}`, 'dairyprod')]);
check('dairyprod cannot open a Feedlot Prod tenant or farm page', otherTenant.status === 404 && otherFarm.status === 404, `${otherTenant.status}, ${otherFarm.status}`);

// Tenant administration: admin only, validated, and a tenant with farms is never deleted -------------------------
const send = async (method: string, path: string, user: string, body?: unknown) =>
  fetch(`http://localhost:4300${path}`, {
    method,
    headers: { authorization: `Bearer ${await tokenFor(user)}`, 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
const smokeTenant = { name: `Smoke Tenant ${Date.now()}`, environment: 'Staging', specie: 'Poultry' };
const notAdmin = await send('POST', '/api/tenants', 'dairyprod', smokeTenant);
check('a non-admin cannot create a tenant', notAdmin.status === 403, String(notAdmin.status));
const invalid = await send('POST', '/api/tenants', 'admin', { name: 'x', environment: 'Prod', specie: 'Sheep' });
check('an invalid tenant is a 400 naming the problems', invalid.status === 400 && ((await invalid.json()) as { error: string }).error.includes('environment must be one of'));
const createdRes = await send('POST', '/api/tenants', 'admin', smokeTenant);
const created = (await createdRes.json()) as { id: string; name: string; color: string; farms: number };
check('admin creates a tenant', createdRes.status === 201 && created.name === smokeTenant.name && created.farms === 0, `${createdRes.status} ${created.id}`);
const duplicate = await send('POST', '/api/tenants', 'admin', { ...smokeTenant, name: smokeTenant.name.toUpperCase() });
check('a duplicate name is a 409', duplicate.status === 409, String(duplicate.status));
const edited = (await (await send('PATCH', `/api/tenants/${created.id}`, 'admin', { color: '#23885c' })).json()) as { color: string; environment: string };
check('admin edits a tenant; what is left out stays', edited.color === '#23885C' && edited.environment === 'Staging', `${edited.color}, ${edited.environment}`);
const editByOther = await send('PATCH', `/api/tenants/${created.id}`, 'dairyprod', { name: 'Taken over' });
const deleteByOther = await send('DELETE', `/api/tenants/${created.id}`, 'dairyprod');
check('a non-admin cannot edit or delete a tenant', editByOther.status === 403 && deleteByOther.status === 403, `${editByOther.status}, ${deleteByOther.status}`);
const withFarms = await send('DELETE', `/api/tenants/${dairyProd.id}`, 'admin');
check('a tenant that has farms is not deleted (409)', withFarms.status === 409, ((await withFarms.json()) as { error: string }).error);
const deleted = await send('DELETE', `/api/tenants/${created.id}`, 'admin');
const gone = await send('GET', `/api/tenants/${created.id}`, 'admin');
check('admin deletes an empty tenant', deleted.status === 204 && gone.status === 404, `${deleted.status}, then ${gone.status}`);

// Farm administration: admin only; a farm takes its tenant's specie; data goes only with cascade ------------------
const smokeFarm = { tenantId: dairyProd.id, name: `Smoke Farm ${Date.now()}`, address: 'Drachten', country: 'Netherlands', latitude: 53.1, longitude: 6.09, version: '7.6.0.1' };
const farmByOther = await send('POST', '/api/farms', 'dairyprod', smokeFarm);
check('a non-admin cannot create a farm', farmByOther.status === 403, String(farmByOther.status));
const badFarm = await send('POST', '/api/farms', 'admin', { ...smokeFarm, latitude: 200, protocol: 'Wifi' });
check('an invalid farm is a 400 naming the problems', badFarm.status === 400 && ((await badFarm.json()) as { error: string }).error.includes('latitude must be between'));
const farmRes = await send('POST', '/api/farms', 'admin', smokeFarm);
const farm = (await farmRes.json()) as { id: string; specie: string; environment: string; connectivityStatus: string; tenantName: string };
check('admin creates a farm in its tenant\'s specie and environment', farmRes.status === 201 && farm.specie === 'Dairy' && farm.environment === 'Production' && farm.connectivityStatus === 'UNKNOWN', `${farmRes.status} ${farm.id}`);
const dupFarm = await send('POST', '/api/farms', 'admin', { ...smokeFarm, name: 'hoeve de linde' });
check('a farm name another farm has is a 409', dupFarm.status === 409, String(dupFarm.status));
const upgraded = (await (await send('PATCH', `/api/farms/${farm.id}`, 'admin', { version: '7.7.0.0', animals: 250 })).json()) as { version: string; animals: number; lastUpgradeAt: string | null; address: string };
check('admin edits a farm; a new version records an upgrade', upgraded.version === '7.7.0.0' && upgraded.animals === 250 && !!upgraded.lastUpgradeAt && upgraded.address === 'Drachten', `${upgraded.version}, upgraded ${upgraded.lastUpgradeAt}`);
const moved = (await (await send('PATCH', `/api/farms/${farm.id}`, 'admin', { tenantId: feedlotProd.id })).json()) as { tenantName: string; specie: string };
check('moving a farm to another tenant takes that tenant\'s specie', moved.tenantName === 'Feedlot Prod' && moved.specie === 'Feedlot', `${moved.tenantName}, ${moved.specie}`);
const farmEditByOther = await send('PATCH', `/api/farms/${farm.id}`, 'feedlotprod', { name: 'Taken over' });
const farmDeleteByOther = await send('DELETE', `/api/farms/${farm.id}`, 'feedlotprod');
check('a non-admin cannot edit or delete a farm', farmEditByOther.status === 403 && farmDeleteByOther.status === 403, `${farmEditByOther.status}, ${farmDeleteByOther.status}`);
const lindeDelete = await send('DELETE', `/api/farms/${linde?.id}`, 'admin');
check('a farm with devices and issues is not deleted without cascade (409)', lindeDelete.status === 409, ((await lindeDelete.json()) as { error: string }).error.slice(0, 90));
const farmDeleted = await send('DELETE', `/api/farms/${farm.id}`, 'admin');
const farmGone = await send('GET', `/api/farms/${farm.id}`, 'admin');
check('admin deletes an empty farm', farmDeleted.status === 204 && farmGone.status === 404, `${farmDeleted.status}, then ${farmGone.status}`);

// No token at all: the backend's 401 points at its OAuth metadata (MCP authorization) --------------------------
const post = (body: string) => fetch('http://localhost:4300/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream' }, body });
const listed401 = await post('{"jsonrpc":"2.0","id":1,"method":"tools/list"}');
check('no token: discovery (tools/list) is open, so Claude can start without a sign-in', listed401.status === 200);
const anonymous = await post('{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"query_data","arguments":{"dataset":"farms","metric":"farm_count"}}}');
check('no token: data (tools/call) is 401 with resource_metadata', anonymous.status === 401 && (anonymous.headers.get('www-authenticate') ?? '').includes('resource_metadata='), anonymous.headers.get('www-authenticate') ?? '');

console.log(failures ? `\n${failures} check(s) failed` : '\nAll checks passed');
process.exit(failures ? 1 : 0);
