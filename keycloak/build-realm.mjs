/*
 * Builds the `nms` realm through Keycloak's admin API and exports it to keycloak/nms-realm.json, the file
 * docker compose imports. Run it only to change the realm's design (a new client, user or claim):
 *
 *   docker compose up -d --wait keycloak      # with no realm imported yet, or the old one deleted in the console
 *   node keycloak/build-realm.mjs
 *
 * What it builds:
 *  - client scope `nms`, a default for every client — dynamically registered MCP clients included — that puts
 *    `aud: nms-ask-dashboard`, `tenants` and `nms_access` into access tokens;
 *  - clients: `nms-dashboard` (the Angular app: code + PKCE), `nms-bridge` (Claude Desktop's stdio bridge: device
 *    flow), `nms-cli` (scripts and tests: password grant — local only);
 *  - anonymous dynamic client registration for loopback redirect URIs, which is how Claude Code registers itself;
 *  - demo users: one per tenant, sees only that tenant; and `admin`, who sees every tenant. Password: `password`.
 */
import { writeFile } from 'node:fs/promises';

const KC = process.env.KEYCLOAK_URL ?? 'http://localhost:8080';
const REALM = 'nms';
const AUDIENCE = 'nms-ask-dashboard';

/* Tenant ids as seeded by the backend's EstateGenerator. */
const TENANT = {
  dairyProd: '77c2c8fc-1266-46e5-937f-e1c84fe42fb8',
  feedlotProd: '2a703fc9-b51a-464f-829b-6b23aa5b3cc9',
  poultryProd: '0576720d-0297-4d4b-bdaf-0e024db11611',
  dairyDev: 'e2362248-5c59-44e3-b5f3-d551a879835a',
  feedlotStaging: '6558cf86-ad58-4c58-9464-630cdff8a2ae',
};

/* One user per tenant and one admin — every password is `password`. */
export const PASSWORD = 'password';
const USERS = [
  { username: 'admin', firstName: 'NMS', lastName: 'Admin', tenants: ['*'], access: 'write' },
  { username: 'dairyprod', firstName: 'Dairy Prod', lastName: 'User', tenants: [TENANT.dairyProd], access: 'write' },
  { username: 'feedlotprod', firstName: 'Feedlot Prod', lastName: 'User', tenants: [TENANT.feedlotProd], access: 'write' },
  { username: 'poultryprod', firstName: 'Poultry Prod', lastName: 'User', tenants: [TENANT.poultryProd], access: 'write' },
  { username: 'dairydev', firstName: 'Dairy Dev', lastName: 'User', tenants: [TENANT.dairyDev], access: 'write' },
  { username: 'feedlotstaging', firstName: 'Feedlot Staging', lastName: 'User', tenants: [TENANT.feedlotStaging], access: 'write' },
];

const token = await adminToken();

