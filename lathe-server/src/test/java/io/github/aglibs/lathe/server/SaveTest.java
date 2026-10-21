package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.lathe.core.CompiledStamps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;

// Saves through the write pass: coalescing, one dependents refresh, write-only diagnostics.
// Counts compiles through a JVM-wide log, so no other test class may compile meanwhile.
@Isolated
class SaveTest extends ServiceTestBase {

  @Test
  void didSave_processorReportsOnTheWrite_mergedIntoThePublishUntilTheBufferChanges()
      throws Exception {
    final Path processors = compileNoteProcessor();
    final Path note = saveWithProcessor(List.of(), List.of(processors));

    // Edited, the buffer no longer holds what was written, so the write's warning no longer
    // applies.
    change(note, "package com.example; class Note { private int marker; }", 2);
    verify(client, timeout(5_000))
        .publishDiagnostics(
            argThat(
                p ->
                    isFor(p, note)
                        && hasMessage(p, "'marker'")
                        && !hasMessage(p, "noted by processor")));
  }

  // Without a processor path javac finds processors on the classpath, as Maven's build does.
  @Test
  void didSave_processorOnTheClasspathOnly_reportsOnTheWrite() throws Exception {
    final Path processors = compileNoteProcessor();

    saveWithProcessor(List.of(processors), List.of());
  }

  // A processor warning on every root element. Analysis runs without annotation processors, so
  // only a write sees it.
  private Path compileNoteProcessor() throws IOException {
    final Path processorSource =
        TestCompiler.writeAt(
            tmp.resolve("processor-src/proc/NoteProcessor.java"),
            """
            package proc;
            import java.util.Set;
            import javax.annotation.processing.*;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;
            import javax.tools.Diagnostic;
            @SupportedAnnotationTypes("*")
            public class NoteProcessor extends AbstractProcessor {
              @Override public SourceVersion getSupportedSourceVersion() {
                return SourceVersion.latestSupported();
              }
              @Override public boolean process(Set<? extends TypeElement> types, RoundEnvironment round) {
                round.getRootElements().forEach(element -> processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.WARNING, "noted by processor", element));
                return false;
              }
            }
            """,
            1_000L);
    final Path processors = tmp.resolve("processors");
    TestCompiler.compileToDir(processors, processorSource);
    return processors;
  }

  // Opens and saves a module source whose build runs NoteProcessor from the given paths, and waits
  // for the write's warning. Returns the saved source.
  private Path saveWithProcessor(final List<Path> classpath, final List<Path> processorPath)
      throws Exception {
    final Path note =
        TestCompiler.writeMirroredModule(
                tmp,
                "module",
                Map.of("com/example/Note.java", "package com.example; class Note {}"),
                List.of(),
                1_000L)
            .get(0);
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        tmp.resolve("module/src/main/java"),
        null,
        classpath,
        processorPath,
        List.of("-processor", "proc.NoteProcessor"));
    service.initialize(tmp);
    awaitStartup();
    openDoc(note, Files.readString(note));
    awaitWorkerIdle();

