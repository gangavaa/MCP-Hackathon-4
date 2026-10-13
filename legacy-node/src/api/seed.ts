import { readFile } from 'node:fs/promises';
import type { Db } from './db.ts';

/*
 * The estate the demo asks about. Vocabulary and shapes follow nms-core-ui's fake-backend — its tenant ids and
 * names, `TestFarmId<n>` farm ids, the connectivity statuses, the four severities, OPEN/CLOSED, the ten alert
 * definitions in `utils/issues.ts` — but the data is realistic rather than `TestFarmName<n>` in Amsterdam a
 * thousand times over.
 *
 * It is generated from a fixed seed, every timestamp relative to `anchor` (the day the seed ran), so the numbers
 * are the same on every machine and every day: the answers to the demo's questions can be checked in advance.
 *
 * Three stories are planted for the demo to find:
 *  1. FIRMWARE — six days before the anchor, firmware 5.16.0 rolled out to the collars and ear tags of 14 farms.
 *     Those devices drop offline at ~10x the normal rate, and "Hoeve De Linde" (NL) is hit hardest.
 *  2. GATEWAYS — German dairy farms have raised four times as many "Gateway not answering" issues in the last
 *     7 days as in the 7 before.
 *  3. BATTERIES — the ear tags on Irish feedlots are running flat: a low-battery cluster in one country.
 */

export const DEFAULT_SEED = 443929;
const DAYS = 60;
const DAY_MS = 86_400_000;
const ROLLOUT_DAYS_AGO = 6;
const NEW_FIRMWARE = '5.16.0';

// ---------------------------------------------------------------------------------------------- randomness

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

class Rng {
  readonly next: () => number;
  constructor(seed: number) {
    this.next = mulberry32(seed);
  }
  int(min: number, max: number): number {
    return min + Math.floor(this.next() * (max - min + 1));
  }
  chance(p: number): boolean {
    return this.next() < p;
  }
  pick<T>(items: readonly T[]): T {
    return items[Math.floor(this.next() * items.length)];
  }
  weighted<T>(items: readonly (readonly [T, number])[]): T {
    const total = items.reduce((sum, [, w]) => sum + w, 0);
    let r = this.next() * total;
    for (const [item, w] of items) {
      if ((r -= w) < 0) return item;
    }
    return items[items.length - 1][0];
  }
  /** Knuth's method — fine for the small rates used here. */
  poisson(lambda: number): number {
    const limit = Math.exp(-lambda);
    let k = 0;
    let p = 1;
    do {
      k++;
      p *= this.next();
    } while (p > limit);
    return k - 1;
  }
}

// ---------------------------------------------------------------------------------------------- reference data

/* The app-wide tenants of fake-backend `tenants/index.ts`, ids kept. The fifth was "Some tenant..." there. */
const TENANTS = [
  { id: '77c2c8fc-1266-46e5-937f-e1c84fe42fb8', name: 'Dairy Prod', environment: 'Production', specie: 'Dairy', color: '#23885C', farms: 40 },
  { id: '2a703fc9-b51a-464f-829b-6b23aa5b3cc9', name: 'Feedlot Prod', environment: 'Production', specie: 'Feedlot', color: '#004A75', farms: 30 },
  { id: '0576720d-0297-4d4b-bdaf-0e024db11611', name: 'Poultry Prod', environment: 'Production', specie: 'Poultry', color: '#C08A2D', farms: 25 },
  { id: 'e2362248-5c59-44e3-b5f3-d551a879835a', name: 'Dairy Dev', environment: 'Development', specie: 'Dairy', color: '#7048CC', farms: 8 },
  { id: '6558cf86-ad58-4c58-9464-630cdff8a2ae', name: 'Feedlot Staging', environment: 'Staging', specie: 'Feedlot', color: '#459CCE', farms: 7 },
] as const;

type Specie = (typeof TENANTS)[number]['specie'];

interface Country {
  name: string;
  lat: number;
  lng: number;
  towns: string[];
  owners: string[];
  farmNames: (rng: Rng, owner: string) => string;
}

