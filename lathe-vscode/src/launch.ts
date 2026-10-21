const WINDOWS = process.platform === 'win32';

// The launcher lathe:sync writes for this OS (the Workspace Contract's client launch rule).
export const LAUNCHER = WINDOWS ? 'lathe-launcher.cmd' : 'lathe-launcher.sh';

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
