package io.github.aglibs.lathe.maven.extension;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LatheLifecycleParticipantTest {

  private final LatheLifecycleParticipant participant = new LatheLifecycleParticipant();

  private static Path lockFile(final Path latheDir) {
    return LatheLayout.reactorLockFile(latheDir);
  }

  private static MavenProject projectWithDisabled(final String value) {
    final var project = new MavenProject(new Model());
    if (value != null) {
      project.getProperties().setProperty(LatheFlags.DISABLED, value);
    }

    return project;
  }

  // Maven before 3.9 cannot read Sisu's class index for Java 17 classes and skips the participant
  // silently, so this module must register it through the Plexus descriptor alone.
  @Test
  void registration_plexusDescriptorNamesParticipant_noSisuIndexInThisModule() throws Exception {
    final Path output =
        Path.of(
            LatheLifecycleParticipant.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());

    assertThat(output.resolve("META-INF/plexus/components.xml"))
        .content()
        .contains("org.apache.maven.AbstractMavenLifecycleParticipant")
        .contains(LatheLifecycleParticipant.class.getName());
    assertThat(output.resolve("META-INF/sisu/javax.inject.Named")).doesNotExist();
  }

  @Test
  void isPomOptOut_disabledAndNoLatheDir_optsOut(@TempDir final Path root) {
    final var latheDir = root.resolve(LatheLayout.LATHE_DIR);
    assertThat(LatheLifecycleParticipant.isPomOptOut(projectWithDisabled("true"), latheDir))
        .isTrue();
  }

  @Test
  void isPomOptOut_disabledButLatheDirExists_developerOptsBackIn(@TempDir final Path root)
      throws IOException {
    final var latheDir = Files.createDirectory(root.resolve(LatheLayout.LATHE_DIR));
    assertThat(LatheLifecycleParticipant.isPomOptOut(projectWithDisabled("true"), latheDir))
        .isFalse();
  }

  @Test
  void isPomOptOut_notDisabled_staysActive(@TempDir final Path root) {
    final var latheDir = root.resolve(LatheLayout.LATHE_DIR);
    assertThat(LatheLifecycleParticipant.isPomOptOut(projectWithDisabled(null), latheDir))
        .isFalse();
    assertThat(LatheLifecycleParticipant.isPomOptOut(projectWithDisabled("false"), latheDir))
        .isFalse();
  }

  @Test
  void reactorLock_existingLatheDir_writesLockThenReleasesOnSessionEnd(@TempDir final Path root)
      throws IOException {
    final var latheDir = Files.createDirectory(root.resolve(LatheLayout.LATHE_DIR));

    participant.startReactorLock(latheDir);
    assertThat(lockFile(latheDir)).exists();

    participant.stopReactorLock();
    assertThat(lockFile(latheDir)).doesNotExist();
  }

  @Test
  void mirrorLateOutputs_fileAddedAfterCompile_copiedIntoTheMirror_classesAndUnmirroredLeftAlone(
      @TempDir final Path root) throws IOException {
    final Path latheDir = root.resolve(LatheLayout.LATHE_DIR);
    final Path service = Path.of("META-INF/services/javax.annotation.processing.Processor");
    final Path output = root.resolve("apt/target/classes");
    Files.createDirectories(output.resolve(service).getParent());
    Files.writeString(output.resolve(service), "com.example.AptProcessor");
    Files.writeString(output.resolve("AptProcessor.class"), "build bytecode");
    final Path mirror = Files.createDirectories(latheDir.resolve("apt/classes"));
    Files.writeString(mirror.resolve("AptProcessor.class"), "server bytecode");
    final Path neverCompiled = Files.createDirectories(root.resolve("docs/target/classes"));
    Files.writeString(neverCompiled.resolve("notes.txt"), "notes");
    writeManifest(
        root,
        new OutputData("apt/target/classes", "apt", LatheLayout.CLASSES_DIR),
        new OutputData("docs/target/classes", "docs", LatheLayout.CLASSES_DIR));

    LatheLifecycleParticipant.mirrorLateOutputs(root);

    assertThat(mirror.resolve(service)).hasContent("com.example.AptProcessor");
    assertThat(mirror.resolve("AptProcessor.class"))
        .as("the server may have rewritten it")
        .hasContent("server bytecode");
    assertThat(latheDir.resolve("docs")).as("no mirror to top up").doesNotExist();
  }

  @Test
  void mirrorLateOutputs_noManifest_doesNothing(@TempDir final Path root) {
    LatheLifecycleParticipant.mirrorLateOutputs(root);

    assertThat(root.resolve(LatheLayout.LATHE_DIR)).doesNotExist();
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
  void afterSessionEnd_topUpFailsReadingTheOutput_buildUnaffectedAndLockReleased(
      @TempDir final Path root) throws IOException {
    final Path latheDir = Files.createDirectories(root.resolve(LatheLayout.LATHE_DIR));
    Files.createDirectories(latheDir.resolve("apt/classes"));
    final Path unreadable = Files.createDirectories(root.resolve("apt/target/classes/sub"));
    writeManifest(root, new OutputData("apt/target/classes", "apt", LatheLayout.CLASSES_DIR));
    participant.startReactorLock(latheDir);
    Files.setPosixFilePermissions(unreadable, Set.of());
    try {
      participant.afterSessionEnd(null);
    } finally {
      Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rwx------"));
    }

    assertThat(lockFile(latheDir)).doesNotExist();
  }

  private static void writeManifest(final Path root, final OutputData... outputs)
      throws IOException {
    Json.write(
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            root.toString(),
            null,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(outputs),
            null),
        root.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON));
  }

  @Test
  void reactorLock_missingLatheDir_writesNothingAndStopIsNoop(@TempDir final Path root) {
    final var latheDir = root.resolve(LatheLayout.LATHE_DIR);

    participant.startReactorLock(latheDir);
    assertThat(latheDir).doesNotExist();

    participant.stopReactorLock();
    assertThat(lockFile(latheDir)).doesNotExist();
  }
}
