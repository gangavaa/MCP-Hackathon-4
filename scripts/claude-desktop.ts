import { copyFile, readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

/*
 * Prints the Claude Desktop entry for this MCP server: the stdio bridge (bridge/McpStdioBridge.java), relaying to the
 * running backend. Absolute paths, because Claude Desktop starts servers without your shell's PATH. With --write it
 * merges the entry into claude_desktop_config.json, after copying the original to claude_desktop_config.json.bak.
 */

/* Claude Desktop keeps a second profile, Claude-3p, when it runs against a company's own cloud (Bedrock, Vertex) —
   and in that mode it reads only that profile's config. Write whichever exist. */
const SUPPORT = join(homedir(), 'Library', 'Application Support');
const CONFIGS = ['Claude-3p', 'Claude'].map((dir) => join(SUPPORT, dir, 'claude_desktop_config.json')).filter((f) => existsSync(f));
const CONFIG = CONFIGS[0] ?? join(SUPPORT, 'Claude', 'claude_desktop_config.json');
const javaHome = [process.env.JAVA_HOME, '/opt/homebrew/opt/openjdk@21', '/opt/homebrew/opt/openjdk'].find((d) => d && existsSync(join(d, 'bin', 'java')));
if (!javaHome) throw new Error('No JDK found — set JAVA_HOME to a JDK 21+.');

const entry = {
  command: join(javaHome, 'bin', 'java'),
  args: [fileURLToPath(new URL('../bridge/McpStdioBridge.java', import.meta.url))],
  env: { NMS_MCP_URL: 'http://localhost:4300/mcp' },
};

console.log(JSON.stringify({ mcpServers: { 'nms-ask-dashboard': entry } }, null, 2));

if (process.argv.includes('--write')) {
  for (const file of CONFIGS.length ? CONFIGS : [CONFIG]) {
    const raw = await readFile(file, 'utf8').catch(() => '{}');
    const config = JSON.parse(raw);
    if (raw !== '{}') await copyFile(file, `${file}.bak`);
    const servers = { ...config.mcpServers };
    delete servers.mcpServers; // a block pasted one level too deep — Desktop skips it as an invalid server
    config.mcpServers = { ...servers, 'nms-ask-dashboard': entry };
    await writeFile(file, `${JSON.stringify(config, null, 2)}\n`);
    console.log(`Wrote ${file} (backup: ${file}.bak)`);
  }
  console.log('Quit Claude Desktop with ⌘Q and reopen it to load the connector.');
} else {
  console.log(`\nAdd the "nms-ask-dashboard" entry inside the existing "mcpServers" of ${CONFIG}, or rerun with --write to merge it in.`);
}
