import { loadEnv } from './env.ts';
import { openDb } from './db.ts';
import { DEFAULT_SEED, seedDatabase } from './seed.ts';

/** Drops everything — including pinned widgets — and seeds a fresh estate. Stop the API first when on PGlite. */
loadEnv();
const seed = Number(process.argv[2] ?? DEFAULT_SEED);
const db = await openDb();
const estate = await seedDatabase(db, seed);
console.log(
  `Seeded ${db.kind} (seed ${seed}, anchor ${estate.anchor.toISOString().slice(0, 10)}): ` +
    `${estate.tenants.length} tenants, ${estate.farms.length} farms, ${estate.devices.length} devices, ` +
    `${estate.issues.length} issues, ${estate.snapshots.length} connectivity snapshots.`,
);
await db.close();
