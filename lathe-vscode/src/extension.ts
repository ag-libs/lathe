import * as fs from 'fs';
import * as path from 'path';
import { ExtensionContext, WorkspaceFolder, window, workspace } from 'vscode';
import { Executable, LanguageClient, LanguageClientOptions } from 'vscode-languageclient/node';
import { LAUNCHER, launcherCommand } from './launch';

const clients = new Map<string, LanguageClient>();
let warnedMissing = false;

export function activate(context: ExtensionContext): void {
  (workspace.workspaceFolders ?? []).forEach(startClient);
  context.subscriptions.push(
    workspace.onDidChangeWorkspaceFolders((event) => {
      event.added.forEach(startClient);
      event.removed.forEach(stopClient);
    }),
  );
}

export async function deactivate(): Promise<void> {
  await Promise.all([...clients.values()].map((client) => client.stop()));
  clients.clear();
}

// LATHE_SERVER_DIR dev override wins; else the workspace's per-version .lathe launcher.
function launcherPath(folder: WorkspaceFolder): string {
  const override = process.env.LATHE_SERVER_DIR;
  if (override) {
    return path.join(override, LAUNCHER);
  }
  return path.join(folder.uri.fsPath, '.lathe', LAUNCHER);
}

function startClient(folder: WorkspaceFolder): void {
  const key = folder.uri.toString();
  if (clients.has(key)) {
    return;
  }

  const launcher = launcherPath(folder);
  if (!fs.existsSync(launcher)) {
    if (!warnedMissing) {
      warnedMissing = true;
      window.showWarningMessage(
        `Lathe: no launcher at ${path.join('.lathe', LAUNCHER)} — run a Lathe-enabled build to generate it, then reload the window. If a build already ran, its log says why: Lathe needs Maven 3.6.3+.`,
      );
    }
    return;
  }

  // An Executable, not a spawn of our own: the client then also stops the process if it outlives
  // shutdown (on Windows with taskkill /T, which takes the JVM under cmd.exe too). The client
  // passes the options to spawn, so windowsVerbatimArguments reaches it.
  const serverOptions: Executable = launcherCommand(launcher, folder.uri.fsPath);
  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: 'file', language: 'java' }],
    workspaceFolder: folder,
  };

  const client = new LanguageClient('lathe', 'Lathe for Java', serverOptions, clientOptions);
  client.start();
  clients.set(key, client);
}

function stopClient(folder: WorkspaceFolder): void {
  const key = folder.uri.toString();
  const client = clients.get(key);
  if (!client) {
    return;
  }

  clients.delete(key);
  void client.stop();
}