const COUNTRIES: Record<string, Country> = {
  NL: {
    name: 'Netherlands', lat: 52.3, lng: 5.6,
    towns: ['Leeuwarden', 'Drachten', 'Zwolle', 'Barneveld', 'Gouda', 'Assen', 'Meppel'],
    owners: ['van Wezel', 'de Vries', 'Jansen', 'Bakker', 'Visser', 'Smit', 'Mulder', 'de Boer', 'Bos', 'Hendriks'],
    farmNames: (rng) => `${rng.pick(['Hoeve', 'Boerderij', 'Melkveebedrijf'])} ${rng.pick(['Zonnehoek', 'Het Veld', 'De Eik', 'Groenendaal', 'Weidezicht', 'De Hoorn', 'Het Hoogland', 'De Wilgen', 'Klaverweide', 'De Meent'])}`,
  },
  DE: {
    name: 'Germany', lat: 52.9, lng: 9.0,
    towns: ['Kirchgellersen', 'Lüneburg', 'Oldenburg', 'Cloppenburg', 'Vechta', 'Husum', 'Rotenburg'],
    owners: ['Müller', 'Schmidt', 'Fischer', 'Weber', 'Becker', 'Hoffmann', 'Schulz', 'Koch', 'Wagner', 'Meyer'],
    farmNames: (rng) => `${rng.pick(['Gut', 'Hof', 'Milchhof'])} ${rng.pick(['Sonnenhof', 'Lindenhof', 'Birkenhof', 'Eichenhof', 'Wiesengrund', 'Heidekamp', 'Moorwiese', 'Elbblick', 'Buchenhain'])}`,
  },
  FR: {
    name: 'France', lat: 48.2, lng: -1.6,
    towns: ['Rennes', 'Caen', 'Laval', 'Vitré', 'Saint-Lô', 'Fougères'],
    owners: ['Martin', 'Bernard', 'Dubois', 'Moreau', 'Laurent', 'Lefebvre', 'Garnier'],
    farmNames: (rng) => `${rng.pick(['Ferme', 'Domaine', 'GAEC'])} ${rng.pick(['du Moulin', 'des Prés', 'de la Vallée', 'des Chênes', 'du Bocage', 'de la Source', 'des Tilleuls'])}`,
  },
  DK: {
    name: 'Denmark', lat: 56.2, lng: 9.2,
    towns: ['Herning', 'Viborg', 'Esbjerg', 'Holstebro', 'Skive', 'Ribe'],
    owners: ['Nielsen', 'Jensen', 'Hansen', 'Pedersen', 'Andersen', 'Christensen'],
    farmNames: (rng) => rng.pick(['Søndergård', 'Nørregård', 'Østergård', 'Vestergård', 'Lindegård', 'Bakkegård', 'Engholm', 'Kildegård', 'Toftegård']),
  },
  IE: {
    name: 'Ireland', lat: 52.6, lng: -7.9,
    towns: ['Cork', 'Kilkenny', 'Tipperary', 'Mallow', 'Clonmel', 'Athlone', 'Roscommon'],
    owners: ['Murphy', 'Kelly', "O'Brien", 'Walsh', 'Byrne', 'Ryan', "O'Connor", 'Doyle'],
    farmNames: (rng) => `${rng.pick(['Glenview', 'Riverside', 'Ashgrove', 'Oakfield', 'Hillcrest', 'Meadowbrook', 'Ballymore', 'Rathmore', 'Kilbrook'])} ${rng.pick(['Farm', 'Feedlot', 'Farms'])}`,
  },
  US: {
    name: 'United States', lat: 38.5, lng: -101.0,
    towns: ['Greeley, CO', 'Dodge City, KS', 'Amarillo, TX', 'Garden City, KS', 'Hereford, TX', 'Yuma, CO'],
    owners: ['Johnson', 'Miller', 'Anderson', 'Thompson', 'Harris', 'Clark', 'Lewis'],
    farmNames: (rng) => `${rng.pick(['Prairie Creek', 'Cottonwood', 'Red Mesa', 'Sandhill', 'Big Sky', 'Cedar Bluff', 'Arrowhead', 'Lone Pine'])} ${rng.pick(['Feedyard', 'Ranch', 'Cattle Co.'])}`,
  },
};

