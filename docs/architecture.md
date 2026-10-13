# NMS Ask the Dashboard: architecture

This document has two parts:
- **High-level design (HLD):** the system's parts and how they connect, including how each kind of client signs in.
- **Low-level design (LLD):** the backend's classes, the request path through them, the data model and the frontend components.

The diagrams are Mermaid, which renders on Azure DevOps, GitHub and in Mermaid-enabled editors. PNG exports for slides are in [`diagrams/`](diagrams/).

---

## 1. High-level design

### 1.1 System context

```mermaid
flowchart LR
  subgraph People
    U["Farm / NMS user<br/>(browser)"]
  end

  subgraph Claude["Claude (AI assistant)"]
    CD["Claude Desktop"]
    CC["Claude Code"]
    PANEL["Chat panel<br/>(MCP App, sandboxed iframe)"]
  end

  BRIDGE["Stdio bridge<br/>bridge/McpStdioBridge.java<br/>device-flow sign-in"]

  subgraph Backend["Spring Boot backend :4300 (Java 21)"]
    SEC["Spring Security<br/>JWT resource server"]
    REST["REST API<br/>/api/*  +  SSE /api/events"]
    MCP["MCP server<br/>/mcp (Streamable HTTP)"]
    CORE["Query layer · Widgets ·<br/>Seed · PNG charts"]
    WEB["Angular build<br/>/ and /host"]
  end

  KC["Keycloak :8080<br/>realm nms<br/>(OIDC / OAuth 2.1)"]
  PG[("PostgreSQL :5433<br/>or embedded")]

  U -- "dashboard, sign-in (PKCE)" --> WEB
  U -- "Bearer token" --> REST
  U -. "signs in" .-> KC
  CD -- "stdio (JSON-RPC)" --> BRIDGE
  BRIDGE -- "HTTPS + Bearer" --> MCP
  CC -- "HTTPS + Bearer<br/>(MCP OAuth)" --> MCP
  CD -. "renders" .-> PANEL
  PANEL -- "tool calls via host" --> CD
  BRIDGE -. "device flow" .-> KC
  CC -. "dynamic registration + PKCE" .-> KC
  SEC -- "verifies signature (JWKS)" --> KC
  REST --> SEC
  MCP --> SEC
  SEC --> CORE
  CORE --> PG
```

**Responsibilities:**

| Part | Owns | Does not |
|---|---|---|
| **Keycloak** | Users, passwords, sign-in pages, consent, token issuing. Claims: `tenants`, `nms_access`, `aud`. | Hold any NMS data |
| **Spring Security** | Verifying every `/api` and `/mcp` token: signature, issuer, expiry, audience | Decide *what* data a user sees. That's the query layer, from the token's claims. |
| **REST API** | The dashboard's contract: queries, charts, widgets, live events | Trust tenant or user values from request bodies |
| **MCP server** | The 9 tools Claude can call, plus the panel resource | Run SQL directly; it calls the same services as REST |
| **Query layer** | Turning a question into one parameterised SQL statement, always restricted to the caller's tenants | Accept SQL from Claude |
| **Bridge** | Relaying stdio to `/mcp` for Claude Desktop; signing in with the device flow | Store passwords. It keeps only a refresh token, readable by the user only. |
| **Chat panel** | Drawing charts in the chat; drill-down; pin | Hold a token. Its calls ride on Claude's signed-in connection. |

### 1.2 Trust boundaries

```mermaid
flowchart TB
  subgraph Untrusted["Untrusted input"]
    M["Claude's tool arguments"]
    B["Browser request bodies"]
    D["Text from the database shown to Claude<br/>(farm names, …)"]
  end
  subgraph Trusted["Trusted, server-side"]
    T["Verified JWT claims<br/>sub · tenants · nms_access · aud"]
    C["DatasetCatalog allowlist<br/>(identifiers)"]
  end
  Q["QueryBuilder"]
  M --> Q
  B --> Q
  T -->|"t.id IN (…) — always added"| Q
  C -->|"only these names in SQL"| Q
  Q -->|"values as ? parameters"| SQL[("Postgres")]
  D -. "treated as data, never as instructions;<br/>destructive tools marked destructiveHint" .-> M
```

### 1.3 Sign-in flows

**(a) Dashboard in the browser: authorization code + PKCE**

