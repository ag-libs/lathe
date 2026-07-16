package io.github.aglibs.lathe.maven.extension;

import io.github.aglibs.lathe.core.LatheFlags;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;

/**
 * Injects all Lathe build wiring into the effective reactor model in memory, so a project needs
 * only a {@code .mvn/extensions.xml} registration and no POM edits. Registered as a Maven core
 * extension (Sisu {@code @Named} component); {@code afterProjectsRead} is the single hook and
 * delegates all model changes to {@link LatheModelInjector}.
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
