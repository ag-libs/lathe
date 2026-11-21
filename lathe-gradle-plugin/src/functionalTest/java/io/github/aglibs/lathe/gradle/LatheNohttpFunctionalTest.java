package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// WS-40: nohttp's check scans the whole project directory, and from the second build on .lathe/
// holds mirrored files with http:// URLs. A stand-in for io.spring.nohttp, with its extension's
// shape, lists the files its check would read.
class LatheNohttpFunctionalTest {

  private static final String SETTINGS =
      "%s%s".formatted(GradleFixture.latheSettings("root"), GradleFixture.latheRepositories());
  private static final String APPLY_NOHTTP = "plugins { id 'io.spring.nohttp' }\n";
  private static final String LIST_TASK = "listNohttpSource";
  private static final String MIRRORED =
      "%s/classes/META-INF/sbom/application.cdx.json".formatted(LatheLayout.LATHE_DIR);
  private static final String EXTENSION_WITH_SOURCE =
      """
      import org.gradle.api.file.ConfigurableFileTree;

      public class NohttpExtension {
        private ConfigurableFileTree source;

        public ConfigurableFileTree getSource() {
          return source;
        }

        public void setSource(ConfigurableFileTree source) {
          this.source = source;
        }
      }
      """;
  private static final String DEFAULT_SOURCE =
      "extension.setSource(project.fileTree(project.getProjectDir()));";

  @TempDir Path dir;

  @Test
  void nohttpApplied_latheMirrorLeftOutOfItsSource_projectFilesKept() throws Exception {
    final var fixture =
        project(APPLY_NOHTTP, EXTENSION_WITH_SOURCE, DEFAULT_SOURCE, "extension.getSource()");

    final String output = fixture.runner(LIST_TASK).build().getOutput();

    assertThat(output).contains("nohttp scans README.md").doesNotContain("nohttp scans .lathe");
  }

  // A build script that replaces the source after the plugin is applied, as nohttp's documentation
  // shows: the tree it ends with is the one the check reads.
  @Test
  void nohttpSourceReplacedByTheBuildScript_latheMirrorStillLeftOut() throws Exception {
    final var fixture =
        project(
            "%snohttp { source = fileTree(projectDir) }\n".formatted(APPLY_NOHTTP),
            EXTENSION_WITH_SOURCE,
            DEFAULT_SOURCE,
            "extension.getSource()");

    final String output = fixture.runner(LIST_TASK).build().getOutput();

    assertThat(output).contains("nohttp scans README.md").doesNotContain("nohttp scans .lathe");
  }

  // An extension of another shape, as a future nohttp might have: the build goes on, and says why
  // .lathe/ was not excluded.
  @Test
  void nohttpExtensionWithoutSource_buildGoesOnAndSaysWhy() throws Exception {
    final var fixture =
        project(
            APPLY_NOHTTP,
            """
            public class NohttpExtension {}
            """,
            "",
            "project.fileTree(project.getProjectDir())");

    final String output = fixture.runner(LIST_TASK, "--info").build().getOutput();

    assertThat(output)
        .contains("nohttp's extension has no source: .lathe/ not excluded")
        .contains("nohttp scans README.md");
  }

  // A root project applying the stand-in, as Spring's builds apply nohttp, with a project file and
  // a
  // mirrored one holding an http:// URL. The task prints each file the given tree holds.
  private GradleFixture project(
      final String buildScript,
      final String extensionSource,
      final String configure,
      final String scanned)
      throws Exception {
    return new GradleFixture(dir)
        .file("settings.gradle", SETTINGS)
        .file("build.gradle", buildScript)
        .file("README.md", "https://example.com\n")
        .file(MIRRORED, "{\"url\": \"http://example.com\"}\n")
        .file("buildSrc/src/main/java/NohttpExtension.java", extensionSource)
        .file(
            "buildSrc/src/main/java/StandInNohttp.java",
            """
            import java.io.File;
            import java.nio.file.Path;
            import org.gradle.api.Plugin;
            import org.gradle.api.Project;
            import org.gradle.api.file.FileTree;

            public class StandInNohttp implements Plugin<Project> {
              public void apply(Project project) {
                NohttpExtension extension =
                    project.getExtensions().create("nohttp", NohttpExtension.class);
                %s
                Path root = project.getProjectDir().toPath();
                project.getTasks().register("%s", task -> {
                  FileTree scanned = %s.matching(files -> files.exclude("buildSrc/**",
                      ".gradle/**", "build/**"));
                  task.doLast(t -> {
                    for (File file : scanned.getFiles()) {
                      System.out.println("nohttp scans " + root.relativize(file.toPath()));
                    }
                  });
                });
              }
            }
            """
                .formatted(configure, LIST_TASK, scanned))
        .file(
            "buildSrc/src/main/resources/META-INF/gradle-plugins/io.spring.nohttp.properties",
            "implementation-class=StandInNohttp\n");
  }
}