```mermaid
sequenceDiagram
  autonumber
  actor U as User
  participant A as Angular app
  participant K as Keycloak
  participant S as Spring backend
  U->>A: open http://localhost:4300/
  A->>S: GET /api/auth/config (public)
  S-->>A: issuer, clientId nms-dashboard
  A->>K: redirect /auth?code_challenge=S256(verifier)
  U->>K: username + password
  K-->>A: redirect /?code=…&state=…
  A->>K: POST /token (code + verifier)
  K-->>A: access token (15 min) + refresh token
  A->>S: GET /api/widgets (Bearer)
  S->>S: verify JWT · user = sub, tenants, access
  S-->>A: this user's widgets, their tenants' data only
  A->>S: fetch /api/events (Bearer, SSE)
  S-->>A: widget-added … (this user only)
```

**(b) Claude Code: MCP authorization (OAuth 2.1, RFC 9728 + RFC 7591)**

```mermaid
sequenceDiagram
  autonumber
  participant C as Claude Code
  participant S as /mcp (Spring)
  participant K as Keycloak
  actor U as User (browser)
  C->>S: POST /mcp (no token)
  S-->>C: 401 WWW-Authenticate: Bearer resource_metadata=".../.well-known/oauth-protected-resource/mcp"
  C->>S: GET /.well-known/oauth-protected-resource/mcp
  S-->>C: resource /mcp, authorization_servers [Keycloak realm nms]
  C->>K: GET /.well-known/oauth-authorization-server/realms/nms
  C->>K: POST /clients-registrations (redirect http://localhost:PORT/callback)
  K-->>C: client_id (public, no secret)
  C->>U: open browser: /auth + PKCE
  U->>K: sign in, approve consent ("Read your NMS estate data…")
  K-->>C: code → token (aud nms-ask-dashboard, tenants, nms_access)
  C->>S: POST /mcp tools/call (Bearer)
  S-->>C: result restricted to the user's tenants
```

**(c) Claude Desktop through the stdio bridge: device authorization grant (RFC 8628)**

```mermaid
sequenceDiagram
  autonumber
  participant D as Claude Desktop
  participant B as Bridge (java …Bridge.java)
  participant S as /mcp
  participant K as Keycloak
  actor U as User (browser)
  D->>B: stdio JSON-RPC
  B->>S: POST /mcp (stored token, or none)
  S-->>B: 401 + resource_metadata
  B->>S: GET protected-resource metadata → Keycloak issuer
  B->>K: POST /auth/device (client nms-bridge, offline_access)
  K-->>B: verification_uri_complete, device_code
  B->>U: open browser on the sign-in page
  U->>K: sign in + approve
  B->>K: poll /token (device_code)
  K-->>B: access + refresh token
  B->>B: save refresh token ~/.nms-ask-dashboard/bridge-token (0600)
  B->>S: retry with Bearer
  S-->>B: result → stdout → Claude Desktop
  Note over B,K: Later sessions refresh silently. npm run login signs in ahead of time.
```

### 1.4 Deployment (local / demo)

```mermaid
flowchart LR
  subgraph Laptop
    subgraph Docker
      KC["keycloak:26.8.0 :8080<br/>imports keycloak/nms-realm.json"]
      PG[("postgres:17 :5433")]
    end
    J["java -jar ask-dashboard.jar :4300<br/>(serves frontend/dist)"]
    BR["bridge (per Claude Desktop session)"]
    CD["Claude Desktop"]
    BROWSER["Browser"]
  end
  CD --> BR --> J
  BROWSER --> J
  J --> PG
  J -->|JWKS| KC
  BROWSER --> KC
  BR --> KC
```

**For production:**
- TLS in front of everything.
- Keycloak replaced by the company's Okta or Entra ID; only the issuer URL changes.
- Managed Postgres, with a read-only role for queries.
- No bridge: Claude connects to `https://…/mcp` as a remote connector.
- The `audit` logger shipped to the SIEM.

---

## 2. Low-level design

### 2.1 Backend classes