const COUNTRIES_BY_SPECIE: Record<Specie, readonly (readonly [string, number])[]> = {
  Dairy: [['NL', 4], ['DE', 4], ['DK', 2], ['IE', 2], ['FR', 2]],
  Feedlot: [['US', 4], ['IE', 3], ['DE', 1]],
  Poultry: [['NL', 3], ['FR', 3], ['DE', 2], ['DK', 1]],
};

const DEVICE_TYPES: Record<Specie, readonly (readonly [string, number])[]> = {
  Dairy: [['Collar', 6], ['Ear Tag', 3], ['Gateway', 1]],
  Feedlot: [['Ear Tag', 8], ['Water Sensor', 1], ['Gateway', 1]],
  Poultry: [['Climate Sensor', 5], ['Feeder Scale', 4], ['Gateway', 1]],
};

const HARDWARE: Record<string, string> = {
  Collar: 'SCR Heatime HR', 'Ear Tag': 'eSense Flex', Gateway: 'PS4', 'Water Sensor': 'AquaTrack 2',
  'Climate Sensor': 'ClimaSense C3', 'Feeder Scale': 'WeighLine 500',
};

/* The ten definitions of fake-backend `utils/issues.ts`, given short names that read on a chart axis. */
const DEFINITIONS = [
  { id: 'def-temperature', description: 'Temperature above threshold', severity: 'CRITICAL', category: 'Environment', weight: 4 },
  { id: 'def-battery', description: 'Battery low', severity: 'LOW', category: 'Technical', weight: 8 },
  { id: 'def-gateway', description: 'Gateway not answering', severity: 'HIGH', category: 'Technical', weight: 3 },
  { id: 'def-feed', description: 'Feed level off schedule', severity: 'MEDIUM', category: 'Operations', weight: 6 },
  { id: 'def-water', description: 'Water consumption low', severity: 'MEDIUM', category: 'Operations', weight: 5 },
  { id: 'def-humidity', description: 'Humidity out of range', severity: 'HIGH', category: 'Environment', weight: 4 },
  { id: 'def-door', description: 'Door left open', severity: 'MEDIUM', category: 'Operations', weight: 3 },
  { id: 'def-firmware', description: 'Firmware rollout failed', severity: 'CRITICAL', category: 'Technical', weight: 1 },
  { id: 'def-telemetry', description: 'Telemetry gap', severity: 'LOW', category: 'Technical', weight: 4 },
  { id: 'def-heartbeat', description: 'Heartbeat missed', severity: 'MEDIUM', category: 'Technical', weight: 4 },
] as const;

type DefinitionId = (typeof DEFINITIONS)[number]['id'];

const RESOLUTION_HOURS: Record<string, [number, number]> = {
  CRITICAL: [2, 12], HIGH: [4, 24], MEDIUM: [8, 48], LOW: [12, 96],
};

// ---------------------------------------------------------------------------------------------- generation

interface FarmRow {
  id: string; tenant_id: string; template_id: string; name: string; farm_name: string; specie: string;
  environment: string; connectivity_status: string; version: string; protocol: string; contract_user: string;
  animals_on_farm: number; address: string; country: string; latitude: number; longitude: number;
  registered_at: Date; last_upgrade_at: Date | null;
}
interface DeviceRow {
  id: string; farm_id: string; device_type: string; hardware_type: string; firmware_version: string;
  status: string; battery_pct: number; last_seen_at: Date; registered_at: Date;
}
interface IssueRow {
  id: string; definition_id: string; tenant_id: string; farm_id: string; resource_id: string; severity: string;
  alert_state: string; created_at: Date; resolved_at: Date | null;
}
interface SnapshotRow { farm_id: string; day: string; online: number; offline: number; total: number }

export interface Estate {
  anchor: Date;
  tenants: typeof TENANTS;
  farms: FarmRow[];
  devices: DeviceRow[];
  issues: IssueRow[];
  snapshots: SnapshotRow[];
}

