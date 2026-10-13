-- The NMS estate, shaped after nms-core-ui's fake-backend: the same tenants, the same farm attributes, the same
-- connectivity and severity vocabularies. What differs is the data — realistic, and seeded with stories a demo can
-- find (see seed/EstateGenerator.java).


-- One row. `anchor` is "now" for every relative time range, so answers stay the same whichever day the demo runs.
CREATE TABLE meta (
  anchor      timestamptz NOT NULL,
  seeded_at   timestamptz NOT NULL DEFAULT now(),
  seed        integer     NOT NULL
);

CREATE TABLE tenants (
  id          text PRIMARY KEY,
  name        text NOT NULL,
  environment text NOT NULL,          -- Production | Staging | Development
  specie      text NOT NULL,          -- Dairy | Feedlot | Poultry
  color       text NOT NULL
);

CREATE TABLE farms (
  id                  text PRIMARY KEY,            -- TestFarmId<n>, as farms/search answers
  tenant_id           text NOT NULL REFERENCES tenants(id),
  template_id         text NOT NULL,
  name                text NOT NULL,
  farm_name           text NOT NULL,               -- the owner's family name
  specie              text NOT NULL,
  environment         text NOT NULL,
  connectivity_status text NOT NULL,               -- ONLINE | OFFLINE | WARNING | UNKNOWN | PARTIALLY_ONLINE
  version             text NOT NULL,               -- farm server image version
  protocol            text NOT NULL,               -- IoT | NoIoT
  contract_user       text NOT NULL,
  animals_on_farm     integer NOT NULL,
  address             text NOT NULL,
  country             text NOT NULL,
  latitude            double precision NOT NULL,
  longitude           double precision NOT NULL,
  registered_at       timestamptz NOT NULL,
  last_upgrade_at     timestamptz
);

CREATE TABLE devices (
  id                text PRIMARY KEY,
  farm_id           text NOT NULL REFERENCES farms(id),
  device_type       text NOT NULL,                 -- Collar | Ear Tag | Gateway | Water Sensor | Climate Sensor | Feeder Scale
  hardware_type     text NOT NULL,
  firmware_version  text NOT NULL,
  status            text NOT NULL,                 -- ONLINE | OFFLINE | LOW_BATTERY | NOT_ON_COW
  battery_pct       integer NOT NULL,
  last_seen_at      timestamptz NOT NULL,
  registered_at     timestamptz NOT NULL
);

CREATE TABLE alert_definitions (
  id          text PRIMARY KEY,
  description text NOT NULL,
  severity    text NOT NULL,                       -- CRITICAL | HIGH | MEDIUM | LOW
  category    text NOT NULL                        -- Environment | Operations | Technical
);

CREATE TABLE issues (
  id             text PRIMARY KEY,
  definition_id  text NOT NULL REFERENCES alert_definitions(id),
  tenant_id      text NOT NULL REFERENCES tenants(id),
  farm_id        text NOT NULL REFERENCES farms(id),
  resource_id    text NOT NULL,                    -- the device the issue was raised on
  severity       text NOT NULL,
  alert_state    text NOT NULL,                    -- OPEN | CLOSED
  created_at     timestamptz NOT NULL,
  resolved_at    timestamptz
);

-- One row per farm per day: the ConnectivitySnapshot the dashboard's connectivity widgets read.
CREATE TABLE connectivity_snapshots (
  farm_id  text NOT NULL REFERENCES farms(id),
  day      date NOT NULL,
  online   integer NOT NULL,
  offline  integer NOT NULL,
  total    integer NOT NULL,
  PRIMARY KEY (farm_id, day)
);

-- The live dashboard. `spec` is a ChartSpec (src/shared/types.ts): the query, not the data, so a pinned widget stays live.
CREATE TABLE dashboard_widgets (
  id          text PRIMARY KEY,
  title       text NOT NULL,
  spec        jsonb NOT NULL,
  x           integer NOT NULL,
  y           integer NOT NULL,
  cols        integer NOT NULL,
  rows        integer NOT NULL,
  source      text NOT NULL DEFAULT 'ai',
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ON devices (farm_id);
CREATE INDEX ON issues (farm_id);
CREATE INDEX ON issues (created_at);
CREATE INDEX ON connectivity_snapshots (day);
