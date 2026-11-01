import * as fs from 'fs';
import * as path from 'path';

const WINDOWS = process.platform === 'win32';

// The launcher lathe:sync writes for this OS (the Workspace Contract's client launch rule).
export const LAUNCHER = WINDOWS ? 'lathe-launcher.cmd' : 'lathe-launcher.sh';

// The nearest directory at or above `dir` holding a .lathe/: the workspace root, as the server and the
// launcher find it, so a module folder opened on its own still starts the root's launcher.
export function findWorkspaceRoot(dir: string): string | undefined {
  for (let current = path.resolve(dir); ; current = path.dirname(current)) {
    if (fs.statSync(path.join(current, '.lathe'), { throwIfNoEntry: false })?.isDirectory()) {
      return current;
    }
    if (path.dirname(current) === current) {
      return undefined;
    }
  }
}

export interface LauncherCommand {
  command: string;
  args: string[];
  options: { cwd: string; windowsVerbatimArguments: boolean };
}

// How to start the launcher on stdio, from cwd (the workspace root, where it finds
// .lathe/java-home). On Windows that's through cmd.exe, in the form and for the reasons the
// Workspace Contract gives.
export function launcherCommand(launcher: string, cwd: string): LauncherCommand {
  if (!WINDOWS) {
    return { command: launcher, args: [], options: { cwd, windowsVerbatimArguments: false } };
  }

  return {
    command: process.env.ComSpec ?? 'cmd.exe',
    args: ['/d', '/s', '/c', `""${launcher}""`],
    options: { cwd, windowsVerbatimArguments: true },
  };
}
