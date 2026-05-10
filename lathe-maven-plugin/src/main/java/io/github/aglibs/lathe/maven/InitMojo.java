package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@SuppressWarnings("unused")
@Mojo(name = "init", aggregator = true)
public final class InitMojo extends AbstractMojo {

  private static final String POM_PROPERTIES =
      "/META-INF/maven/io.github.ag-libs/lathe-maven-plugin/pom.properties";
  private static final String VERSION_PLACEHOLDER = "VERSION";

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Override
  public void execute() throws MojoExecutionException {
    final var workspaceRoot = session.getTopLevelProject().getBasedir().toPath();
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    getLog().info("[init] " + workspaceRoot);
    try {
      Files.createDirectories(latheDir);
      Files.writeString(latheDir.resolve(LatheLayout.ROOT_MARKER), "");
      Files.deleteIfExists(latheDir.resolve(LatheLayout.WORKSPACE_PROPERTIES));
      validateSetup();
    } catch (final IOException e) {
      throw new MojoExecutionException("lathe:init failed", e);
    }
  }

  private void validateSetup() throws MojoExecutionException {
    final var report = LatheSetup.inspect(session.getTopLevelProject(), session.getProjects());
    if (report.versionMismatch()) {
      throw new MojoExecutionException(
          """
          [lathe] lathe-compiler version %s does not match lathe-maven-plugin version %s.
                  Update both versions together in your pom.xml, then re-run:
                  mvn io.github.ag-libs:lathe-maven-plugin:%s:init
          """
              .formatted(report.compilerVersion(), report.mavenPluginVersion(), pluginVersion()));
    }

    if (!report.complete()) {
      logSetupGuidance(report);
    }
  }

  private void logSetupGuidance(final LatheSetup.Report report) {
    if (!report.compilerDependencyConfigured() || !report.compilerIdConfigured()) {
      getLog().warn(compilerSetupMessage());
    }

    if (!report.syncExecutionConfigured()) {
      getLog().warn(syncSetupMessage());
    }
  }

  private String compilerSetupMessage() {
    return """
        [lathe] Compiler shim not configured. Add the following to your parent pom.xml:

        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <dependencies>
            <dependency>
              <groupId>io.github.ag-libs</groupId>
              <artifactId>lathe-compiler</artifactId>
              <version>%s</version>
            </dependency>
          </dependencies>
          <configuration>
            <compilerId>lathe</compilerId>
          </configuration>
        </plugin>

        Re-run after updating the POM:
        mvn io.github.ag-libs:lathe-maven-plugin:%s:init
        """
        .formatted(pluginVersion(), pluginVersion());
  }

  private String syncSetupMessage() {
    return """
        [lathe] Sync goal not configured. Add the following to your parent pom.xml:

        <plugin>
          <groupId>io.github.ag-libs</groupId>
          <artifactId>lathe-maven-plugin</artifactId>
          <version>%s</version>
          <executions>
            <execution>
              <id>lathe-sync</id>
              <goals>
                <goal>sync</goal>
              </goals>
            </execution>
          </executions>
        </plugin>

        Re-run after updating the POM:
        mvn io.github.ag-libs:lathe-maven-plugin:%s:init
        """
        .formatted(pluginVersion(), pluginVersion());
  }

  private String pluginVersion() {
    final var props = new Properties();
    try (final var in = getClass().getResourceAsStream(POM_PROPERTIES)) {
      if (in == null) {
        return VERSION_PLACEHOLDER;
      }

      props.load(in);
      return props.getProperty("version", VERSION_PLACEHOLDER);
    } catch (final IOException e) {
      return VERSION_PLACEHOLDER;
    }
  }
}
