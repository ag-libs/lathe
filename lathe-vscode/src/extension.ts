import * as fs from 'fs';
import * as path from 'path';
import { ExtensionContext, WorkspaceFolder, window, workspace } from 'vscode';
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  TransportKind,
} from 'vscode-languageclient/node';

const LAUNCHER = 'lathe-launcher.sh';

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

// LATHE_SERVER_DIR dev override wins; else the workspace's per-version .lathe/lathe-launcher.sh.
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
        'Lathe: no launcher at .lathe/lathe-launcher.sh — run a Lathe-enabled build to generate it, then reload the window.',
      );
    }
    return;
  }

  // cwd = workspace folder so the launcher resolves .lathe/java-home relative to it.
  const serverOptions: ServerOptions = {
    command: launcher,
    transport: TransportKind.stdio,
    options: { cwd: folder.uri.fsPath },
  };
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
