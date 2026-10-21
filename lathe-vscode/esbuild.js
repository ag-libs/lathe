const esbuild = require('esbuild');

const production = process.argv.includes('--production');
// --test bundles the extension's test instead, for npm test. npm test runs this file with node,
// not the esbuild command: on Windows cmd.exe would find this esbuild.js first and run it as a
// Windows Script Host script.
const test = process.argv.includes('--test');

esbuild
  .build({
    entryPoints: [test ? 'test/launch.test.ts' : 'src/extension.ts'],
    bundle: true,
    format: 'cjs',
    platform: 'node',
    // Extension-host Node (VS Code's Electron) for engines.vscode ^1.84.0 — not the build toolchain.
    target: 'node18',
    outfile: test ? 'target/test/launch.test.js' : 'target/main.js',
    external: ['vscode'],
    sourcemap: !production,
    minify: production,
    logLevel: 'info',
  })
  .catch(() => process.exit(1));