/** Pure: the same seed and anchor always give the same estate. */
export function generateEstate(seed = DEFAULT_SEED, anchor = startOfTodayUtc()): Estate {
  const rng = new Rng(seed);
  // Day 0 is the last full day before the anchor (midnight UTC), so day buckets line up with calendar dates.
  const at = (daysAgo: number, hours = 0) => new Date(anchor.getTime() - (daysAgo + 1) * DAY_MS + hours * 3_600_000);
  const rollout = at(ROLLOUT_DAYS_AGO, 2);

  // Farms ---------------------------------------------------------------------------------------------------
  const farms: (FarmRow & { countryCode: string })[] = [];
  const usedNames = new Set<string>();
  for (const tenant of TENANTS) {
    for (let i = 0; i < tenant.farms; i++) {
      const index = farms.length;
      const story = index === 0; // Hoeve De Linde: the first Dairy Prod farm
      const code = story ? 'NL' : rng.weighted(COUNTRIES_BY_SPECIE[tenant.specie]);
      const country = COUNTRIES[code];
      const owner = story ? 'van Wezel' : rng.pick(country.owners);
      let name = story ? 'Hoeve De Linde' : country.farmNames(rng, owner);
      for (let n = 2; usedNames.has(name); n++) name = `${name.replace(/ \d+$/, '')} ${n}`;
      usedNames.add(name);

      farms.push({
        id: `TestFarmId${index}`,
        countryCode: code,
        tenant_id: tenant.id,
        template_id: 'ed3afa07-8c5d-4a1b-925c-9db0228f2caa',
        name,
        farm_name: owner,
        specie: tenant.specie,
        environment: tenant.environment,
        connectivity_status: 'ONLINE', // derived from its devices below
        version: rng.weighted([['7.5.6.3', 5], ['7.6.0.1', 3], ['7.4.2.0', 1]] as const),
        protocol: rng.chance(0.8) ? 'IoT' : 'NoIoT',
        contract_user: `${rng.pick(['Bryce', 'Anna', 'Lars', 'Sophie', 'Tom', 'Eva', 'Liam'])} ${owner}`,
        animals_on_farm: tenant.specie === 'Poultry' ? rng.int(8, 60) * 1000 : tenant.specie === 'Feedlot' ? rng.int(400, 6000) : rng.int(80, 650),
        address: story ? 'Drachten' : rng.pick(country.towns),
        country: country.name,
        latitude: round(country.lat + (rng.next() - 0.5) * 2.2, 4),
        longitude: round(country.lng + (rng.next() - 0.5) * 3.0, 4),
        registered_at: at(rng.int(200, 2400)),
        last_upgrade_at: null,
      });
    }
  }

  // Story 1: the 5.16.0 rollout wave — Hoeve De Linde plus 13 more production dairy and poultry farms.
  const rolloutFarms = new Set<string>(['TestFarmId0']);
  const candidates = farms.filter((f) => f.environment === 'Production' && f.specie !== 'Feedlot' && f.id !== 'TestFarmId0');
  while (rolloutFarms.size < 14) rolloutFarms.add(rng.pick(candidates).id);
  for (const farm of farms) if (rolloutFarms.has(farm.id)) farm.last_upgrade_at = rollout;

  const isGatewayStory = (f: { countryCode: string; specie: string; environment: string }) =>
    f.countryCode === 'DE' && f.specie === 'Dairy' && f.environment === 'Production';
  const isBatteryStory = (f: { countryCode: string; specie: string }) => f.countryCode === 'IE' && f.specie === 'Feedlot';

  // Devices -------------------------------------------------------------------------------------------------
  const devices: DeviceRow[] = [];
  const devicesByFarm = new Map<string, DeviceRow[]>();
  for (const farm of farms) {
    const specie = farm.specie as Specie;
    const count = farm.id === 'TestFarmId0' ? 72 : rng.int(18, 80);
    const list: DeviceRow[] = [];
    for (let i = 0; i < count; i++) {
      const type = i === 0 ? 'Gateway' : rng.weighted(DEVICE_TYPES[specie].filter(([t]) => t !== 'Gateway'));
      const upgraded = rolloutFarms.has(farm.id) && (type === 'Collar' || type === 'Ear Tag' || type === 'Climate Sensor');
      const firmware = upgraded ? NEW_FIRMWARE : rng.chance(0.3) ? '5.14.2' : '5.15.0';

      const flatTags = isBatteryStory(farm) && type === 'Ear Tag' && rng.chance(0.55);
      const battery = flatTags ? rng.int(3, 18) : rng.int(35, 100);

      const offlineP = upgraded ? (farm.id === 'TestFarmId0' ? 0.62 : 0.38) : isGatewayStory(farm) ? 0.09 : 0.035;
      const offline = rng.chance(offlineP);
      const status = offline ? 'OFFLINE'
        : battery < 20 ? 'LOW_BATTERY'
        : type === 'Collar' && rng.chance(0.03) ? 'NOT_ON_COW'
        : 'ONLINE';
      const lastSeen = offline
        ? upgraded ? at(rng.int(0, ROLLOUT_DAYS_AGO - 1), rng.int(1, 20)) : at(rng.int(1, 9), rng.int(0, 23))
        : at(0, 24 - rng.int(1, 50) / 60);

      const device: DeviceRow = {
        id: `${farm.id.replace('TestFarmId', 'DEV-')}-${String(i + 1).padStart(3, '0')}`,
        farm_id: farm.id,
        device_type: type,
        hardware_type: HARDWARE[type],
        firmware_version: firmware,
        status,
        battery_pct: battery,
        last_seen_at: lastSeen,
        registered_at: new Date(Math.max(farm.registered_at.getTime(), at(rng.int(20, 900)).getTime())),
      };
      list.push(device);
      devices.push(device);
    }
    devicesByFarm.set(farm.id, list);

    const offlineRatio = list.filter((d) => d.status === 'OFFLINE').length / list.length;
    farm.connectivity_status = offlineRatio >= 0.5 ? 'OFFLINE' : offlineRatio >= 0.2 ? 'PARTIALLY_ONLINE' : offlineRatio >= 0.08 ? 'WARNING' : 'ONLINE';
    if (farm.environment !== 'Production' && rng.chance(0.15)) farm.connectivity_status = 'UNKNOWN';
  }

  // Connectivity snapshots: 60 days per farm, the last one agreeing with the devices' current status ---------
  const snapshots: SnapshotRow[] = [];
  for (const farm of farms) {
    const list = devicesByFarm.get(farm.id)!;
    const total = list.length;
    const offlineNow = list.filter((d) => d.status === 'OFFLINE').length;
    const baseline = Math.max(0, Math.round(total * 0.035));
    for (let daysAgo = DAYS - 1; daysAgo >= 0; daysAgo--) {
      let offline: number;
      if (daysAgo === 0) offline = offlineNow;
      else if (rolloutFarms.has(farm.id) && daysAgo <= ROLLOUT_DAYS_AGO) {
        const ramp = Math.min(1, (ROLLOUT_DAYS_AGO - daysAgo + 1) / 3);
        offline = Math.round(baseline + Math.max(0, offlineNow - baseline) * ramp * (0.85 + rng.next() * 0.3));
      } else if (isGatewayStory(farm) && daysAgo <= 7) {
        offline = Math.round(total * (0.06 + rng.next() * 0.06));
      } else {
        offline = Math.max(0, baseline + rng.int(-1, 2));
      }
      offline = Math.min(total, Math.max(0, offline));
      snapshots.push({ farm_id: farm.id, day: isoDay(at(daysAgo)), online: total - offline, offline, total });
    }
  }

  // Issues ----------------------------------------------------------------------------------------------------
  const issues: IssueRow[] = [];
  const definition = (id: DefinitionId) => DEFINITIONS.find((d) => d.id === id)!;
  const weights = DEFINITIONS.filter((d) => d.id !== 'def-firmware').map((d) => [d, d.weight] as const);

  const raise = (farm: FarmRow, defId: DefinitionId, daysAgo: number, resource?: DeviceRow) => {
    const def = definition(defId);
    const list = devicesByFarm.get(farm.id)!;
    const device = resource ?? rng.pick(list);
    const created = at(daysAgo, rng.int(0, 23) + rng.next());
    const ageDays = (anchor.getTime() - created.getTime()) / DAY_MS;
    const [minH, maxH] = RESOLUTION_HOURS[def.severity];
    const resolves = rng.chance(ageDays > 3 ? 0.92 : ageDays > 1 ? 0.6 : 0.3);
    let resolved: Date | null = resolves ? new Date(created.getTime() + rng.int(minH, maxH) * 3_600_000) : null;
    if (resolved && resolved > anchor) resolved = null;
    issues.push({
      id: `ISS-${String(issues.length + 1).padStart(6, '0')}`,
      definition_id: def.id,
      tenant_id: farm.tenant_id,
      farm_id: farm.id,
      resource_id: device.id,
      severity: def.severity,
      alert_state: resolved ? 'CLOSED' : 'OPEN',
      created_at: created,
      resolved_at: resolved,
    });
  };

  for (const farm of farms) {
    const list = devicesByFarm.get(farm.id)!;
    const upgraded = list.filter((d) => d.firmware_version === NEW_FIRMWARE);
    const lambda = 0.12 + list.length / 500;
    for (let daysAgo = DAYS - 1; daysAgo >= 0; daysAgo--) {
      for (let n = rng.poisson(lambda); n > 0; n--) raise(farm, rng.weighted(weights).id, daysAgo);

      if (rolloutFarms.has(farm.id) && daysAgo <= ROLLOUT_DAYS_AGO) {
        const hit = farm.id === 'TestFarmId0' ? 1.8 : 1;
        for (let n = rng.poisson(0.9 * hit); n > 0; n--) raise(farm, 'def-firmware', daysAgo, rng.pick(upgraded));
        for (let n = rng.poisson(2.2 * hit); n > 0; n--) raise(farm, 'def-heartbeat', daysAgo, rng.pick(upgraded));
        for (let n = rng.poisson(1.4 * hit); n > 0; n--) raise(farm, 'def-telemetry', daysAgo, rng.pick(upgraded));
      }
      if (isGatewayStory(farm)) {
        const rate = daysAgo <= 6 ? 1.1 : daysAgo <= 13 ? 0.27 : 0.2;
        for (let n = rng.poisson(rate); n > 0; n--) raise(farm, 'def-gateway', daysAgo, list[0]);
      }
      if (isBatteryStory(farm)) {
        const rate = daysAgo <= 14 ? 1.6 : 0.5;
        const flat = list.filter((d) => d.battery_pct < 20);
        for (let n = rng.poisson(rate); n > 0 && flat.length > 0; n--) raise(farm, 'def-battery', daysAgo, rng.pick(flat));
      }
    }
  }

  return {
    anchor,
    tenants: TENANTS,
    farms: farms.map(({ countryCode: _countryCode, ...farm }) => farm),
    devices,
    issues,
    snapshots,
  };
}