```mermaid
classDiagram
  direction LR

  namespace security {
    class SecurityConfig {
      +securityFilterChain(HttpSecurity)
      +jwtDecoder() JwtDecoder
      ~entryPoint() 401 + resource_metadata
    }
    class ProtectedResourceController {
      GET /.well-known/oauth-protected-resource/mcp
      GET /api/auth/config
    }
    class CurrentUser {
      +get() NmsUser
    }
    class NmsUser {
      <<record>>
      subject
      username
      name
      tenants: TenantScope
      canWrite
      +fromJwt(Jwt)$
      +owner()
      +requireWrite()
    }
    class TenantScope {
      <<record>>
      all
      ids
      +key()
    }
  }

  namespace api {
    class ApiController {
      /api/me  /api/datasets  /api/query  /api/chart
      /api/widgets[/reset|/id]  /api/events  /panel.html
    }
  }

  namespace mcp {
    class McpConfig {
      mcpTransport(contextExtractor → USER_KEY)
      mcpServer(tools, panel resource)
    }
    class McpTools {
      9 tools
      audit log
      -tool(name, schema, meta, handler(user, args))
    }
    class ChartImageRenderer {
      +render(ChartData) PNG
    }
  }

  namespace query {
    class QueryService {
      +run(req, user)
      +chart(spec, user)
      +datasets(user)
    }
    class QueryBuilder {
      +build(req, anchor, TenantScope)$ Built
      ~tenantClause(scope, params)$
    }
    class DatasetCatalog {
      DATASETS: farms, devices, issues, connectivity
    }
  }

  namespace dashboard {
    class WidgetService {
      +list(user)
      +add(user, spec, cols, rows)
      +remove(user, id)
      +restoreDefaults(user)
    }
    class DashboardEvents {
      +subscribe(owner) SseEmitter
      +publish(owner, event)
    }
    class DefaultDashboard {
      widgets()$ 11 placed specs
    }
  }

  namespace seed {
    class SeedService {
      +run(args)
      +reseed(seed, anchor)
    }
    class EstateGenerator {
      +generate(seed, anchor)$ Estate
    }
  }

  SecurityConfig ..> ProtectedResourceController : 401 points to
  CurrentUser ..> NmsUser : builds from Jwt
  NmsUser *-- TenantScope
  ApiController --> CurrentUser
  ApiController --> QueryService
  ApiController --> WidgetService
  ApiController --> DashboardEvents
  McpConfig --> CurrentUser : contextExtractor
  McpConfig --> McpTools
  McpTools --> QueryService
  McpTools --> WidgetService
  McpTools --> ChartImageRenderer
  QueryService --> QueryBuilder
  QueryBuilder --> DatasetCatalog
  WidgetService --> QueryService : validates spec as user
  WidgetService --> DashboardEvents
  WidgetService --> DefaultDashboard
  SeedService --> EstateGenerator
```

### 2.2 One MCP call through the layers: `show_chart`

```mermaid
sequenceDiagram
  autonumber
  participant C as Claude (via bridge or Claude Code)
  participant F as Spring Security filter
  participant T as HttpServletStatelessServerTransport
  participant X as contextExtractor
  participant H as McpTools.tool() wrapper
  participant Q as QueryService
  participant B as QueryBuilder
  participant P as Postgres
  participant R as ChartImageRenderer

  C->>F: POST /mcp tools/call show_chart (Bearer JWT)
  F->>F: JwtDecoder: signature (JWKS), iss, exp, aud=nms-ask-dashboard
  F->>T: authenticated request
  T->>X: extract(request)
  X-->>T: McpTransportContext{nms.user = NmsUser.fromJwt}
  T->>H: callHandler(context, request)
  H->>H: user from context (never from args) · audit.info(user, tool, args)
  H->>Q: chart(spec, user)
  Q->>B: build(query, anchor, user.tenants)
  B-->>Q: SELECT … WHERE t.id IN (?) AND … (identifiers from DatasetCatalog, values as ?)
  Q->>P: execute (+ previous period if compare)
  P-->>Q: rows
  Q-->>H: ChartData
  H->>R: render PNG (fallback for hosts that can't show the panel)
  H-->>C: content[text, image, link] + structuredContent (panel payload, drill map)
```

### 2.3 Authorization rules

| Action | REST | MCP tool | Needs |
|---|---|---|---|
| See data, charts, dropdown values | `/api/query`, `/api/chart`, `/api/datasets` | `query_data`, `show_chart`, `run_chart`, `list_datasets` | Valid token. Rows limited to `tenants` |
| See own dashboard | `/api/widgets`, `/api/events` | `list_dashboard`, `show_widget` | Valid token. Only the user's own widgets |
| Change dashboard | `POST/DELETE /api/widgets`, `/reset` | `pin_to_dashboard`, `create_dashboard`, `remove_widget` | `nms_access = write` (otherwise 403 / tool error) |
| Discover sign-in | `/.well-known/oauth-protected-resource/mcp`, `/api/auth/config` | n/a | Public |
| Discover the MCP server | n/a | `initialize`, `ping`, `tools/list`, `resources/list`, `resources/read` (the panel HTML) | Public, but only for requests with **no** `Authorization` header (`McpDiscoveryFilter`), so Claude starts without a sign-in. Any `tools/call` needs a token. |
| App shell, panel HTML | `/`, `/host`, `/panel.html` | n/a | Public (no data) |

**Token claims** (Keycloak client scope `nms`, a default for every client, including self-registered ones):