    TestCompiler.writeAt(note, Files.readString(note), 5_000L);
    save(note);
    verify(client, timeout(5_000))
        .publishDiagnostics(argThat(p -> isFor(p, note) && hasMessage(p, "noted by processor")));
    return note;
  }

  @Test
  void didSave_singleSaveThenBurstAcrossFiles_recompilesEachOpenDependentOnce() throws Exception {
    final DepFixture fx = depWithUsers(3);
    service.initialize(tmp);
    awaitStartup();
    openDoc(fx.dep(), Files.readString(fx.dep()));
    for (final Path user : fx.users()) {
      openDoc(user, Files.readString(user));
      verify(client, timeout(5_000)).publishDiagnostics(argThat(p -> isFor(p, user)));
    }

    awaitWorkerIdle();
    clearInvocations(client);
    // Count compiles, not publishes: a stale result is dropped unpublished, so a pile-up of
    // redundant compiles only shows in the compile log.
    try (final var compiles = new CompileLog()) {
      // A single save refreshes its open dependents as soon as its own compile is done, in one
      // analysis task for their tree.
      save(fx.dep());
      for (final Path user : fx.users()) {
        verify(client, timeout(5_000)).publishDiagnostics(argThat(p -> isFor(p, user)));
      }

      awaitWorkerIdle();
      assertRefreshedOnce(compiles, fx.users());

      awaitWorkerIdle();
      compiles.clear();
      clearInvocations(client);
      // `:wa` after a rename saves every touched file back to back; the refresh waits for all of
      // their compiles, instead of every completing save refreshing every other open file.
      save(fx.dep());
      fx.users().forEach(this::save);
      for (final Path user : fx.users()) {
        verify(client, timeout(5_000).atLeastOnce())
            .publishDiagnostics(argThat(p -> isFor(p, user)));
      }

      // One quiet window, not one per file, for a duplicate refresh to show before counting.
      verify(client, after(1_500).atLeastOnce()).publishDiagnostics(any());

      awaitWorkerIdle();
      assertRefreshedOnce(compiles, fx.users());
    }
  }

  @Test
  void didSave_dependentEditedDuringRefreshBatch_lastPublishReflectsTheEdit() throws Exception {
    final DepFixture fx = depWithUsers(3);
    service.initialize(tmp);
    awaitStartup();
    openDoc(fx.dep(), Files.readString(fx.dep()));
    for (final Path user : fx.users()) {
      openDoc(user, Files.readString(user));
    }

    awaitWorkerIdle();
    clearInvocations(client);
    final Path edited = fx.users().get(0);

    // The save starts a refresh batch over the users; one of them is edited while it runs. A batch
    // result for the old content is stale and dropped, so the edit's own compile has the last word.
    save(fx.dep());
    service.didChange(
        new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(edited.toUri().toString(), 2),
            List.of(
                new TextDocumentContentChangeEvent(
                    "package com.example;\nclass User0 { int u() { return missing(); } }\n"))));

    // At least once: under load the refresh may re-analyze the edited file after its own compile,
    // publishing the same error again; the last publish is what matters.
    verify(client, timeout(5_000).atLeastOnce())
        .publishDiagnostics(
            argThat(
                p ->
                    isFor(p, edited)
                        && p.getDiagnostics().stream()
                            .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error)));
    awaitWorkerIdle();
    final var publishes = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, atLeastOnce()).publishDiagnostics(publishes.capture());
    assertThat(
            CollectionUtil.last(
                    publishes.getAllValues().stream().filter(p -> isFor(p, edited)).toList())
                .getDiagnostics())
        .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
  }

  @Test
  void didSave_savesArrivingDuringASaveCompile_compileAsOneBatch_eachRepublished()
      throws Exception {
    final DepFixture fx = depWithUsers(3);
    service.initialize(tmp);
    awaitStartup();
    final var files = new ArrayList<Path>(fx.users());
    files.add(fx.dep());
    for (final Path file : files) {
      openDoc(file, Files.readString(file));
    }

    awaitWorkerIdle();
    clearInvocations(client);
    try (final var compiles = new CompileLog()) {
      // A Save All of hand-edited files: the first save compiles at once, the rest arrive while it
      // runs and compile together when it is done.
      for (final Path file : files) {
        Files.writeString(file, Files.readString(file) + "\n// edited\n");
        save(file);
      }

      for (final Path file : files) {
        verify(client, timeout(10_000))
            .publishDiagnostics(argThat(p -> isFor(p, file) && p.getDiagnostics().isEmpty()));
      }

      awaitWorkerIdle();
      assertThat(writes(compiles))
          .as("write tasks, by files written")
          .hasSizeLessThanOrEqualTo(2)
          .satisfies(
              tasks ->
                  assertThat(tasks.stream().mapToInt(Integer::intValue).sum())
                      .as("files written")
                      .isEqualTo(files.size()));
      final Map<Path, Long> stamps = CompiledStamps.load(tmp.resolve(".lathe/module"), "classes");
      for (final Path file : files) {
        assertThat(stamps)
            .containsEntry(
                tmp.resolve("module/src/main/java").relativize(file),
                Files.getLastModifiedTime(file).toMillis());
      }
    }
  }

  // The open dependents were refreshed once: each republished exactly once, all by one multi-file
  // analysis task. A single-file analysis of the saved file itself is allowed.
  private void assertRefreshedOnce(final CompileLog compileLog, final List<Path> dependents) {
    assertThat(analyses(compileLog))
        .as("multi-file analysis tasks (the dependents refresh)")
        .filteredOn(files -> files > 1)
        .hasSize(1);
    dependents.forEach(
        dependent ->
            verify(client, times(1)).publishDiagnostics(argThat(p -> isFor(p, dependent))));
  }
}
