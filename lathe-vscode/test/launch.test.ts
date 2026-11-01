import { spawn } from 'child_process';
import * as assert from 'node:assert/strict';
import { test } from 'node:test';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { LAUNCHER, findWorkspaceRoot, launcherCommand } from '../src/launch';

// Starts a stub launcher the way the language client does (cp.spawn with the command's options),
// from a directory whose name has a space, as on the Windows CI row's cmd /d /s /c path. The stub
// reports its working directory and echoes a line of stdin, so the test sees the launcher ran
// from the workspace root with stdio passed through.
test('launcherCommand starts the launcher from the workspace root with stdio passed through', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lathe-launch-'));
  const workspace = path.join(tmp, 'workspace root');
  const launcher = path.join(tmp, 'my launchers', LAUNCHER);
  fs.mkdirSync(workspace);
  fs.mkdirSync(path.dirname(launcher));
  fs.writeFileSync(
    launcher,
    process.platform === 'win32'
      ? '@echo off\r\necho cwd=%CD%\r\nset /p line=\r\necho got=%line%\r\n'
      : '#!/bin/sh\necho "cwd=$(pwd)"\nread line\necho "got=$line"\n',
    { mode: 0o755 },
  );

  try {
    const { command, args, options } = launcherCommand(launcher, workspace);
    const child = spawn(command, args, { ...options, stdio: 'pipe' });
    child.stdin.end('hello\n');
    let stdout = '';
    child.stdout.on('data', (data: Buffer) => (stdout += data.toString('utf8')));
    const exitCode = await new Promise((resolve) => child.on('close', resolve));

    assert.equal(exitCode, 0, stdout);
    const lines = stdout.split(/\r?\n/);
    assert.ok(lines[0].endsWith(path.join(path.basename(tmp), 'workspace root')), stdout);
    assert.equal(lines[1], 'got=hello');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

// A module folder opened on its own finds the reactor root's .lathe/; a folder under no .lathe/ none.
test('findWorkspaceRoot walks up from a module folder to the nearest .lathe', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lathe-root-'));
  const root = path.join(tmp, 'my reactor');
  const module = path.join(root, 'core', 'sub');
  fs.mkdirSync(path.join(root, '.lathe'), { recursive: true });
  fs.mkdirSync(module, { recursive: true });

  try {
    assert.equal(findWorkspaceRoot(module), root);
    assert.equal(findWorkspaceRoot(root), root);
    assert.equal(findWorkspaceRoot(tmp), undefined);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});
