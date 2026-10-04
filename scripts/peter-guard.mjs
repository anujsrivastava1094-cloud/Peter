import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

function fail(message) {
  console.error("\n[PETER GUARD] FAIL:", message);
  process.exit(1);
}

const html = readFileSync("index.html", "utf8");
const scripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(m => m[1]).filter(Boolean);

if (!scripts.length) fail("No inline JavaScript blocks found in index.html.");

for (let i = 0; i < scripts.length; i++) {
  try {
    new Function(scripts[i]);
  } catch (error) {
    fail(`index.html script #${i + 1} has a syntax error: ${error.message}`);
  }
}

try {
  execFileSync(process.execPath, ["--check", "worker.js"], { stdio: "pipe" });
} catch (error) {
  fail(`worker.js syntax check failed:\n${error.stdout?.toString() || ""}${error.stderr?.toString() || ""}`);
}

const workerUrl = process.env.PETER_WORKER_URL || "https://peter.anujsrivastava1094.workers.dev";

async function get(path) {
  const response = await fetch(workerUrl + path);
  const text = await response.text();
  let data;
  try { data = JSON.parse(text); } catch { fail(`Non-JSON response from ${path}: HTTP ${response.status}`); }
  if (!response.ok) fail(`${path} returned HTTP ${response.status}: ${text.slice(0, 500)}`);
  return data;
}

async function post(path, body) {
  const response = await fetch(workerUrl + path, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body)
  });
  const text = await response.text();
  let data;
  try { data = JSON.parse(text); } catch { fail(`Non-JSON response from ${path}: HTTP ${response.status}`); }
  if (!response.ok) fail(`${path} returned HTTP ${response.status}: ${text.slice(0, 500)}`);
  return data;
}

const health = await get("/api/health");
for (const key of ["aiBinding", "memoryBinding", "databaseBinding", "queueBinding"]) {
  if (health[key] !== true) fail(`Health check: ${key} is not available.`);
}

const command = await post("/api/command", { command: "start focus 25" });
if (command.intent !== "action" || command.action !== "start_focus" || command.payload?.minutes !== 25) {
  fail("Command router regression: 'start focus 25' was not classified correctly.");
}

const state = await post("/api/actions", {
  sessionId: "guard-" + Date.now(),
  action: "get_state"
});
if (!state.ok || state.action !== "get_state") {
  fail("Action engine regression: get_state failed.");
}

console.log("[PETER GUARD] PASS: JavaScript syntax, Worker syntax, health, command routing, and action engine checks passed.");
