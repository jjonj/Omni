// Generate the agent-side catalog from the editor's *actual* built-in stencil packs.
// Run after changes to src/packs.ts: node scripts/export_agent_catalog.cjs
const esbuild = require('esbuild');
const path = require('node:path');
const fs = require('node:fs');
const Module = require('node:module');
(async () => {
  const entry = path.resolve(__dirname, '../src/packs.ts');
  const result = await esbuild.build({entryPoints:[entry], bundle:true, platform:'node',
    format:'cjs', write:false, logLevel:'silent'});
  const compiled = new Module(path.resolve(__dirname, 'packs-compiled.cjs'), module);
  compiled.filename = path.resolve(__dirname, 'packs-compiled.cjs');
  compiled.paths = Module._nodeModulePaths(__dirname);
  compiled._compile(result.outputFiles[0].text, compiled.filename);
  const packs = compiled.exports.builtInPacks;
  if (!Array.isArray(packs) || packs.length < 8) throw Error('Missing editor packs');
  const destination = path.resolve(__dirname, '../../service/agent_packs.json');
  fs.writeFileSync(destination, JSON.stringify(packs, null, 2) + '\n', 'utf8');
  console.log('Exported ' + packs.length + ' editor packs to ' + destination);
})().catch(error => {console.error(error);process.exitCode=1;});
