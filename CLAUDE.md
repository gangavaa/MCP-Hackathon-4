# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Ask questions about the NMS estate in plain English from Claude; an interactive chart (an [MCP App](https://github.com/modelcontextprotocol/ext-apps)) renders inside the chat. The dashboard at http://localhost:4300/ is a **fixed** page — nothing from the chat is added to it. `README.md` is the user-facing reference (demo script, users, admin endpoints, troubleshooting); `docs/architecture.md` has the detailed design (classes, sign-in flows, data model, design decisions).

## Commands

Prereqs: JDK 21+, Maven 3.9+, Node 22.18+, Docker (Keycloak).

```bash
npm install && npm --prefix frontend install
npm run db:up            # Keycloak :8080 + Postgres :5433 (docker compose); cp .env.example .env to use that Postgres
npm run build            # frontend/dist (dashboard + inlined panel), then backend and mcp-server jars
npm start                # backend on :4300 (seeds DB on first run)
npm run start:reset      # drop everything and re-seed (dates are relative to seed day)
npm run start:mcp        # MCP server on :4301/mcp — Claude connects here
npm run dev:frontend     # ng serve on :4200, proxies /api and /panel.html to :4300
npm --prefix frontend run build:panel   # rebuild only the chat panel; backend + MCP server re-read it per request

npm test                 # backend then mcp-server unit tests (Maven)
npm run smoke            # end-to-end over MCP via the bridge; needs backend + MCP server running
```

Single test: `mvn -q -f backend/pom.xml test -Dtest=QueryBuilderTest` (or `-Dtest=QueryBuilderTest#methodName`); same with `-f mcp-server/pom.xml`. The frontend has no test suite.

Without `DATABASE_URL`, the backend starts an embedded PostgreSQL in-process (data in `backend/.pgdata/`, ~15 s first start). `AUTH_ENABLED=false` disables sign-in (every caller sees every tenant).

## Architecture

Three processes plus a relay:

```
Claude Desktop ─stdio─► bridge/McpStdioBridge.java ─HTTP─┐
Claude Code ───────────────────────────────HTTP──────────┤
                                                          ▼
              mcp-server/  :4301 /mcp   MCP gateway — no data, no SQL
                    │  HTTP, forwarding the caller's own bearer token
                    ▼
              backend/     :4300 /api   REST API, only process touching Postgres; no MCP code
                           /, /host     serves frontend/dist
```

- **mcp-server** (`com.nms.dashboard.mcp`): tools live in `McpTools`; each is one or two calls through `api/NmsApiClient` using the caller's token, so the backend enforces tenant scope. It adds tool descriptions/schemas, rows-as-text for the model, a PNG per chart (`ChartImageRenderer`, fallback for hosts that can't frame the panel), and serves the panel resource (`McpPanel`, reads `frontend/dist/panel/index.html`). API errors become tool errors in the API's words. `McpDiscoveryFilter` allows `initialize`/tool listing/panel fetch without a token; every tool call needs one (401 + `WWW-Authenticate` → OAuth via Keycloak). Tools: `list_datasets`, `query_data`, `show_chart`, `run_chart` (panel-only drill-down, no model turn), `logout`. API DTOs in `mcp/api/` mirror the backend's domain records — keep them in step.
- **backend** (`com.nms.dashboard`): one package per domain (`tenant`, `farm`, `estate`, `analytics`, `platform`, `seed`, `shared`), each layered `domain/` (no Spring, no SQL; entities, repository interfaces, events) → `application/` (use cases: access check, load, apply rules, save, publish events, audit) → `infrastructure/` (JDBC repos) → `web/` (controllers). `estate/` is the read model (query side) for `tenant/` and `farm/`. Flyway migrations in `backend/src/main/resources/db/migration`.
- **Errors carry no HTTP status**: domain throws `ValidationException` / `ConflictException` / `NotFoundException` / `ForbiddenException` (`shared/error`); only `shared/web/ApiExceptionHandler` maps to 400/409/404/403. Use `shared/domain/Problems` to collect all validation errors at once.
- **Claude never writes SQL.** `analytics/infrastructure/QueryBuilder` turns a chart/query spec into one parameterised statement: identifiers only from `analytics/domain/DatasetCatalog`, values always `?` params, unknown fields error with the list of valid ones. Tenant scope from the token (`tenants` claim → `t.id IN (…)`, via `shared/persistence/TenantFilter`) is added to **every** query, chart and dropdown value list. `QueryBuilderTest` guards this.
- **Auth**: Keycloak realm from `keycloak/nms-realm.json` (regenerate with `keycloak/build-realm.mjs`). Backend and MCP server both verify signature/issuer/expiry/audience (`aud: nms-ask-dashboard`). Claims used: `tenants` (ids or `*`), `nms_access` (`read`/`write`). Admin = `tenants: *` + `write`. Sign-out (`POST /api/auth/logout`) kills the Keycloak session and both processes keep an in-memory `SignedOutSessions` list. The chat panel never holds a token — its calls go through Claude's MCP connection.
- **frontend** (Angular 22 + Tailwind v4 workspace): `projects/dashboard` (fixed estate dashboard `/`, tenant `/tenants/:id`, farm `/farms/:id`, panel test host `/host`), `projects/panel` (the MCP App, inlined into one HTML file by `scripts/inline-panel.mjs`), `shared/` (ECharts renderer `render.ts`, `chart.component.ts`, types, NMS design tokens). The panel reports every chart-type switch / drill-down to Claude via `updateModelContext`, so the model and the panel share one view.

## Seed data is pinned by tests

`seed/EstateGenerator` uses a fixed seed with dates relative to an anchor (midnight UTC on seed day). `EstateGeneratorTest` pins the three demo stories (firmware 5.16.0 offline ~40% / Hoeve De Linde 44 offline; German dairy gateway issues 91 vs 19; 410 low-battery ear tags on Irish feedlots). If you change the generator, keep that test and the numbers in `README.md` in step.
