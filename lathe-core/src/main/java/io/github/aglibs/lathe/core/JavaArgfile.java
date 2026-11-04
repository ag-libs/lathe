package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// A java @argfile: one argument per line, quoted when the Java launcher would otherwise split or
// misread it. The launcher reads argfiles in native.encoding, not UTF-8 (on Windows, the ANSI code
// page), so they are written in it, and an argument it can't hold fails the write.
public final class JavaArgfile {

  // The charset the Java launcher reads argfiles in on this machine.
  public static final Charset NATIVE_CHARSET =
      Charset.forName(System.getProperty(LatheLayout.NATIVE_ENCODING_PROPERTY));

  // Whitespace and quotes split an argument, # starts a comment, and \ escapes only inside quotes.
  private static final Pattern NEEDS_QUOTES = Pattern.compile("[\\s\"'#\\\\]");

  private JavaArgfile() {}

  public static String render(final List<String> args) {
    return args.stream().map(JavaArgfile::quote).collect(Collectors.joining("\n", "", "\n"));
  }

  // Production callers pass NATIVE_CHARSET; a test passes a narrower one to check the failure.
  public static boolean write(
      final Path dir, final Path file, final List<String> args, final Charset charset)
      throws IOException {
    try {
      return FileUtil.writeBytesIfChanged(dir, file, FileUtil.encode(render(args), charset), false);
    } catch (final CharacterCodingException e) {
      throw new IOException(
          "%s: an argument can't be written in %s, the encoding the Java launcher reads it in"
              .formatted(file, charset),
          e);
    }
  }

  // One argument as the launcher reads it from an argfile, and from JDK_JAVA_OPTIONS, which it
  // parses the same way.
  public static String quote(final String arg) {
    if (!arg.isEmpty() && !NEEDS_QUOTES.matcher(arg).find()) {
      return arg;
    }

    return "\"%s\"".formatted(arg.replace("\\", "\\\\").replace("\"", "\\\""));
  }
}
