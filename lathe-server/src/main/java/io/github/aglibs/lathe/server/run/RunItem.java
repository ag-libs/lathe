package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.schema.RunKind;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One run-config entry: an overlay scoped by {@code (module, kind)}. {@code kind} is required;
 * {@code module} is optional — an omitted {@code module} applies to every module of that kind.
 * Every overlay field is optional; an omitted key (null after parse) means "use the generated
 * default", and collections are normalized to immutable-empty so overlay application never branches
 * on null.
 */
public record RunItem(
    String module,
    RunKind kind,
    List<String> args,
    List<String> jvmArgs,
    Map<String, String> env,
    String cwd,
    List<String> classpathAppend,
    List<String> modulePathAppend) {

  public RunItem {
    ValidCheck.check().notNull(kind, "kind").validate();
    args = args != null ? List.copyOf(args) : List.of();
    jvmArgs = jvmArgs != null ? List.copyOf(jvmArgs) : List.of();
    env = env != null ? Map.copyOf(env) : Map.of();
    classpathAppend = classpathAppend != null ? List.copyOf(classpathAppend) : List.of();
    modulePathAppend = modulePathAppend != null ? List.copyOf(modulePathAppend) : List.of();
  }

  static RunItem empty(final String module, final RunKind kind) {
    return new RunItem(module, kind, null, null, null, null, null, null);
  }

  /**
   * Field-level merge with a higher-precedence (local) layer sharing this entry's {@code (module,
   * kind)}: {@code cwd} is overridden when set locally, lists concatenate shared-then-local, and
   * env unions with local winning on a key conflict.
   */
  RunItem mergedWith(final RunItem local) {
    return new RunItem(
        module,
        kind,
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
