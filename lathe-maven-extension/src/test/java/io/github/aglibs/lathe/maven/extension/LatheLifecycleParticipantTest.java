package io.github.aglibs.lathe.maven.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
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
  void mirrorLateOutputs_noOrUnreadableManifest_doesNothingAndNeverThrows(@TempDir final Path root)
      throws IOException {
    LatheLifecycleParticipant.mirrorLateOutputs(root);
    assertThat(root.resolve(LatheLayout.LATHE_DIR)).doesNotExist();

    // As another Lathe version wrote it: the build must not fail over the best-effort top-up.
    final Path latheDir = Files.createDirectories(root.resolve(LatheLayout.LATHE_DIR));
    Files.writeString(
        latheDir.resolve(LatheLayout.WORKSPACE_JSON),
        "{\"schemaVersion\":\"6\",\"workspaceRoot\":\"x\",\"buildFiles\":\"not a list\"}");

    assertThatCode(() -> LatheLifecycleParticipant.mirrorLateOutputs(root))
        .doesNotThrowAnyException();
  }

  // Maven 3.6.3, the API floor, has no session constructor left undeprecated.
  @SuppressWarnings("deprecation")
  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
  void afterSessionEnd_topUpFailsReadingTheOutput_buildUnaffectedAndLockReleased(
      @TempDir final Path root) throws IOException {
    final Path latheDir = Files.createDirectories(root.resolve(LatheLayout.LATHE_DIR));
    Files.createDirectories(latheDir.resolve("apt/classes"));
    final Path unreadable = Files.createDirectories(root.resolve("apt/target/classes/sub"));
    writeManifest(root, new OutputData("apt/target/classes", "apt", LatheLayout.CLASSES_DIR));
    participant.startReactorLock(latheDir);
    final var session =
        new MavenSession(
            null, null, new DefaultMavenExecutionRequest(), new DefaultMavenExecutionResult());
    session.setProjects(List.of());
    Files.setPosixFilePermissions(unreadable, Set.of());
    try {
      participant.afterSessionEnd(session);
    } finally {
      Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rwx------"));
    }

    assertThat(lockFile(latheDir)).doesNotExist();
  }

  // Maven found app up to date, so its stamps lack what the build compiled; lib has its record from
  // a compile; docs has no mirror.
  @Test
  void recordBuilt_moduleWithoutABuiltRecord_recordsItsSourcesAndKeepsAnExistingRecord(
      @TempDir final Path root) throws IOException {
    final Path foo = Path.of("com", "example", "Foo.java");
    final ContentFingerprint compiled = new ContentFingerprint(1, 1);
    final Path appDir = moduleWithSource(root, "app", foo);
    final var stamp = new SourceStamp(100L, null);
    CompiledStamps.writeAll(appDir, LatheLayout.CLASSES_DIR, Map.of(foo, stamp), Map.of());
    final Path libDir = moduleWithSource(root, "lib", foo);
    CompiledStamps.writeAll(
        libDir, LatheLayout.CLASSES_DIR, Map.of(foo, stamp), Map.of(foo, compiled));

    LatheLifecycleParticipant.recordBuilt(
        root, List.of(root.resolve("app"), root.resolve("lib"), root.resolve("docs")));

    assertThat(CompiledStamps.loadBuilt(appDir, LatheLayout.CLASSES_DIR))
        .containsExactly(
            Map.entry(foo, FileUtil.fingerprint(root.resolve("app/src/main/java").resolve(foo))));
    assertThat(CompiledStamps.load(appDir, LatheLayout.CLASSES_DIR))
        .containsExactly(Map.entry(foo, stamp));
    assertThat(CompiledStamps.loadBuilt(libDir, LatheLayout.CLASSES_DIR))
        .containsExactly(Map.entry(foo, compiled));
    assertThat(root.resolve(LatheLayout.LATHE_DIR).resolve("docs")).doesNotExist();
  }

  private static Path moduleWithSource(final Path root, final String module, final Path source)
      throws IOException {
    final Path sourceRoot = root.resolve(module).resolve("src/main/java");
    Files.createDirectories(sourceRoot.resolve(source).getParent());
    Files.writeString(sourceRoot.resolve(source), "class Foo {}");
    final Path moduleDir =
        Files.createDirectories(root.resolve(LatheLayout.LATHE_DIR).resolve(module));
    Json.write(
        Map.of(
            "sourceTree", LatheLayout.CLASSES_DIR,
            "outputDir", root.resolve(module).resolve("target/classes").toString(),
            "sourceRoots", List.of(sourceRoot.toString())),
        moduleDir.resolve(LatheLayout.paramsFileName(LatheLayout.CLASSES_DIR)));
    return moduleDir;
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
}
