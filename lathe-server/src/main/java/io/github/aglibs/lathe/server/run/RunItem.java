package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.RunKind;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One resolved run-config entry: a null {@code name} is a {@code (module, kind)} baseline
 * auto-applied to cursor runs; a named entry is a selectable config that pins a target. The
 * baseline-vs-config target rules are enforced by the reader/writer, which know the bucket and can
 * name it in the error; the constructor keeps only the always-true target-kind consistency.
 */
public record RunItem(
    String name,
    String module,
    RunKind kind,
    String mainClass,
    List<TestSelection> selectors,
    List<String> args,
    List<String> jvmArgs,
    Map<String, String> env,
    String cwd,
    List<String> classpathAppend,
    List<String> modulePathAppend) {

  public RunItem {
    ValidCheck.check()
        .notNull(kind, "kind")
        .assertTrue(mainClass == null || kind == RunKind.MAIN, "mainClass (MAIN only)")
        .validate();
    selectors = selectors != null ? List.copyOf(selectors) : List.of();
    ValidCheck.check()
        .assertTrue(selectors.isEmpty() || kind == RunKind.TEST, "selectors (TEST only)")
        .validate();
    args = args != null ? List.copyOf(args) : List.of();
    jvmArgs = jvmArgs != null ? List.copyOf(jvmArgs) : List.of();
    env = env != null ? Map.copyOf(env) : Map.of();
    classpathAppend = classpathAppend != null ? List.copyOf(classpathAppend) : List.of();
    modulePathAppend = modulePathAppend != null ? List.copyOf(modulePathAppend) : List.of();
  }

  static RunItem empty(final String module, final RunKind kind) {
    return new RunItem(null, module, kind, null, null, null, null, null, null, null, null);
  }

  RunItem withName(final String named) {
    return new RunItem(
        named,
        module,
        kind,
        mainClass,
        selectors,
        args,
        jvmArgs,
        env,
        cwd,
        classpathAppend,
        modulePathAppend);
  }

  boolean hasTarget() {
    return kind == RunKind.MAIN ? mainClass != null : !selectors.isEmpty();
  }

  /** Label for the run console/log: the config name, else whether a baseline overlay applied. */
  public String configLabel() {
    if (name != null) {
      return name;
    }

    return hasOverlay() ? "default+baseline" : "default";
  }

  private boolean hasOverlay() {
    return !args.isEmpty()
        || !jvmArgs.isEmpty()
        || !env.isEmpty()
        || cwd != null
        || !classpathAppend.isEmpty()
        || !modulePathAppend.isEmpty();
  }

  // Merge under a higher-precedence layer: identity/target from local; lists concat (this first),
  // env unions local-wins. Used for the layer merge and to compose a config over its baseline.
  RunItem mergedWith(final RunItem local) {
    return new RunItem(
        local.name,
        local.module,
        local.kind,
        local.mainClass,
        local.selectors,
        concat(args, local.args),
        concat(jvmArgs, local.jvmArgs),
        union(env, local.env),
        local.cwd != null ? local.cwd : cwd,
        concat(classpathAppend, local.classpathAppend),
        concat(modulePathAppend, local.modulePathAppend));
  }

  private static List<String> concat(final List<String> base, final List<String> extra) {
    return Stream.concat(base.stream(), extra.stream()).toList();
  }

  private static Map<String, String> union(
      final Map<String, String> base, final Map<String, String> extra) {
    final var merged = new LinkedHashMap<String, String>(base);
    merged.putAll(extra);
    return Map.copyOf(merged);
  }
}
