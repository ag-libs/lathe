package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * Params of the {@code lathe/sync} notification: the server asks the client to run the workspace's
 * {@code .lathe/lathe-sync.sh} (the server never runs the build itself). {@code captureTests} maps
 * to {@code --tests}, which also re-captures {@code test-launch.json} for neotest/test-run.
 */
public record LatheSyncParams(String workspaceRoot, boolean captureTests) {

  public LatheSyncParams {
    ValidCheck.check().notNull(workspaceRoot, "workspaceRoot").validate();
  }
}
