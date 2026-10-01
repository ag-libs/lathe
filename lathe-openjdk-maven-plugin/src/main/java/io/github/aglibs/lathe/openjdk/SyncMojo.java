package io.github.aglibs.lathe.openjdk;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * {@code lathe-openjdk:sync} — reads the OpenJDK build's per-module descriptors and emits the
 * OpenJDK-native {@code .lathe/}. Runs without a project ({@code requiresProject = false}) so it
 * can be invoked by coordinate in the JDK checkout.
 */
@Mojo(name = "sync", requiresProject = false, threadSafe = true)
public final class SyncMojo extends AbstractMojo {

  /** The configured build output dir, e.g. {@code build/linux-x86_64-server-release}. */
  @Parameter(property = "lathe.buildDir", required = true)
  private String buildDir;

  @Parameter(defaultValue = "${basedir}", readonly = true, required = true)
  private String workspaceRoot;

  @Parameter(property = "lathe.serverVersion", defaultValue = "0.1.0-SNAPSHOT")
  private String serverVersion;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      OpenJdkSync.sync(Path.of(buildDir), Path.of(workspaceRoot), serverVersion, getLog()::info);
    } catch (final IOException e) {
      throw new MojoExecutionException("lathe openjdk sync failed", e);
    }
  }
}
