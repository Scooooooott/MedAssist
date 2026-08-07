import { gzipSync } from "node:zlib";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const limits = {
  js: 250 * 1024,
  css: 60 * 1024
};

function collectFiles(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    return statSync(path).isDirectory() ? collectFiles(path) : [path];
  });
}

const files = collectFiles("dist/assets");
const totals = { js: 0, css: 0 };

for (const file of files) {
  if (!file.endsWith(".js") && !file.endsWith(".css")) continue;
  const kind = file.endsWith(".js") ? "js" : "css";
  totals[kind] += gzipSync(readFileSync(file)).length;
}

console.log(`Bundle gzip totals: js=${totals.js} bytes css=${totals.css} bytes`);

for (const [kind, total] of Object.entries(totals)) {
  if (total > limits[kind]) {
    throw new Error(`${kind} bundle exceeds budget: ${total} > ${limits[kind]}`);
  }
}
