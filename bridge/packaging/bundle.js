#!/usr/bin/env node
// Bundles bridge.js and its local modules into one CommonJS file (dist/bridge.js) for a Node single
// executable. Only relative requires are inlined; built-in modules stay as require() calls.
'use strict';
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const entry = path.join(root, 'bridge.js');
const out = path.join(root, 'dist', 'bridge.js');

const mods = new Map(); // absolute file -> { id, code }
function resolve(from, req) {
  const base = path.resolve(path.dirname(from), req);
  for (const f of [base, base + '.js', path.join(base, 'index.js')]) if (fs.existsSync(f) && fs.statSync(f).isFile()) return f;
  throw new Error(`Cannot resolve ${req} from ${from}`);
}
function add(file) {
  if (mods.has(file)) return mods.get(file).id;
  const m = { id: mods.size, code: '' };
  mods.set(file, m);
  let code = fs.readFileSync(file, 'utf8').replace(/^#!.*\n/, '');
  code = code.replace(/require\((['"])(\.{1,2}\/[^'"]+)\1\)/g, (_, q, req) => `__req(${add(resolve(file, req))})`);
  m.code = code;
  return m.id;
}
const entryId = add(entry);

let src = "'use strict';\nconst __defs = {}, __cache = {};\n" +
  'function __req(id) { if (__cache[id]) return __cache[id].exports; const module = __cache[id] = { exports: {} }; ' +
  '__defs[id](module, module.exports); return module.exports; }\n';
for (const [file, m] of mods) {
  src += `// ${path.relative(root, file)}\n__defs[${m.id}] = function (module, exports) {\n${m.code}\n};\n`;
}
src += `__req(${entryId});\n`;
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, src);
console.log(`bundled ${mods.size} files -> ${path.relative(process.cwd(), out)}`);