// ---------------------------------------------------------------------------------------------- loading

export async function seedDatabase(db: Db, seed = DEFAULT_SEED): Promise<Estate> {
  const schema = await readFile(new URL('../../db/schema.sql', import.meta.url), 'utf8');
  await db.exec(schema);

  const estate = generateEstate(seed);
  await db.query('INSERT INTO meta (anchor, seed) VALUES ($1, $2)', [estate.anchor, seed]);
  await insert(db, 'tenants', estate.tenants.map(({ farms: _farms, ...t }) => t));
  await insert(db, 'farms', estate.farms);
  await insert(db, 'devices', estate.devices);
  await insert(db, 'alert_definitions', DEFINITIONS.map(({ weight: _weight, ...d }) => d));
  await insert(db, 'issues', estate.issues);
  await insert(db, 'connectivity_snapshots', estate.snapshots);
  return estate;
}

async function insert<T extends object>(db: Db, table: string, rows: readonly T[], batch = 400): Promise<void> {
  if (rows.length === 0) return;
  const columns = Object.keys(rows[0]);
  for (let start = 0; start < rows.length; start += batch) {
    const chunk = rows.slice(start, start + batch);
    const params: unknown[] = [];
    const values = chunk.map((row) => {
      const placeholders = columns.map((column) => {
        params.push((row as Record<string, unknown>)[column]);
        return `$${params.length}`;
      });
      return `(${placeholders.join(', ')})`;
    });
    await db.query(`INSERT INTO ${table} (${columns.join(', ')}) VALUES ${values.join(', ')}`, params);
  }
}

// ---------------------------------------------------------------------------------------------- helpers

function startOfTodayUtc(): Date {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
}

function isoDay(date: Date): string {
  return date.toISOString().slice(0, 10);
}

function round(value: number, digits: number): number {
  const f = 10 ** digits;
  return Math.round(value * f) / f;
}
