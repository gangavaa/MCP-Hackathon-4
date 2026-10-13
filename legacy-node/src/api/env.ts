import { fileURLToPath } from 'node:url';

/** Loads the repo's .env whatever directory the process was started from (Claude Desktop starts it from /). */
export function loadEnv(): void {
  try {
    process.loadEnvFile(fileURLToPath(new URL('../../.env', import.meta.url)));
  } catch {
    // no .env — the defaults apply
  }
}