| Claim | Example | Used for |
|---|---|---|
| `aud` | `nms-ask-dashboard` | Rejects tokens issued for other apps |
| `sub` | `8f209b63-…` | Dashboard owner |
| `tenants` | `["77c2c8fc-…"]` or `["*"]` | `TenantScope`, which becomes `t.id IN (…)` |
| `nms_access` | `write` / `read` | `canWrite` |
| `preferred_username`, `name` | `dairyprod`, `Dairy Prod User` | Audit log, top bar |

### 2.4 Data model

```mermaid
erDiagram
  TENANTS ||--o{ FARMS : has
  FARMS ||--o{ DEVICES : has
  FARMS ||--o{ ISSUES : raises
  DEVICES ||--o{ ISSUES : "resource of"
  ALERT_DEFINITIONS ||--o{ ISSUES : defines
  FARMS ||--o{ CONNECTIVITY_SNAPSHOTS : "one per day"
  DASHBOARD_OWNERS ||--o{ DASHBOARD_WIDGETS : owns

  TENANTS { text id PK
    text name
    text environment
    text specie }
  FARMS { text id PK
    text tenant_id FK
    text name
    text country
    text connectivity_status
    timestamptz last_upgrade_at }
  DEVICES { text id PK
    text farm_id FK
    text device_type
    text firmware_version
    text status
    int battery_pct }
  ISSUES { text id PK
    text definition_id FK
    text farm_id FK
    text tenant_id FK
    text severity
    text alert_state
    timestamptz created_at
    timestamptz resolved_at }
  ALERT_DEFINITIONS { text id PK
    text description
    text severity
    text category }
  CONNECTIVITY_SNAPSHOTS { text farm_id PK
    date day PK
    int online
    int offline
    int total }
  DASHBOARD_OWNERS { text owner PK "JWT sub"
    timestamptz initialized_at }
  DASHBOARD_WIDGETS { text id PK
    text owner FK
    jsonb spec "ChartSpec: the question, not the data"
    int x
    int y
    int cols
    int rows }
  META { timestamptz anchor
    int seed }
```

Flyway migrations: `V1__estate.sql` (estate and widgets) and `V2__dashboards_per_user.sql` (owner column and `dashboard_owners`).

### 2.5 Frontend

```mermaid
flowchart TB
  subgraph dashboard["projects/dashboard (Angular 22, zoneless, signals)"]
    INIT["provideAppInitializer → AuthService.init()<br/>code+PKCE, refresh, logout"]
    INT["authInterceptor<br/>Bearer on /api/*, 401 → login"]
    API["ApiService"]
    EV["DashboardEventsService<br/>fetch-based SSE with Bearer"]
    TOP["Topbar<br/>user · tenants · Sign out · theme"]
    DP["DashboardPage /"]
    WC["WidgetCard"]
    HP["HostPage /host<br/>AppBridge test host"]
  end
  subgraph panel["projects/panel → one HTML file"]
    APP["App (MCP App)<br/>ontoolinput / ontoolresult<br/>callServerTool run_chart, pin<br/>updateModelContext"]
  end
  subgraph shared["shared/"]
    CH["ChartComponent"]
    RN["render.ts (ECharts, KPI, table)"]
    TY["types.ts"]
    TK["theme.css / tokens.css (NMS tokens)"]
  end
  INIT --> INT --> API
  DP --> API
  DP --> EV
  DP --> WC --> CH
  HP --> API
  HP -. "iframe + PostMessageTransport" .-> APP
  APP --> CH --> RN
  RN --> TY
```

---

## 3. Design decisions

| Decision | Why |
|---|---|
| **The tenant restriction lives in `QueryBuilder`**, and `build()` has no unrestricted variant | One choke point for REST, MCP, the panel and dashboard widgets. It can't be forgotten. |
| **The user reaches tools through the MCP transport context** | Tools can't be talked into acting as another user through their arguments |
| **Stateless MCP transport** | No session to keep or leak; every request carries its own token |
| **MCP discovery open, tool calls protected** | Claude Desktop lists tools at start-up. Requiring a token there would open the sign-in page with the app rather than with the first question. The tool list and panel HTML hold no estate data. |
| **Widgets store the question (`ChartSpec`), not the data** | Always live; re-run in the viewer's tenant scope |
| **Bridge with device flow** for Claude Desktop | Desktop can't run OAuth for stdio servers. The device flow needs no local web server or secret. |
| **Public clients + PKCE**, no client secrets in the browser, bridge or Claude | Nothing secret to leak from a laptop |
| **Keycloak locally; Okta or Entra ID in production** | Standard OIDC. Only `OIDC_ISSUER` and the claim mappers change. |
