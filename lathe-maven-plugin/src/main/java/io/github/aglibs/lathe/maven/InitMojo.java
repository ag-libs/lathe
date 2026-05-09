package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@SuppressWarnings("unused")
@Mojo(name = "init", aggregator = true)
public final class InitMojo extends AbstractMojo {

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
    } catch (final IOException e) {
      throw new MojoExecutionException("lathe:init failed", e);
    }
  }
}
