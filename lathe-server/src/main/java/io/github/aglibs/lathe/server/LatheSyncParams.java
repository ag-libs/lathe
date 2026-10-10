package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * Params of the {@code lathe/sync} notification: the server asks the client to run the workspace's
 * {@code .lathe/lathe-sync.sh} (the server never runs the build itself). {@code captureTests} maps
 * to {@code --tests}, which also re-captures {@code test-launch.json} for neotest/test-run. {@code
 * modules} are the reactor-relative modules for a scoped sync; empty means the whole workspace.
 */
public record LatheSyncParams(String workspaceRoot, boolean captureTests, List<String> modules) {

  public LatheSyncParams {
    ValidCheck.check().notNull(workspaceRoot, "workspaceRoot").validate();
    modules = modules != null ? List.copyOf(modules) : List.of();
  }
}
