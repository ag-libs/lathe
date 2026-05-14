package io.github.aglibs.lathe.core.schema;

import java.util.List;

public record DependencyData(
    String gav, String jar, String status, String dir, List<String> classpath) {}
