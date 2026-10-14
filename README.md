# NMS Dashboard

Ask a question about the NMS estate in plain English. A live, interactive chart appears **inside the chat**. Click it to drill down. Every answer stays in the chat. The NMS dashboard at http://localhost:4300/ is a **fixed** page, like nms-core-ui's, and nothing is added to it from the chat.

The project is built on [MCP Apps](https://github.com/modelcontextprotocol/ext-apps). With MCP Apps, an MCP tool can return an interactive UI that renders in Claude.

```
Claude Desktop ──stdio──► bridge/McpStdioBridge.java ──HTTP──┐
Claude Code ───────────────────────────────────HTTP──────────┤
                                                              ▼
                        mcp-server/ (Spring Boot 3, Java 21) — port 4301
                          /mcp          MCP gateway: 4 tools + the panel resource. No data, no SQL.
                                │
                                │  HTTP, with the signed-in user's own token
                                ▼
                        backend/ (Spring Boot 3, Java 21) — port 4300
                          /api/*        REST API — knows nothing about MCP
                          /, /host      the Angular build (frontend/dist)
                                │
                                ▼
                   PostgreSQL (docker compose, or embedded)

frontend/ (Angular 22 + Tailwind v4)
  projects/dashboard   the fixed estate dashboard (/) and the panel test host (/host)
  projects/panel       the MCP App rendered in the chat, built into one HTML file
  shared/              chart renderer (ECharts), types and NMS design tokens used by both
```

| Part | What it is |
|---|---|
| `mcp-server/` | Spring Boot 3.5, Java 21. The MCP server Claude talks to: a **gateway in front of the REST API**. Each tool is one or two calls to `backend/`'s `/api` (`api/NmsApiClient`), made with the caller's own access token, so the API applies the same tenant scope and access checks as for the dashboard. It adds only what a chat needs: tool descriptions and schemas, rows as text for the model, a PNG of each chart (`ChartImageRenderer`) and the panel. API errors become tool errors in the API's own words. |
| `backend/` | Spring Boot 3.5, Java 21. The REST API and the only process that touches Postgres; it has no MCP code. It's organised by domain (see [Backend structure](#backend-structure)). Flyway owns the schema. |
| `frontend/` | An Angular 22 workspace with two apps that share one chart component. The dashboard (`estate-dashboard-page.ts`) is fixed, laid out after nms-core-ui's: severity summary cards, the tenants overview, alerts by severity and the specie groups. It reads `/api/query` and `/api/chart` and refreshes every minute. A tenant's name opens its page (`/tenants/:id`: farm overview, alert summary, issue definitions, farms needing attention, every farm), and a farm opens its farm card (`/farms/:id`: identity, alerts by severity, devices by type, then Overview, Devices, Issues, Statistics and Properties tabs). Both pages read `/api/tenants/:id` and `/api/farms/:id`, which are scoped to the user's tenants like every query. |
| `bridge/` | A single-file Java program that Claude Desktop launches. It relays stdio to the MCP server's `/mcp`, so every Desktop session shares one server instead of starting its own. |
| `scripts/` | `smoke.ts` (end-to-end check over MCP) and `claude-desktop.ts` (Desktop setup) |

## Backend structure

The backend has one package per domain, under `com.nms.dashboard`. Each domain is split into the same layers:

| Layer | Holds | Depends on |
|---|---|---|
| `domain/` | Entities and value objects with their rules, repository interfaces, domain events. No Spring and no SQL. | `shared/domain`, `shared/error` |
| `application/` | Use cases. Each one checks access, loads aggregates, applies the domain rules, saves, then publishes events and writes the audit log. | `domain/` |
| `infrastructure/` | JDBC implementations of the repositories. | `domain/` |
| `web/` | REST controllers and their request bodies. | `application/` |

| Package | What it is |
|---|---|
| `tenant/` | `Tenant` (with `Environment` and `Specie`): create, edit, delete. A tenant that still has farms isn't deleted. |
| `farm/` | `Farm` (with `Location`, `Protocol`, `ConnectivityStatus`): register, edit, move, delete. A farm takes its tenant's specie and environment. A new version is an upgrade. |
| `estate/` | The read model: what the tenant and farm pages show (`EstateQueries`). It's the query side of `tenant/` and `farm/`, which only change data. |
| `analytics/` | The semantic query layer that both the chat and the charts use: `DatasetCatalog`, `QueryBuilder`, `QueryService`. It listens for the `EstateChanged` event to re-read tenant and farm names. |
| `platform/` | `/api/health`, `/api/me`, and `/panel.html` (the built chat panel, for the test host) |
| `shared/` | Bearer-token sign-in for `/api` and tenant scope (`security/`), the shared tenant SQL filter and the estate clock (`persistence/`), domain errors (`error/`, mapped to HTTP status codes in `web/ApiExceptionHandler`), and `Problems`, which collects every validation error at once. |
| `seed/` | The demo estate generator. |

**Errors carry no HTTP status:** the domain raises `ValidationException`, `ConflictException`, `NotFoundException` and `ForbiddenException`, and only the web layer turns them into 400, 409, 404 and 403.

## Quick start

You need **JDK 21+**, **Maven 3.9+**, **Node 22.18+** and **Docker** (for Keycloak sign-in).

```bash
npm install && npm --prefix frontend install
npm run db:up      # Keycloak (sign-in, :8080) + Postgres (:5433)
npm run build      # Angular apps (frontend/dist) + backend and MCP server jars
npm start          # backend on http://localhost:4300 (seeds the database on first run)
npm run start:mcp  # MCP server on http://localhost:4301/mcp, in a second terminal — Claude connects here
```

Open http://localhost:4300/ and sign in as any user below. Every password is **`password`**.

- **Dashboard:** http://localhost:4300/
- **Panel test host:** http://localhost:4300/host. It renders the chat panel through the real MCP Apps bridge, with the demo questions as buttons. Use it to work on the panel, and as the backup if Claude misbehaves during the demo.

To work on the UI with hot reload, run `npm run dev:frontend`. That's `ng serve` on :4200, with `/api` proxied to the backend.

## Database

- **With Docker:** `npm run db:up` starts `postgres:17` on localhost:5433. Then `cp .env.example .env` sets `DATABASE_URL` to point at it. The backend reads the repo's `.env` itself.
- **Without Docker:** leave `DATABASE_URL` unset. The backend starts a **real embedded PostgreSQL** in-process, from binaries bundled in the jar, with data in `backend/.pgdata/`. The first start takes ~15 s while it creates the database.

`npm run start:reset` drops everything and seeds again. Re-seed on the morning of the demo so the dates read as "this week".

### The data

There are 5 tenants (the ids from nms-core-ui's fake-backend), 110 farms in 6 countries, 5,345 devices, 3,083 issues over 60 days, and a daily connectivity snapshot per farm. All of it comes from a fixed seed, with every date relative to the **anchor**: midnight UTC on the day it was seeded. So the numbers are the same on every machine. `EstateGeneratorTest` pins the three stories planted for the demo:

1. **Firmware.** Firmware 5.16.0 rolled out to 14 farms six days before the anchor. Those devices are offline **40%** of the time, against ~4% on 5.14 and 5.15. Estate-wide offline doubles from ~4.7% to ~9%. **Hoeve De Linde** (NL) is hit hardest, with 44 devices offline.
2. **Gateways.** German dairy farms raised **91** "Gateway not answering" issues in the last 7 days, against **19** the week before.
3. **Batteries.** All **410** low-battery devices are ear tags on Irish feedlots.

## Sign-in and access

| User | Sees | Can change the dashboard |
|---|---|---|
| `admin` | every tenant | yes |
| `dairyprod` | Dairy Prod | yes |
| `feedlotprod` | Feedlot Prod | yes |
| `poultryprod` | Poultry Prod | yes |
| `dairydev` | Dairy Dev | yes |
| `feedlotstaging` | Feedlot Staging | yes |

All passwords are `password`. The Keycloak admin console is at http://localhost:8080 (`admin` / `admin`), realm `nms`.

**How it works:**
- **Keycloak issues the tokens.** The realm is imported from `keycloak/nms-realm.json` on start. `keycloak/build-realm.mjs` documents the realm and rebuilds it.
- **The backend checks every `/api` request, and the MCP server every `/mcp` tool call.** Both verify the token's signature, issuer, expiry and audience (`aud: nms-ask-dashboard`).
- **What a user can see comes from the token**, not from the request:
  - `tenants` (tenant ids, or `*` for all) becomes a `t.id IN (…)` clause that `QueryBuilder` adds to **every** query, chart and dropdown value list;
  - `nms_access` (`read` or `write`) is read from the token too. Nothing needs write access since the dashboard became fixed.
- **Each user has their own dashboard** and their own live-update stream.
- **Discovery is open; data is not.** Claude can say hello (`initialize`), list the tools and fetch the chart panel without a token, so it starts without asking anyone to sign in. Every tool call (queries, charts) needs a token, so the sign-in page opens on the first NMS question. See `mcp-server`'s `McpDiscoveryFilter`.
- **Signing out** is `POST /api/auth/logout` (in Claude: *"sign me out of NMS"*, the `logout` tool). The API ends the caller's Keycloak session with their own token, through Keycloak's Account API, so it can't be renewed. It refuses that session's tokens from the next request on, and so does the MCP server, whose `401` makes Claude sign in again. Only that sign-in ends; the same user's dashboard tab stays signed in. The list of signed-out sessions is kept in memory: after a restart, a token issued before the sign-out works again until it expires (15 minutes at most), but it can't be renewed.
- **MCP sign-in** follows the MCP authorization specification. Without a token, `/mcp` answers `401` with `WWW-Authenticate: Bearer resource_metadata=…`. `/.well-known/oauth-protected-resource/mcp` names Keycloak. Claude Code then registers itself with Keycloak, signs the user in with PKCE, and calls `/mcp` with that user's token. The MCP server forwards that same token to the API.
- **The chat panel never holds a token.** Its calls go through Claude's signed-in MCP connection.
- **Audit log:** every tool call is written to the `audit` log with the user and the arguments.

**Changing the login provider:** to use Okta or Entra ID instead, set `OIDC_ISSUER` (and `OIDC_CLIENT_ID`) to an app registered there. The tokens need the `tenants`, `nms_access` and `aud` claims. To work without sign-in, set `AUTH_ENABLED=false`; every caller then sees every tenant.

## Connect Claude

**Claude Desktop:**

```bash
npm run setup:claude            # prints the entry: this machine's java + the bridge's absolute path
npm run setup:claude -- --write # merges it into claude_desktop_config.json (backup kept as .bak)
```

Quit (⌘Q) and reopen Claude Desktop. Keep the backend and the MCP server running; the bridge only relays to the MCP server.

**Signing in happens by itself, when it's needed.** Opening Claude Desktop doesn't ask for anything. On your first NMS question, the bridge opens the NMS sign-in page in your browser. After you sign in, the tab says *"Signed in to NMS — go back to Claude"* and the answer arrives. The sign-in is kept in `~/.nms-dashboard/bridge-token`, readable by you only, and renewed silently, so later sessions don't ask again. To switch users, ask Claude to *"sign me out of NMS"*, or delete that file.

Optionally, `npm run login` signs in ahead of time. `NMS_LOGIN=device` uses the device-code flow instead, for machines without a local browser.

> When Claude Desktop runs against your company's cloud (third-party mode), it reads `~/Library/Application Support/Claude-3p/claude_desktop_config.json`, not the usual file. `setup:claude --write` writes both. If you edit by hand, put the `"nms-dashboard"` entry **inside** the existing `"mcpServers"`, not a second `"mcpServers"` within it; Desktop reports that as *"not valid MCP server configurations"*.

**Claude Code:** the project `.mcp.json` connects straight to `http://localhost:4301/mcp`. Run Claude Code from this folder. On first use, `/mcp` shows the server needs authentication; choose it and sign in in the browser.

## Tenant administration (admin only)

| Request | Does |
|---|---|
| `POST /api/tenants` | Creates a tenant. Body: `name`, `environment` (`Production` · `Staging` · `Development`), `specie` (`Dairy` · `Feedlot` · `Poultry`), optional `color` (`#RRGGBB`). Answers `201`. |
| `PATCH /api/tenants/{id}` | Edits any of those fields. Fields you leave out stay as they are. A change of environment or specie also updates the tenant's farms. |
| `DELETE /api/tenants/{id}` | Deletes a tenant with no farms (`204`). A tenant that still has farms is refused with `409`, so no farm, device or issue is ever deleted along with it. |

- **Who is an admin:** a user whose token has `tenants: *` and `nms_access: write`. In the demo realm, that's only `admin`. Everyone else gets `403`.
- **Errors:**
  - an invalid body is a `400` that lists every problem;
  - a name another tenant already has (in any case) is a `409`;
  - an unknown id is a `404`.
- **Audit:** every change is written to the `audit` log.
- **Access to a new tenant:** a new tenant's id isn't in any demo user's `tenants` claim. Only admins see it until you add the id to a user's `tenants` attribute in Keycloak.

## Farm administration (admin only)

| Request | Does |
|---|---|
| `POST /api/farms` | Creates a farm. Required: `tenantId`, `name`, `address` (the town), `country`, `latitude`, `longitude`, `version` (e.g. `7.6.0.1`). Optional: `farmName`, `contractUser`, `protocol` (`IoT` by default, or `NoIoT`), `connectivityStatus` (`UNKNOWN` by default), `animals` (`0` by default). Answers `201`. |
| `PATCH /api/farms/{id}` | Edits any of those fields. Fields you leave out stay as they are. A new `tenantId` moves the farm, and its issues move with it. A new `version` sets `lastUpgradeAt`. |
| `DELETE /api/farms/{id}` | Deletes a farm with no data (`204`). A farm that still has devices, issues or connectivity history is refused with `409`, and the answer lists how much would be deleted. `?cascade=true` deletes the farm together with all of that. |

- **Specie and environment:** a farm always takes them from its tenant. They can't be set on the farm itself.
- **Unique names:** farm names are unique across the estate, in any case, because the chat filters farms by name. A name that's already taken is a `409`.
- **Errors and audit:** same as for tenants. Only admins may make changes, everyone else gets `403`, and every change is written to the `audit` log.

## The tools

| Tool | Who calls it | Does |
|---|---|---|
| `list_datasets` | model | Datasets, dimensions (with their exact values) and metrics. Claude calls this first. |
| `query_data` | model | Rows as text, for Claude to explore before it charts |
| `show_chart` | model | **Renders the panel.** The spec is the dataset, metric, ≤2 dimensions, filters, time range and/or bucket, an optional previous-period comparison, and an insight sentence. It also returns a PNG of the chart and a localhost link to the interactive version. |
| `run_chart` | panel only | Re-runs a spec when the user drills down. No model turn. |
| `logout` | model | Signs the user out when they ask to sign out or switch user (`POST /api/auth/logout`). The next NMS question opens the sign-in page. |

There are no dashboard tools: charts live in the chat, and the dashboard is a fixed page.

**Claude never writes SQL.** `QueryBuilder` turns a spec into one parameterised statement:
- Every identifier comes from `DatasetCatalog`.
- Every value travels as a `?` parameter, never written into the SQL.
- An unknown field returns an error that lists the valid ones, so Claude corrects itself.

The panel shows the generated SQL behind **Show SQL**.

**The panel and Claude share one view.** Every chart-type switch and drill-down is reported to Claude through `updateModelContext`. So *"why are these offline?"* refers to whatever is on screen.

## Demo script (~4 minutes)

Two screens: Claude Desktop on the left, http://localhost:4300/ on the right (the fixed estate dashboard).

1. *"Which farms have the most offline devices?"* → a ranking chart, with Hoeve De Linde at 44.
2. **Click** Hoeve De Linde's bar → it drills into firmware: everything offline is on **5.16.0**.
3. *"Is that happening anywhere else?"* → offline % by firmware: 5.16.0 at 40% vs ~4%.
4. *"Show me the estate's offline % over the last 30 days."* → an area chart that jumps on 5 Oct.
5. *"Anything else going wrong this week?"* → Claude finds German gateway issues, 91 vs 19, when asked with a week-on-week comparison.
6. Finale: *"Give me a morning overview."* → Claude answers with several charts in the chat. The fixed dashboard on the right shows the same open-alert counts.

Keep a recording of a full run, and http://localhost:4300/host open in a tab, in case something goes wrong live.

## Development

```bash
npm test                        # unit tests: seed stories, query-builder safety; the MCP server's API client and error mapping
npm start & npm run start:mcp & npm run smoke   # end to end over MCP via the bridge, as real users: every tool, tenant isolation, 401 discovery
npm --prefix frontend run build:panel   # rebuild only the chat panel. The backend and the MCP server re-read it per request.
```

## Splitting the work (2 core + 5 supporting)

| Who | Owns |
|---|---|
| **Dev A** | `backend/`: datasets and metrics (`DatasetCatalog`), more tools (`McpTools`), Postgres on the demo machine |
| **Dev B** | `frontend/`: the panel and dashboard UI, new chart types in `shared/render.ts` (map!), presentation mode |
| **S1** | Test 20 questions in Claude and note which ones Claude gets wrong. Improve tool and field descriptions to fix them. |
| **S2** | The data story: adjust `EstateGenerator` and keep `EstateGeneratorTest` and the numbers in this README in step |
| **S3** | Check every number on screen against the database (Show SQL helps) |
| **S4** | Run the full demo daily from hour 18, record the backup video, keep `/host` ready |
| **S5** | Slides, the pitch, timing the demo script |

## More ideas to build on this

- **"What changed this week?"** A `find_changes` tool that compares week on week across every dimension and returns the biggest movers. Claude then finds the stories by itself instead of being led to them.
- **Map widget.** Farms already have latitude and longitude. An ECharts geo chart for *"show offline farms on a map"*.
- **Act on the insight.** For example *"Pause the 5.16.0 rollout and open a ticket"*: an `actions` table and a tool that writes to it, shown as a banner on the dashboard.
- **Watch it for me.** *"Tell me when offline % goes above 8%"*: a threshold rule checked on refresh.
- **Into the real product.** Serve the MCP server alongside nms-core-ui, so the chat answers next to the real dashboard. The frontend here already uses the same Angular version and Tailwind tokens.

## Troubleshooting

| Symptom | Fix |
|---|---|
| A tool says "The NMS API is not reachable" | Run `npm start` (the MCP server is up, the backend isn't). |
| Claude can't connect to NMS at all | Run `npm run start:mcp`. |
| Claude says the NMS connector "needs you to sign in" | Claude Desktop is still running an older connector: quit it with ⌘Q and reopen. The sign-in page then opens by itself. Keycloak must be running (`npm run db:up`). |
| "not valid MCP server configurations were skipped: mcpServers" | The entry was nested one level too deep. Run `npm run setup:claude -- --write`, which fixes it. |
| The browser loops back to the sign-in page | Keycloak was recreated, so its sessions are gone. Sign in again. |
| "The panel is not built" | Run `npm --prefix frontend run build`. |
| "There was a problem displaying content from nms-dashboard" | Claude Desktop loads MCP App panels inside a frame from `*.claudemcpcontent.com`. On the company network, Zscaler intercepts that request: `~/Library/Logs/Claude-3p/claude.ai-web.log` shows `Framing 'https://gateway.zscalertwo.net/' violates … frame-src`. So every `show_chart` also returns a **PNG** (drawn by the backend, no frame needed) and a **localhost link** to the interactive version at `/host?spec=…`, which doesn't go through Zscaler. For in-chat panels on the company network, IT needs to allow `*.claudemcpcontent.com`. `CHART_IMAGES=false` turns the PNG off. |
| Claude Desktop logs | In AWS (third-party) mode they're in `~/Library/Logs/Claude-3p/` |
| Dates look old | Run `npm run start:reset`. |
