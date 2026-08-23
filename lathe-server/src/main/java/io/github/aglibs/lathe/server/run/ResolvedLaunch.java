package io.github.aglibs.lathe.server.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A fully resolved launch: the command line plus the process-level overlay (environment merge and
 * working directory). {@code cwd} is null when the run should inherit the server's working
 * directory.
 */
public record ResolvedLaunch(List<String> argv, Map<String, String> env, Path cwd) {

  public ResolvedLaunch {
    argv = List.copyOf(argv);
    env = Map.copyOf(env);
  }
}
