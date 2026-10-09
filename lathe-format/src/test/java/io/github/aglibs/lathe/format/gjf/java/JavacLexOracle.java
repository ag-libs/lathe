package io.github.aglibs.lathe.format.gjf.java;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Options;
import io.github.aglibs.lathe.format.JavaLexer.LexToken;
import io.github.aglibs.lathe.format.gjf.java.JavacTokens.RawTok;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/**
 * javac's own scanner, the way the formatter used to call it, reduced to {@link LexToken} ranges so
 * it can serve as the oracle for {@link io.github.aglibs.lathe.format.JavaLexer}.
 */
public final class JavacLexOracle {

  private JavacLexOracle() {}

  public static Optional<List<LexToken>> tokenize(final String text) {
    final var context = new Context();
    Options.instance(context).put("--enable-preview", "true");
    context.put(JavaFileManager.class, new JavacFileManager(context, false, UTF_8));
    final var diagnostics = new DiagnosticCollector<JavaFileObject>();
    context.put(DiagnosticListener.class, diagnostics);
    Log.instance(context).useSource(Trees.sourceFile(text));
    final List<RawTok> rawTokens = JavacTokens.getTokens(text, context, Set.of(TokenKind.EOF));
    if (diagnostics.getDiagnostics().stream().anyMatch(JavacLexOracle::isError)) {
      return Optional.empty();
    }

    final List<LexToken> tokens = new ArrayList<>();
    rawTokens.forEach(rawToken -> addRanges(text, rawToken, tokens));
    return Optional.of(List.copyOf(tokens));
  }

  private static boolean isError(final Diagnostic<?> diagnostic) {
    return diagnostic.getKind() == Diagnostic.Kind.ERROR;
  }

  // Ranges past the text come from the scanner's appended EOF comment.
  private static void addRanges(
      final String text, final RawTok rawToken, final List<LexToken> out) {
    final int start = rawToken.pos();
    final int end = Math.min(rawToken.endPos(), text.length());
    if (start >= end) {
      return;
    }

    final String raw = text.substring(start, end);
    if (rawToken.kind() == null && raw.startsWith("//")) {
      addLineCommentRanges(raw, start, out);
      return;
    }

    out.add(new LexToken(start, end));
  }

  // Since JDK 23 (JEP 467) javac merges consecutive /// lines into one Markdown doc comment; the
  // formatter treats them per line as earlier javac did (the output is the same), so split them
  // back into comment lines and the whitespace runs between them.
  private static void addLineCommentRanges(
      final String raw, final int offset, final List<LexToken> out) {
    int i = 0;
    while (i < raw.length()) {
      final int lineEnd = indexOfLineBreak(raw, i);
      out.add(new LexToken(offset + i, offset + lineEnd));
      int next = lineEnd;
      while (next < raw.length() && Character.isWhitespace(raw.charAt(next))) {
        next++;
      }

      if (next > lineEnd) {
        out.add(new LexToken(offset + lineEnd, offset + next));
      }

      i = next;
    }
  }

  private static int indexOfLineBreak(final String raw, final int from) {
    int i = from;
    while (i < raw.length() && raw.charAt(i) != '\n' && raw.charAt(i) != '\r') {
      i++;
    }

    return i;
  }
}