async function adminToken() {
  const res = await fetch(`${KC}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' }),
  });
  if (!res.ok) throw new Error(`Admin login failed: ${res.status}`);
  return (await res.json()).access_token;
}

async function api(method, path, body) {
  const res = await fetch(`${KC}/admin${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, ...(body ? { 'content-type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok && res.status !== 409) throw new Error(`${method} ${path} → ${res.status} ${await res.text()}`);
  const text = await res.text();
  return text ? JSON.parse(text) : undefined;
}

// Realm ------------------------------------------------------------------------------------------------------
await fetch(`${KC}/admin/realms/${REALM}`, { method: 'DELETE', headers: { authorization: `Bearer ${token}` } }); // rebuild from scratch
await api('POST', '/realms', {
  realm: REALM,
  displayName: 'NMS',
  enabled: true,
  sslRequired: 'none', // localhost; production runs behind TLS and sets this to "external"
  accessTokenLifespan: 900,
  ssoSessionIdleTimeout: 28_800,
  oauth2DeviceCodeLifespan: 600,
  loginWithEmailAllowed: true,
  registrationAllowed: false,
});
console.log('realm', REALM);

// The `tenants` and `nms_access` attributes live outside the user profile's declared fields.
const profile = await api('GET', `/realms/${REALM}/users/profile`);
await api('PUT', `/realms/${REALM}/users/profile`, { ...profile, unmanagedAttributePolicy: 'ENABLED' });

// Client scope `nms` -----------------------------------------------------------------------------------------
const attributeMapper = (name, claim, multivalued) => ({
  name,
  protocol: 'openid-connect',
  protocolMapper: 'oidc-usermodel-attribute-mapper',
  config: {
    'user.attribute': claim, 'claim.name': claim, 'jsonType.label': 'String', multivalued: String(multivalued),
    'access.token.claim': 'true', 'id.token.claim': 'true', 'userinfo.token.claim': 'true', 'introspection.token.claim': 'true',
  },
});
await api('POST', `/realms/${REALM}/client-scopes`, {
  name: 'nms',
  description: 'NMS Ask the Dashboard: audience, tenants, access level',
  protocol: 'openid-connect',
  attributes: { 'include.in.token.scope': 'false', 'display.on.consent.screen': 'true', 'consent.screen.text': 'Read your NMS estate data and manage your dashboard' },
  protocolMappers: [
    {
      name: 'audience',
      protocol: 'openid-connect',
      protocolMapper: 'oidc-audience-mapper',
      config: { 'included.custom.audience': AUDIENCE, 'access.token.claim': 'true', 'introspection.token.claim': 'true' },
    },
    attributeMapper('tenants', 'tenants', true),
    attributeMapper('access level', 'nms_access', false),
  ],
});
const scopes = await api('GET', `/realms/${REALM}/client-scopes`);
const nmsScope = scopes.find((s) => s.name === 'nms');
await api('PUT', `/realms/${REALM}/default-default-client-scopes/${nmsScope.id}`);
console.log('client scope nms (realm default)');

// Clients ------------------------------------------------------------------------------------------------------
const publicClient = (clientId, name, extra) => ({
  clientId, name, protocol: 'openid-connect', publicClient: true, enabled: true,
  standardFlowEnabled: false, directAccessGrantsEnabled: false, implicitFlowEnabled: false, serviceAccountsEnabled: false,
  defaultClientScopes: ['basic', 'profile', 'email', 'roles', 'web-origins', 'acr', 'nms'],
  ...extra,
});
await api('POST', `/realms/${REALM}/clients`, publicClient('nms-dashboard', 'NMS Ask the Dashboard (web)', {
  standardFlowEnabled: true,
  rootUrl: 'http://localhost:4300',
  redirectUris: ['http://localhost:4300/*', 'http://localhost:4200/*'],
  webOrigins: ['http://localhost:4300', 'http://localhost:4200'],
  attributes: { 'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': 'http://localhost:4300/*##http://localhost:4200/*' },
}));
await api('POST', `/realms/${REALM}/clients`, publicClient('nms-bridge', 'NMS Ask the Dashboard (Claude Desktop bridge)', {
  // The bridge opens the sign-in page and takes the code on http://127.0.0.1:<any port>/callback (RFC 8252), with
  // PKCE; the device flow stays on for machines without a local browser.
  standardFlowEnabled: true,
  redirectUris: ['http://127.0.0.1/*'],
  attributes: { 'oauth2.device.authorization.grant.enabled': 'true', 'pkce.code.challenge.method': 'S256' },
  // offline_access: a refresh token that outlives the browser session, so Claude Desktop signs in once.
  optionalClientScopes: ['offline_access'],
}));
await api('POST', `/realms/${REALM}/clients`, publicClient('nms-cli', 'NMS scripts and tests (local only)', {
  directAccessGrantsEnabled: true,
}));
console.log('clients nms-dashboard, nms-bridge, nms-cli');

// Dynamic client registration: MCP clients (Claude Code) register themselves with a loopback redirect URI. -------
const components = await api('GET', `/realms/${REALM}/components?type=org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy`);
const trustedHosts = components.find((c) => c.providerId === 'trusted-hosts' && c.subType === 'anonymous');
await api('PUT', `/realms/${REALM}/components/${trustedHosts.id}`, {
  ...trustedHosts,
  config: { 'trusted-hosts': ['localhost', '127.0.0.1'], 'host-sending-registration-request-must-match': ['false'], 'client-uris-must-match': ['true'] },
});
const allowedScopes = components.find((c) => c.providerId === 'allowed-client-templates' && c.subType === 'anonymous');
if (allowedScopes) {
  await api('PUT', `/realms/${REALM}/components/${allowedScopes.id}`, { ...allowedScopes, config: { ...allowedScopes.config, 'allow-default-scopes': ['true'] } });
}
console.log('anonymous client registration for localhost redirect URIs');

// Users ------------------------------------------------------------------------------------------------------------
for (const u of USERS) {
  await api('POST', `/realms/${REALM}/users`, {
    username: u.username, firstName: u.firstName, lastName: u.lastName, email: `${u.username}@nms.example`, emailVerified: true, enabled: true,
    attributes: { tenants: u.tenants, nms_access: [u.access] },
    credentials: [{ type: 'password', value: PASSWORD, temporary: false }],
  });
  console.log(`user ${u.username} / ${PASSWORD} — tenants ${u.tenants.join(', ')}, ${u.access}`);
}

// Export -------------------------------------------------------------------------------------------------------------
// partial-export leaves users and secrets out; the users go back in with plain passwords, which import hashes.
const realm = await api('POST', `/realms/${REALM}/partial-export?exportClients=true&exportGroupsAndRoles=true`);
const users = await api('GET', `/realms/${REALM}/users?briefRepresentation=false`);
realm.users = USERS.map((u) => {
  const { id, createdTimestamp, access, ...exported } = users.find((x) => x.username === u.username);
  // The users endpoint leaves role mappings out; without the realm's default roles an imported user may not hold
  // offline_access, and the bridge's device sign-in is refused.
  return { ...exported, realmRoles: [`default-roles-${REALM}`], credentials: [{ type: 'password', value: PASSWORD, temporary: false }] };
});
realm.components = realm.components ?? {};
await writeFile(new URL('./nms-realm.json', import.meta.url), `${JSON.stringify(realm, null, 2)}\n`);
console.log('exported keycloak/nms-realm.json');
