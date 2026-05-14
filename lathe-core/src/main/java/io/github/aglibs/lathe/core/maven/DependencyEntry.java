package io.github.aglibs.lathe.core.maven;

import java.util.List;

public record DependencyEntry(
    String gav, String jar, String status, String dir, List<String> classpath) {}
