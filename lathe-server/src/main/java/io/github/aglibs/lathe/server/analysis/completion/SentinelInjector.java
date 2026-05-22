package io.github.aglibs.lathe.server.analysis.completion;

final class SentinelInjector {

  static final String SENTINEL = "__LATHE_SENTINEL__";

  enum Context {
    STATEMENT,
    EXPRESSION
  }

  private record BackwardResult(
      String prefix, int tokenStart, Context context, String receiverText) {}

  private record ForwardResult(int unclosedParens, int unclosedBraces) {}

  private final String content;

  SentinelInjector(final String content) {
    this.content = content;
  }

  SentinelResult inject(final int cursorOffset) {
    final BackwardResult back = backwardScan(cursorOffset);
    final ForwardResult fwd = forwardScan();

    final boolean parenFollows =
        cursorOffset < content.length() && content.charAt(cursorOffset) == '(';
    final var semicolon = back.context() == Context.STATEMENT && !parenFollows ? ";" : "";
    final var injected =
        content.substring(0, back.tokenStart())
            + SENTINEL
            + semicolon
            + content.substring(cursorOffset)
            + ")".repeat(fwd.unclosedParens())
            + "}".repeat(fwd.unclosedBraces());

    return new SentinelResult(
        back.prefix(), back.tokenStart(), back.receiverText(), back.context(), injected);
  }

  private BackwardResult backwardScan(final int cursorOffset) {
    int tokenStart = cursorOffset;
    while (tokenStart > 0 && Character.isJavaIdentifierPart(content.charAt(tokenStart - 1))) {
      tokenStart--;
    }

    final var prefix = content.substring(tokenStart, cursorOffset);

    int parenDepth = 0;
    int bracketDepth = 0;
    Context context = Context.STATEMENT;
    int i = tokenStart - 1;

    final boolean hasDot = i >= 0 && content.charAt(i) == '.';
    if (hasDot) {
      i--;
    }

    outer:
    while (i >= 0) {
      final char c = content.charAt(i);
      switch (c) {
        case ')' -> parenDepth++;
        case '(' -> {
          parenDepth--;
          if (parenDepth < 0) {
            context = Context.EXPRESSION;
            break outer;
          }
        }
        case ']' -> bracketDepth++;
        case '[' -> {
          bracketDepth--;
          if (bracketDepth < 0) {
            context = Context.EXPRESSION;
            break outer;
          }
        }
        case ';' -> {
          break outer;
        }
        case '{' -> {
          if (parenDepth == 0) {
            break outer;
          }
        }
        case ':' -> {
          context = Context.EXPRESSION;
          break outer;
        }
        case '>' -> {
          if (i > 0 && content.charAt(i - 1) == '-') {
            context = Context.EXPRESSION;
            break outer;
          }
        }
        case '/' -> {
          if (i > 0 && content.charAt(i - 1) == '*') {
            i -= 2;
            while (i > 0) {
              if (content.charAt(i) == '*' && content.charAt(i - 1) == '/') {
                i -= 2;
                break;
              }
              i--;
            }
          }
        }
        default -> {}
      }
      i--;
    }

    final String receiverText = hasDot ? collectReceiver(tokenStart - 2) : null;
    return new BackwardResult(prefix, tokenStart, context, receiverText);
  }

  private String collectReceiver(final int from) {
    int end = from;
    while (end >= 0) {
      final char c = content.charAt(end);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
        break;
      }
      end--;
    }
    if (end < 0) {
      return null;
    }

    int depth = 0;
    int i = end;
    while (i >= 0) {
      final char c = content.charAt(i);
      if (c == ')' || c == ']') {
        depth++;
      } else if (c == '(' || c == '[') {
        depth--;
      }

      if (depth < 0) {
        break;
      }

      if (depth == 0 && (c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '{' || c == ',')) {
        break;
      }

      i--;
    }

    final var raw = content.substring(i + 1, end + 1).trim();
    final var result = raw.startsWith(".") ? raw.substring(1) : raw;
    return result.isEmpty() ? null : result;
  }

  private ForwardResult forwardScan() {
    int parenDepth = 0;
    int braceDepth = 0;
    boolean inString = false;
    boolean inLineComment = false;
    boolean inBlockComment = false;
    int i = 0;

    while (i < content.length()) {
      if (inLineComment) {
        if (content.charAt(i) == '\n') {
          inLineComment = false;
        }

        i++;
        continue;
      }

      if (inBlockComment) {
        if (i + 1 < content.length() && content.charAt(i) == '*' && content.charAt(i + 1) == '/') {
          inBlockComment = false;
          i += 2;
        } else {
          i++;
        }

        continue;
      }

      if (!inString
          && i + 1 < content.length()
          && content.charAt(i) == '/'
          && content.charAt(i + 1) == '/') {
        inLineComment = true;
        i += 2;
        continue;
      }

      if (!inString
          && i + 1 < content.length()
          && content.charAt(i) == '/'
          && content.charAt(i + 1) == '*') {
        inBlockComment = true;
        i += 2;
        continue;
      }

      if (inString) {
        if (content.charAt(i) == '\\' && i + 1 < content.length()) {
          i += 2;
        } else {
          if (content.charAt(i) == '"') {
            inString = false;
          }
          i++;
        }
        continue;
      }

      if (i + 2 < content.length()
          && content.charAt(i) == '"'
          && content.charAt(i + 1) == '"'
          && content.charAt(i + 2) == '"') {
        i += 3;
        while (i < content.length()) {
          if (i + 2 < content.length()
              && content.charAt(i) == '"'
              && content.charAt(i + 1) == '"'
              && content.charAt(i + 2) == '"') {
            i += 3;
            break;
          }
          if (content.charAt(i) == '\\') {
            i += 2;
          } else {
            i++;
          }
        }
        continue;
      }

      if (content.charAt(i) == '"') {
        inString = true;
        i++;
        continue;
      }

      if (content.charAt(i) == '\'') {
        i++; // skip opening '
        if (i < content.length() && content.charAt(i) == '\\') {
          i++; // skip backslash
        }
        i++; // skip char value
        if (i < content.length() && content.charAt(i) == '\'') {
          i++; // skip closing '
        }
        continue;
      }

      switch (content.charAt(i)) {
        case '(' -> parenDepth++;
        case ')' -> parenDepth--;
        case '{' -> braceDepth++;
        case '}' -> braceDepth--;
        default -> {}
      }

      i++;
    }

    return new ForwardResult(Math.max(0, parenDepth), Math.max(0, braceDepth));
  }
}
