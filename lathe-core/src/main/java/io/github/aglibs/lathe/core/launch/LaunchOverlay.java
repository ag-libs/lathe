package io.github.aglibs.lathe.core.launch;

import java.util.List;

/**
 * The user-owned, argv-affecting slice of a run configuration, resolved to plain strings and handed
 * to {@link LaunchPlan}. Path entries are already resolved to absolute form by the caller; {@code
 * LaunchPlan} only concatenates, never resolves. Environment and working directory live outside
 * argv and are applied at process spawn, so they are not part of this record.
 */
public record LaunchOverlay(
    List<String> jvmArgs,
    List<String> programArgs,
    List<String> classpathAppend,
    List<String> modulePathAppend) {

  public static final LaunchOverlay NONE =
      new LaunchOverlay(List.of(), List.of(), List.of(), List.of());

  public LaunchOverlay {
    jvmArgs = jvmArgs != null ? List.copyOf(jvmArgs) : List.of();
    programArgs = programArgs != null ? List.copyOf(programArgs) : List.of();
    classpathAppend = classpathAppend != null ? List.copyOf(classpathAppend) : List.of();
    modulePathAppend = modulePathAppend != null ? List.copyOf(modulePathAppend) : List.of();
  }
}
