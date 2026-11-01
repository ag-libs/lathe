package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenStyleTest {

  @TempDir Path tmp;

  private static Xpp3Dom config(final String xml) throws Exception {
    return Xpp3DomBuilder.build(new StringReader(xml));
  }

  // The style sync derives from a Spotless <java> section.
  private static WorkspaceStyleData javaStyle(final String java) throws Exception {
    return MavenStyle.fromConfig(
        config("<configuration><java>%s</java></configuration>".formatted(java)));
  }

  @Test
  void fromConfig_googleJavaFormat_mapsToGoogle() throws Exception {
    final WorkspaceStyleData style = javaStyle("<googleJavaFormat/>");

    assertThat(style.formatter().engine()).isEqualTo("google");
    assertThat(style.indent().profile()).isEqualTo("google");
    assertThat(style.indent().block()).isEqualTo(2);
    assertThat(style.indent().continuation()).isEqualTo(4);
  }

  @Test
  void fromConfig_googleJavaFormatStepSettings_carriesOnlyConfiguredOnes() throws Exception {
    final FormatterSpec configured =
        javaStyle(
                """
                <googleJavaFormat>
                  <version> 1.28.0 </version>
                  <groupArtifact>com.example:custom-gjf</groupArtifact>
                  <reorderImports>true</reorderImports>
                  <formatJavadoc>false</formatJavadoc>
                </googleJavaFormat>
                """)
            .formatter();
    final FormatterSpec bare = javaStyle("<googleJavaFormat/>").formatter();

    assertThat(configured.version()).isEqualTo("1.28.0");
    assertThat(configured.options())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                LatheFlags.FORMAT_GROUP_ARTIFACT, "com.example:custom-gjf",
                LatheFlags.FORMAT_REORDER_IMPORTS, "true",
                LatheFlags.FORMAT_JAVADOC, "false"));
    assertThat(bare.version()).isEmpty();
    assertThat(bare.options()).isEmpty();
    assertThat(bare.classpath()).isEmpty();
  }

  @Test
  void fromConfig_googleJavaFormatAosp_mapsToAosp() throws Exception {
    final WorkspaceStyleData style =
        javaStyle("<googleJavaFormat><style>AOSP</style></googleJavaFormat>");

    assertThat(style.formatter().engine()).isEqualTo("aosp");
    assertThat(style.indent().block()).isEqualTo(4);
    assertThat(style.indent().continuation()).isEqualTo(8);
  }

  // The indent follows the step's style: PALANTIR indents by 4, and palantir run with GOOGLE style
  // by 2.
  @Test
  void fromConfig_palantirJavaFormat_mapsToPalantirWithStyleIndent() throws Exception {
    final WorkspaceStyleData palantir =
        javaStyle(
            """
            <palantirJavaFormat>
              <version>2.98.0</version>
              <formatJavadoc>true</formatJavadoc>
            </palantirJavaFormat>
            """);
    final WorkspaceStyleData googleStyled =
        javaStyle("<palantirJavaFormat><style>GOOGLE</style></palantirJavaFormat>");

    assertThat(palantir.formatter().engine()).isEqualTo("palantir");
    assertThat(palantir.formatter().version()).isEqualTo("2.98.0");
    assertThat(palantir.formatter().options())
        .containsExactlyEntriesOf(Map.of(LatheFlags.FORMAT_JAVADOC, "true"));
    assertThat(palantir.indent().block()).isEqualTo(4);
    assertThat(palantir.indent().continuation()).isEqualTo(8);
    assertThat(googleStyled.formatter().options())
        .containsExactlyEntriesOf(Map.of(LatheFlags.FORMAT_STYLE, "GOOGLE"));
    assertThat(googleStyled.indent().block()).isEqualTo(2);
  }

  @Test
  void fromConfig_eclipse_mapsToEclipseWithProfileAndEditorconfigIndent() throws Exception {
    final WorkspaceStyleData configured =
        javaStyle("<eclipse><file>build/eclipse.xml</file><version>4.38</version></eclipse>");
    final WorkspaceStyleData bare = javaStyle("<eclipse/>");

    assertThat(configured.formatter().engine()).isEqualTo("eclipse");
    assertThat(configured.formatter().version()).isEqualTo("4.38");
    assertThat(configured.formatter().options())
        .containsExactlyEntriesOf(Map.of(LatheFlags.FORMAT_FILE, "build/eclipse.xml"));
    assertThat(configured.indent().profile()).isEqualTo("editorconfig");
    assertThat(bare.formatter().options()).isEmpty();
  }

  // A formatter with no in-process engine is disabled, not delegated to Maven.
  @Test
  void fromConfig_otherFormatter_disablesFormatting() throws Exception {
    final WorkspaceStyleData style = javaStyle("<prettier/>");

    assertThat(style.formatter().engine()).isEqualTo("none");
    assertThat(style.formatter().command()).isEmpty();
    assertThat(style.indent().profile()).isEqualTo("editorconfig");
  }

  @Test
  void fromConfig_noJavaSection_returnsNull() throws Exception {
    assertThat(MavenStyle.fromConfig(config("<configuration/>"))).isNull();
    assertThat(MavenStyle.fromConfig(null)).isNull();
  }

  @Test
  void detect_spotlessPresentAndAbsent() throws Exception {
    assertThat(MavenStyle.detect(new MavenProject())).isNull();

    final var project = new MavenProject();
    withSpotless(project, "<googleJavaFormat/>", "3.0.0");

    assertThat(MavenStyle.detect(project).formatter().engine()).isEqualTo("google");
  }

  @Test
  void styleSource_rootOnlyManagesSpotless_takesTheJavaModulesOnlyWhenAllAgree() throws Exception {
    final MavenProject root = project("root", "pom");
    final MavenProject core = withSpotless(javaModule("core"), "<googleJavaFormat/>", "3.0.0");
    final MavenProject app = withSpotless(javaModule("app"), "<googleJavaFormat/>", "3.0.0");
    final MavenProject docs = project("docs", "pom");
    final var style = new MavenStyle(null, new SystemStreamLog());

    assertThat(style.styleSource(root, List.of(root, core, app, docs))).contains(core);
    // a Java module the build does not format
    assertThat(style.styleSource(root, List.of(root, core, app, javaModule("plain")))).isEmpty();
    // another configuration, or another version of the plugin
    assertThat(
            style.styleSource(
                root,
                List.of(
                    core,
                    withSpotless(
                        javaModule("aosp"),
                        "<googleJavaFormat><style>AOSP</style></googleJavaFormat>",
                        "3.0.0"))))
        .isEmpty();
    assertThat(
            style.styleSource(
                root,
                List.of(core, withSpotless(javaModule("newer"), "<googleJavaFormat/>", "3.1.0"))))
        .isEmpty();
    // the root's own Spotless still wins
    final MavenProject formattedRoot =
        withSpotless(project("root", "pom"), "<palantirJavaFormat/>", "3.0.0");
    assertThat(style.styleSource(formattedRoot, List.of(formattedRoot, javaModule("plain"))))
        .contains(formattedRoot);
  }

  private MavenProject project(final String name, final String packaging) {
    final var project = new MavenProject();
    project.setPackaging(packaging);
    project.setFile(tmp.resolve(name).resolve("pom.xml").toFile());
    return project;
  }

  private MavenProject javaModule(final String name) throws Exception {
    final MavenProject project = project(name, "jar");
    final Path sources = tmp.resolve(name).resolve("src/main/java");
    Files.createDirectories(sources);
    Files.writeString(sources.resolve("A.java"), "class A {}\n");
    project.addCompileSourceRoot(sources.toString());
    return project;
  }

  private static MavenProject withSpotless(
      final MavenProject project, final String java, final String version) throws Exception {
    final var spotless = new Plugin();
    spotless.setGroupId("com.diffplug.spotless");
    spotless.setArtifactId("spotless-maven-plugin");
    spotless.setVersion(version);
    spotless.setConfiguration(
        config("<configuration><java>%s</java></configuration>".formatted(java)));
    final var build = new Build();
    build.addPlugin(spotless);
    project.setBuild(build);
    return project;
  }
}
