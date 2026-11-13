package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import java.nio.file.Path;
import java.util.Map;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.languages.java.jpms.LocationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenMainLaunchTest {

  @TempDir private Path root;

  @Test
  void write_jarModule_recordsTheWorkspaceJdk_pomModuleWritesNothing() throws Exception {
    final Path workspaceJdk = root.resolve("jdk");
    final var mainLaunch = new MavenMainLaunch(new LocationManager(), Map.of(), workspaceJdk);

    mainLaunch.write(root, project("app", "jar"));
    mainLaunch.write(root, project("parent", "pom"));

    final MainLaunchData launch = Json.read(launchFile("app"), MainLaunchData.class);
    assertThat(launch.javaHome()).isEqualTo(workspaceJdk.toString());
    assertThat(launchFile("parent")).doesNotExist();
  }

  private Path launchFile(final String module) {
    return root.resolve(LatheLayout.LATHE_DIR)
        .resolve(module)
        .resolve(LatheLayout.MAIN_LAUNCH_FILE);
  }

  private MavenProject project(final String module, final String packaging) {
    final Path dir = root.resolve(module);
    final var build = new Build();
    build.setOutputDirectory(dir.resolve("target/classes").toString());
    final var project = new MavenProject();
    project.setFile(dir.resolve("pom.xml").toFile());
    project.setPackaging(packaging);
    project.setBuild(build);
    return project;
  }
}
