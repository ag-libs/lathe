package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.TestFilterData;
import java.io.StringReader;
import java.util.List;
import java.util.regex.Pattern;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;

class MavenTestFilterTest {

  // Without Surefire configuration, `mvn test` runs Test*, *Test, *Tests and *TestCase classes in
  // any package, and no nested class on its own.
  @Test
  void filter_noConfiguration_matchesWhatSurefiresDefaultsRun() {
    final TestFilterData filter = MavenTestFilter.filter(null);

    assertThat(runs(filter, "com.x.FooTest", "com.x.TestFoo", "FooTests", "com.x.y.FooTestCase"))
        .containsOnly(true);
    assertThat(
            runs(filter, "com.x.FooIT", "com.x.Helper", "com.x.FooTest$Inner", "com.x.Testy.Foo"))
        .containsOnly(false);
  }

  // Configured includes replace the defaults (comma-separated entries too, and %regex[...] over the
  // class file's path); configured excludes replace the default nested-class exclude.
  @Test
  void filter_configuredPatterns_replaceTheDefaults() throws Exception {
    final TestFilterData filter =
        MavenTestFilter.filter(
            Xpp3DomBuilder.build(
                new StringReader(
                    """
                    <configuration>
                      <includes>
                        <include>**/*Test.java</include>
                        <include>**/*IT.java, com/x/special/Check?.java</include>
                        <include>%regex[.*/Slow.*Spec\\.class]</include>
                      </includes>
                      <excludes>
                        <exclude>**/Broken*</exclude>
                      </excludes>
                    </configuration>
                    """)));

    assertThat(
            runs(
                filter,
                "com.x.FooTest",
                "com.x.FooIT",
                "com.x.special.Check1",
                "com.x.SlowLoadSpec",
                "com.x.Outer$NestedTest"))
        .containsOnly(true);
    assertThat(runs(filter, "com.x.FooTests", "com.x.BrokenTest", "com.x.special.Check12"))
        .containsOnly(false);
  }

  // As JUnit's ClassNameFilter applies them: any include matches fully, and no exclude does.
  private static List<Boolean> runs(final TestFilterData filter, final String... classNames) {
    return List.of(classNames).stream()
        .map(
            name ->
                matchesAny(filter.classNameIncludes(), name)
                    && !matchesAny(filter.classNameExcludes(), name))
        .toList();
  }

  private static boolean matchesAny(final List<String> regexes, final String className) {
    return regexes.stream().anyMatch(regex -> Pattern.matches(regex, className));
  }
}
