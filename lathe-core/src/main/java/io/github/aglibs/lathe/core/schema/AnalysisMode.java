package io.github.aglibs.lathe.core.schema;

/**
 * How the server analyzes a module. {@code CLASSPATH} resolves dependencies from compiled
 * jars/mirror (Maven/Gradle); {@code MODULE_SYSTEM} resolves them from the host JDK's module system
 * and overlays the edited/dirty sources via {@code --patch-module} (OpenJDK). Why it must be
 * explicit: a module's shape alone is ambiguous — Maven JPMS modules also use {@code
 * --patch-module} and a module-path.
 */
public enum AnalysisMode {
  CLASSPATH,
  MODULE_SYSTEM
}
