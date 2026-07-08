package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TestLaunchData(
    String schemaVersion,
    String kind,
    LaunchMode mode,
    String javaHome,
    String mainModule,
    List<String> modulePath,
    List<String> classPath,
    Map<String, String> patchModules,
    List<String> addOpens,
    List<String> addReads,
    List<String> addExports,
    List<String> addModules,
    List<String> jvmArgs) {

  public TestLaunchData {
    ValidCheck.check()
        .notBlank(schemaVersion, "schemaVersion")
        .notBlank(kind, "kind")
        .notNull(mode, "mode")
        .notBlank(javaHome, "javaHome")
        .when(mode == LaunchMode.MODULE, v -> v.notBlank(mainModule, "mainModule"))
        .when(
            mode == LaunchMode.CLASSPATH,
            v -> v.assertTrue(mainModule == null || mainModule.isBlank(), "mainModule"))
        .validate();
    modulePath = modulePath != null ? List.copyOf(modulePath) : List.of();
    classPath = classPath != null ? List.copyOf(classPath) : List.of();
    patchModules =
        patchModules != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(patchModules))
            : Map.of();
    addOpens = addOpens != null ? List.copyOf(addOpens) : List.of();
    addReads = addReads != null ? List.copyOf(addReads) : List.of();
    addExports = addExports != null ? List.copyOf(addExports) : List.of();
    addModules = addModules != null ? List.copyOf(addModules) : List.of();
    jvmArgs = jvmArgs != null ? List.copyOf(jvmArgs) : List.of();
  }
}
