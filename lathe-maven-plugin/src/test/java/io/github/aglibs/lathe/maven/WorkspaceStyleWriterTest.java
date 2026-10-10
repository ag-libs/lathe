package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.StringReader;
import java.util.Map;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;

class WorkspaceStyleWriterTest {

  private static Xpp3Dom config(final String xml) throws Exception {
    return Xpp3DomBuilder.build(new StringReader(xml));
  }

  // The style sync derives from a Spotless <java> section.
  private static WorkspaceStyleData javaStyle(final String java) throws Exception {
    return WorkspaceStyleWriter.fromConfig(
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
    assertThat(WorkspaceStyleWriter.fromConfig(config("<configuration/>"))).isNull();
    assertThat(WorkspaceStyleWriter.fromConfig(null)).isNull();
  }

  @Test
  void detect_spotlessPresentAndAbsent() throws Exception {
    assertThat(WorkspaceStyleWriter.detect(new MavenProject())).isNull();

    final var project = new MavenProject();
    final var build = new Build();
    final var spotless = new Plugin();
    spotless.setGroupId("com.diffplug.spotless");
    spotless.setArtifactId("spotless-maven-plugin");
    spotless.setConfiguration(
        config("<configuration><java><googleJavaFormat/></java></configuration>"));
    build.addPlugin(spotless);
    project.setBuild(build);

    assertThat(WorkspaceStyleWriter.detect(project).formatter().engine()).isEqualTo("google");
  }
}
