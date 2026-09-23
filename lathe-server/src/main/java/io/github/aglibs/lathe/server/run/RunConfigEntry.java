package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.RunKind;
import java.util.List;
import java.util.Map;

/**
 * On-disk overlay entry, mapped by Gson. Fields stay nullable — an omitted key means "unset", which
 * the writer relies on to emit only what the user set; semantic checks live in {@link RunItem}.
 */
record RunConfigEntry(
    String module,
    RunKind kind,
    String mainClass,
    List<RunSelector> selectors,
    List<String> args,
    List<String> jvmArgs,
    Map<String, String> env,
    String envFile,
    String cwd,
    List<String> classpathAppend,
    List<String> modulePathAppend) {

  RunConfigEntry {
    selectors = selectors != null ? List.copyOf(selectors) : null;
    args = args != null ? List.copyOf(args) : null;
    jvmArgs = jvmArgs != null ? List.copyOf(jvmArgs) : null;
    env = env != null ? Map.copyOf(env) : null;
    classpathAppend = classpathAppend != null ? List.copyOf(classpathAppend) : null;
    modulePathAppend = modulePathAppend != null ? List.copyOf(modulePathAppend) : null;
  }

  RunItem toItem(final String name) {
    final List<TestSelection> resolved =
        selectors != null ? selectors.stream().map(RunSelector::toSelection).toList() : null;
    return new RunItem(
        name,
        module,
        kind,
        mainClass,
        resolved,
        args,
        jvmArgs,
        env,
        envFile,
        cwd,
        classpathAppend,
        modulePathAppend);
  }

  static RunConfigEntry mainTarget(final String module, final String mainClass) {
    return new RunConfigEntry(
        module, RunKind.MAIN, mainClass, null, null, null, null, null, null, null, null);
  }

  static RunConfigEntry testTarget(final String module, final List<RunSelector> selectors) {
    return new RunConfigEntry(
        module, RunKind.TEST, null, selectors, null, null, null, null, null, null, null);
  }
}
