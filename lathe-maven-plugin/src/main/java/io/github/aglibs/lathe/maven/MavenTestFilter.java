package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.TestFilterData;
import io.github.aglibs.lathe.install.TestFilterWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

// A module's Surefire class filter as class-name regexes, so package and module runs execute the
// classes `mvn test` would: the default-test execution's includes/excludes over the plugin's, else
// Surefire's defaults.
final class MavenTestFilter {

  private static final String SUREFIRE_PLUGIN_KEY =
      Plugin.constructKey(
          LatheLayout.Maven.MAVEN_SUREFIRE_PLUGIN_GROUP_ID,
          LatheLayout.Maven.MAVEN_SUREFIRE_PLUGIN_ARTIFACT_ID);
  private static final String REGEX_PREFIX = "%regex[";

  private MavenTestFilter() {}

  static void write(final Path workspaceRoot, final MavenProject project) {
    if (LatheLayout.Maven.POM_PACKAGING.equals(project.getPackaging())) {
      return;
    }

    TestFilterWriter.write(
        workspaceRoot,
        ReactorProjects.moduleRel(workspaceRoot, project),
        filter(surefireConfiguration(project)));
  }

  static TestFilterData filter(final Xpp3Dom configuration) {
    return new TestFilterData(
        regexes(patterns(configuration, "includes", LatheLayout.Maven.SUREFIRE_DEFAULT_INCLUDES)),
        regexes(patterns(configuration, "excludes", LatheLayout.Maven.SUREFIRE_DEFAULT_EXCLUDES)));
  }

  // Surefire matches a pattern against a class file's path under the test output, and a class name
  // is that path with '.' for '/' and no extension; %regex[...] patterns are regexes over the path.
  static String classNameRegex(final String pattern) {
    if (pattern.startsWith(REGEX_PREFIX) && pattern.endsWith("]")) {
      return pattern
          .substring(REGEX_PREFIX.length(), pattern.length() - 1)
          .replace("/", "\\.")
          .replaceFirst("\\\\?\\.(class|java)$", "");
    }

    final String glob = pattern.replace('\\', '/').replaceFirst("\\.(class|java)$", "");
    final var regex = new StringBuilder();
    int i = 0;
    while (i < glob.length()) {
      if (glob.startsWith("**/", i)) {
        regex.append("(.*\\.)?");
        i += 3;
      } else if (glob.startsWith("**", i)) {
        regex.append(".*");
        i += 2;
      } else {
        regex.append(regexFor(glob.charAt(i)));
        i++;
      }
    }

    return regex.toString();
  }

  private static String regexFor(final char c) {
    return switch (c) {
      case '*' -> "[^.]*";
      case '?' -> "[^.]";
      case '/' -> "\\.";
      case '.', '$', '+', '(', ')', '[', ']', '{', '}', '^', '|' -> "\\" + c;
      default -> String.valueOf(c);
    };
  }

  private static List<String> regexes(final List<String> patterns) {
    return patterns.stream().map(MavenTestFilter::classNameRegex).toList();
  }

  // <includes><include>…</include></includes>, each entry possibly comma-separated; none →
  // defaults.
  private static List<String> patterns(
      final Xpp3Dom configuration, final String name, final List<String> defaults) {
    final Xpp3Dom list = configuration == null ? null : configuration.getChild(name);
    final List<String> patterns =
        list == null
            ? List.of()
            : Arrays.stream(list.getChildren())
                .map(Xpp3Dom::getValue)
                .filter(Objects::nonNull)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toList();
    return patterns.isEmpty() ? defaults : patterns;
  }

  // The default-test execution's configuration over the plugin-level one; null without Surefire.
  private static Xpp3Dom surefireConfiguration(final MavenProject project) {
    final Plugin plugin =
        project.getBuild() == null
            ? null
            : project.getBuild().getPluginsAsMap().get(SUREFIRE_PLUGIN_KEY);
    if (plugin == null) {
      return null;
    }

    final PluginExecution execution =
        plugin.getExecutionsAsMap().get(LatheLayout.Maven.SUREFIRE_TEST_EXECUTION_ID);
    return Xpp3Dom.mergeXpp3Dom(
        execution == null ? null : (Xpp3Dom) execution.getConfiguration(),
        (Xpp3Dom) plugin.getConfiguration());
  }
}
