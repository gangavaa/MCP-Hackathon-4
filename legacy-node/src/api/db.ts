import { fileURLToPath } from 'node:url';

/** The one seam between the app and Postgres: a real server when DATABASE_URL is set, PGlite otherwise. */
export interface Db {
  readonly kind: 'postgres' | 'pglite';
  query<T = Record<string, unknown>>(sql: string, params?: unknown[]): Promise<T[]>;
  /** Several statements, no parameters — for the schema. */
  exec(sql: string): Promise<void>;
  close(): Promise<void>;
}

export const PGLITE_DIR = fileURLToPath(new URL('../../.pglite', import.meta.url));

export async function openDb(url = process.env.DATABASE_URL): Promise<Db> {
  if (url) {
    const { default: pg } = await import('pg');
    const pool = new pg.Pool({ connectionString: url, max: 5 });
    await pool.query('select 1');
    return {
      kind: 'postgres',
      query: async (sql, params) => (await pool.query(sql, params)).rows,
      exec: async (sql) => void (await pool.query(sql)),
      close: () => pool.end(),
    };
  }

  const { PGlite } = await import('@electric-sql/pglite');
  const lite = await PGlite.create(PGLITE_DIR);
  return {
    kind: 'pglite',
    query: async <T>(sql: string, params?: unknown[]) => (await lite.query<T>(sql, params)).rows,
    exec: async (sql) => void (await lite.exec(sql)),
    close: () => lite.close(),
  };
}
