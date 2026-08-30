package io.github.aglibs.lathe.maven.extension;

import io.github.aglibs.lathe.core.LatheFlags;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;

/**
 * Injects all Lathe build wiring into the effective reactor model in memory, so a project needs
 * only to register the extension and no per-piece POM edits. Registered either as a Maven core
 * extension ({@code .mvn/extensions.xml}) or as a build extension ({@code <build><extensions>} in
 * the reactor-root POM); both reach the single {@code afterProjectsRead} hook, which runs after the
 * reactor model is read and delegates all model changes to {@link LatheModelInjector}.
 */
@Named("lathe")
@Singleton
public final class LatheLifecycleParticipant extends AbstractMavenLifecycleParticipant {

  @Override
  public void afterProjectsRead(final MavenSession session) {
    if (LatheFlags.isDisabled()) {
      return;
    }

    final var injector = new LatheModelInjector(ExtensionProps.version());
    session.getProjects().forEach(injector::injectProject);
    injector.injectRootExecutions(session.getTopLevelProject());
  }
}
