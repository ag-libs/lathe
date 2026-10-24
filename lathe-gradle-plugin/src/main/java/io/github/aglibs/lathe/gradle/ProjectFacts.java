package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// What one project contributes to the workspace files. Each project writes its own, and the root's
// latheSync merges them, so no project reads another's model (Isolated Projects). javaHome is the
// JDK its main compile runs on, absent for a project without Java.
record ProjectFacts(
    String module,
    String buildFile,
    List<OutputData> outputs,
    List<ResourceRootData> resourceRoots,
    String javaHome,
    int javaVersion,
    List<String> accessFlags) {

  ProjectFacts {
    ValidCheck.check().notNull(module, "module").notNull(buildFile, "buildFile").validate();
    outputs = outputs != null ? List.copyOf(outputs) : List.of();
    resourceRoots = resourceRoots != null ? List.copyOf(resourceRoots) : List.of();
    accessFlags = accessFlags != null ? List.copyOf(accessFlags) : List.of();
  }
}
