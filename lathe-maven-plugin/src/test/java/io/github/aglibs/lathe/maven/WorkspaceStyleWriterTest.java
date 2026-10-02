package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.StringReader;
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

  @Test
  void fromConfig_googleJavaFormat_mapsToGoogle() throws Exception {
    final WorkspaceStyleData style =
        WorkspaceStyleWriter.fromConfig(
            config("<configuration><java><googleJavaFormat/></java></configuration>"));

    assertThat(style.formatter().engine()).isEqualTo("google");
    assertThat(style.indent().profile()).isEqualTo("google");
    assertThat(style.indent().block()).isEqualTo(2);
    assertThat(style.indent().continuation()).isEqualTo(4);
  }

  @Test
  void fromConfig_googleJavaFormatAosp_mapsToAosp() throws Exception {
    final WorkspaceStyleData style =
        WorkspaceStyleWriter.fromConfig(
            config(
                "<configuration><java><googleJavaFormat><style>AOSP</style>"
                    + "</googleJavaFormat></java></configuration>"));

    assertThat(style.formatter().engine()).isEqualTo("aosp");
    assertThat(style.indent().block()).isEqualTo(4);
    assertThat(style.indent().continuation()).isEqualTo(8);
  }

  @Test
  void fromConfig_eclipse_mapsToNone() throws Exception {
    final WorkspaceStyleData style =
        WorkspaceStyleWriter.fromConfig(
            config(
                "<configuration><java><eclipse><file>fmt.xml</file></eclipse></java></configuration>"));

    assertThat(style.formatter().engine()).isEqualTo("none");
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
