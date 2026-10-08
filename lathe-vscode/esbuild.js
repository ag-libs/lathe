const esbuild = require('esbuild');

const production = process.argv.includes('--production');

esbuild
  .build({
    entryPoints: ['src/extension.ts'],
    bundle: true,
    format: 'cjs',
    platform: 'node',
    // Extension-host Node (VS Code's Electron) for engines.vscode ^1.84.0 — not the build toolchain.
    target: 'node18',
    outfile: 'target/main.js',
    external: ['vscode'],
    sourcemap: !production,
    minify: production,
    logLevel: 'info',
  })
  .catch(() => process.exit(1));
