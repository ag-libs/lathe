package io.github.aglibs.lathe.openjdk;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.install.PluginProps;
import io.github.aglibs.lathe.install.ServerInstaller;
import io.github.aglibs.lathe.install.SyncException;
import java.io.IOException;
import java.nio.file.Path;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;

/**
 * {@code lathe-openjdk:sync} — reads the OpenJDK build's per-module descriptors, emits the
 * OpenJDK-native {@code .lathe/}, and installs the matching server bundle into {@code
 * ~/.cache/lathe/}. Runs without a project ({@code requiresProject = false}) so it can be invoked
 * by coordinate in the JDK checkout.
 */
@Mojo(name = "sync", requiresProject = false, threadSafe = true)
public final class SyncMojo extends AbstractMojo {

  /** The configured build output dir, e.g. {@code build/linux-x86_64-server-release}. */
  @Parameter(property = "lathe.buildDir", required = true)
  private String buildDir;

  @Parameter(defaultValue = "${basedir}", readonly = true, required = true)
  private String workspaceRoot;

  @Inject private RepositorySystem repositorySystem;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Override
  public void execute() throws MojoExecutionException {
    if (LatheFlags.isDisabled()) {
      getLog().info("[sync] disabled (CI or lathe.disabled) — skipping");
      return;
    }

    try {
      OpenJdkSync.sync(
          Path.of(buildDir), Path.of(workspaceRoot), PluginProps.version(), getLog()::info);
      installServer();
    } catch (final IOException e) {
      throw new MojoExecutionException("lathe openjdk sync failed", e);
    } catch (final SyncException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
  }

  private void installServer() {
    final var installer =
        new ServerInstaller(
            repositorySystem,
            session.getRepositorySession(),
            session.getCurrentProject().getRemoteProjectRepositories(),
            getLog());
    installer.install();
  }
}
