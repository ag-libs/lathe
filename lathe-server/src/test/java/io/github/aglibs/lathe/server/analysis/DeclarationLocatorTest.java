package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclarationLocatorTest {

  @Test
  void findContract_nonOverriddenMethod_returnsEmpty(@TempDir final Path tempDir)
      throws IOException {
    final var source =
        """
        public class MyClass {
            public void greet() {}
        }
        """;
    final var parsed = compile(tempDir, "MyClass.java", source);
    final var path = SampleFixture.pathAt(parsed.trees(), parsed.cu(), "greet", "greet");
    final var element = SourceLocator.elementAt(parsed.trees(), path);

    final var contract =
        DeclarationLocator.findContract(
            element, parsed.task().getTypes(), parsed.task().getElements());

    assertThat(contract).isEmpty();
  }

  @Test
  void findContract_implementsInterface_returnsInterfaceMethod(@TempDir final Path tempDir)
      throws IOException {
    final var source =
        """
        public interface Greeter {
            void greet();
        }

        class MyGreeter implements Greeter {
            @Override
            public void greet() {}
        }
        """;
    final var parsed = compile(tempDir, "MyGreeter.java", source);
    final var path =
        SampleFixture.pathAt(parsed.trees(), parsed.cu(), "public void greet", "greet");
    final var element = SourceLocator.elementAt(parsed.trees(), path);

    final var contract =
        DeclarationLocator.findContract(
            element, parsed.task().getTypes(), parsed.task().getElements());

    assertThat(contract).isPresent();
    assertThat(contract.get().getEnclosingElement().getSimpleName().toString())
        .isEqualTo("Greeter");
  }

  @Test
  void findContract_extendsAbstractClass_returnsAbstractMethod(@TempDir final Path tempDir)
      throws IOException {
    final var source =
        """
        public abstract class BaseGreeter {
            public abstract void greet();
        }

        class AbstractGreeter extends BaseGreeter {
            @Override
            public void greet() {}
        }
        """;
    final var parsed = compile(tempDir, "AbstractGreeter.java", source);
    final var path =
        SampleFixture.pathAt(parsed.trees(), parsed.cu(), "public void greet", "greet");
    final var element = SourceLocator.elementAt(parsed.trees(), path);

    final var contract =
        DeclarationLocator.findContract(
            element, parsed.task().getTypes(), parsed.task().getElements());

    assertThat(contract).isPresent();
    assertThat(contract.get().getEnclosingElement().getSimpleName().toString())
        .isEqualTo("BaseGreeter");
  }

  @Test
  void findContract_overridesMultiple_prefersInterface(@TempDir final Path tempDir)
      throws IOException {
    final var source =
        """
        interface IRunnable {
            void run();
        }

        abstract class BaseRunnable {
            public void run() {}
        }

        class MyRunnable extends BaseRunnable implements IRunnable {
            @Override
            public void run() {}
        }
        """;
    final var parsed = compile(tempDir, "MyRunnable.java", source);
    final var path = SampleFixture.pathAt(parsed.trees(), parsed.cu(), "class MyRunnable", "run");
    final var element = SourceLocator.elementAt(parsed.trees(), path);

    final var contract =
        DeclarationLocator.findContract(
            element, parsed.task().getTypes(), parsed.task().getElements());

    assertThat(contract).isPresent();
    assertThat(contract.get().getEnclosingElement().getSimpleName().toString())
        .isEqualTo("IRunnable");
  }

  @Test
  void findContract_deepHierarchy_findsTopMostInterface(@TempDir final Path tempDir)
      throws IOException {
    final var source =
        """
        interface Top {
            void execute();
        }

        interface Middle extends Top {
            void execute();
        }

        class Impl implements Middle {
            @Override
            public void execute() {}
        }
        """;
    final var parsed = compile(tempDir, "Impl.java", source);
    final var path =
        SampleFixture.pathAt(parsed.trees(), parsed.cu(), "public void execute", "execute");
    final var element = SourceLocator.elementAt(parsed.trees(), path);

    final var contract =
        DeclarationLocator.findContract(
            element, parsed.task().getTypes(), parsed.task().getElements());

    assertThat(contract).isPresent();
    assertThat(contract.get().getEnclosingElement().getSimpleName().toString()).isEqualTo("Top");
  }

  private static TestCompiler.ParsedSource compile(
      final Path tempDir, final String filename, final String source) throws IOException {
    final var file = tempDir.resolve(filename);
    Files.writeString(file, source);
    return TestCompiler.parse(file);
  }
}
