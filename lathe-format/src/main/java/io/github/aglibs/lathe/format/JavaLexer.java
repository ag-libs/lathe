package io.github.aglibs.lathe.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Splits Java source into the contiguous ranges the formatter consumes: whitespace runs, comments,
 * literals, identifiers, numbers, operators, and separators, cut exactly where javac's scanner cuts
 * them. Token kinds are not reported; the formatter classifies ranges by their text.
 */
public final class JavaLexer {

  /** The source range {@code [start, end)} of one token, comment, or whitespace run. */
  public record LexToken(int start, int end) {

    public LexToken {
      if (start < 0 || end <= start) {
        throw new IllegalArgumentException("invalid range [%d, %d)".formatted(start, end));
      }
    }
  }

  /**
   * The tokens before the first lex error, in order; {@code complete} when the whole text lexed.
   * Like javac's scanner, a caller that stops early never sees an error further on.
   */
  public record Lexed(List<LexToken> tokens, boolean complete) {

    public Lexed {
      tokens = List.copyOf(tokens);
    }
  }

  private static final int ERROR = -1;
  private static final char END = Character.MAX_VALUE;
  private static final String TEXT_BLOCK = "\"\"\"";

  // javac's operator tokens; it extends an operator while the longer text is still one. The
  // formatter splits operators into characters, but counts columns by javac's operator length.
  private static final Set<String> OPERATORS =
      Set.of(
          "!", "%", "&", "*", "?", "+", "-", ":", "<", "=", ">", "^", "|", "~", "/", "->", "::",
          "==", "<=", ">=", "!=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "&=", "|=", "^=",
          "%=", "<<", ">>", ">>>", "<<=", ">>=", ">>>=");

  // Source with Unicode escapes decoded up to the first malformed one; rawPos maps each decoded
  // char back to its raw offset, and rawPos[length] is the raw offset where decoding stopped.
  private final char[] chars;
  private final int[] rawPos;
  private final int length;
  private final boolean decoded;

  private JavaLexer(
      final char[] chars, final int[] rawPos, final int length, final boolean decoded) {
    this.chars = chars;
    this.rawPos = rawPos;
    this.length = length;
    this.decoded = decoded;
  }

  /** Returns {@code text} with its Unicode escapes decoded, or unchanged if one is malformed. */
  public static String decodeUnicodeEscapes(final String text) {
    final JavaLexer lexer = decode(text);
    return lexer.decoded ? new String(lexer.chars, 0, lexer.length) : text;
  }

  /** Returns the ranges covering {@code text}, or empty if it cannot be lexed. */
  public static Optional<List<LexToken>> tokenize(final String text) {
    final Lexed lexed = lex(text);
    return lexed.complete() ? Optional.of(lexed.tokens()) : Optional.empty();
  }

  /** Lexes {@code text} up to its first error. */
  public static Lexed lex(final String text) {
    return decode(text).scan();
  }

  // JLS 3.3: a backslash starts a Unicode escape only when preceded by an even number of raw
  // backslashes; a backslash produced by an escape never starts another one.
  private static JavaLexer decode(final String text) {
    final var chars = new char[text.length()];
    final var rawPos = new int[text.length() + 1];
    int n = 0;
    int i = 0;
    int backslashes = 0;
    while (i < text.length()) {
      final char c = text.charAt(i);
      rawPos[n] = i;
      if (c != '\\' || backslashes % 2 == 1 || rawAt(text, i + 1) != 'u') {
        backslashes = c == '\\' ? backslashes + 1 : 0;
        chars[n++] = c;
        i++;
        continue;
      }

      int digits = i + 1;
      while (rawAt(text, digits) == 'u') {
        digits++;
      }

      final int value = hexValue(text, digits);
      if (value == ERROR) {
        return new JavaLexer(chars, rawPos, n, false);
      }

      chars[n++] = (char) value;
      i = digits + 4;
      backslashes = 0;
    }

    rawPos[n] = text.length();
    return new JavaLexer(chars, rawPos, n, true);
  }

  private static char rawAt(final String text, final int i) {
    return i < text.length() ? text.charAt(i) : END;
  }

  private static int hexValue(final String text, final int start) {
    if (start + 4 > text.length()) {
      return ERROR;
    }

    int value = 0;
    for (int i = start; i < start + 4; i++) {
      final int digit = Character.digit(text.charAt(i), 16);
      if (digit < 0) {
        return ERROR;
      }

      value = value * 16 + digit;
    }

    return value;
  }

  private Lexed scan() {
    final List<LexToken> tokens = new ArrayList<>();
    int i = 0;
    while (i < length) {
      final int end = tokenEnd(i);
      if (end == ERROR) {
        return new Lexed(tokens, false);
      }

      tokens.add(new LexToken(rawPos[i], rawPos[end]));
      i = end;
    }

    if (decoded) {
      return new Lexed(tokens, true);
    }

    // Decoding stopped at a malformed escape, which may have cut the last token short.
    if (!tokens.isEmpty()) {
      tokens.removeLast();
    }

    return new Lexed(tokens, false);
  }

  private int tokenEnd(final int i) {
    final char c = chars[i];
    if (isWhitespace(c)) {
      return skip(i, JavaLexer::isWhitespace);
    }

    if (startsWith(i, "//")) {
      return skip(i + 2, ch -> !isLineBreak(ch));
    }

    if (startsWith(i, "/*")) {
      return blockCommentEnd(i + 2);
    }

    if (startsWith(i, TEXT_BLOCK)) {
      return literalEnd(i + TEXT_BLOCK.length(), TEXT_BLOCK, false);
    }

    if (c == '"' || c == '\'') {
      return literalEnd(i + 1, String.valueOf(c), true);
    }

    if (isDigit(c) || (c == '.' && isDigit(at(i + 1)))) {
      return numberEnd(i);
    }

    if (startsWith(i, "...")) {
      return i + 3;
    }

    final int codePoint = Character.codePointAt(chars, i, length);
    if (Character.isJavaIdentifierStart(codePoint)) {
      return identifierEnd(i);
    }

    if (OPERATORS.contains(String.valueOf(c))) {
      return operatorEnd(i);
    }

    return i + Character.charCount(codePoint);
  }

  private int operatorEnd(final int start) {
    int end = start + 1;
    while (end < length && OPERATORS.contains(new String(chars, start, end + 1 - start))) {
      end++;
    }

    return end;
  }

  private int blockCommentEnd(final int start) {
    for (int i = start; i < length; i++) {
      if (startsWith(i, "*/")) {
        return i + 2;
      }
    }

    return ERROR;
  }

  // String, character, and text block literals: a backslash escapes the next char, so an escaped
  // delimiter does not close the literal.
  private int literalEnd(final int start, final String delimiter, final boolean singleLine) {
    int i = start;
    while (i < length) {
      if (chars[i] == '\\') {
        i += 2;
        continue;
      }

      if (startsWith(i, delimiter)) {
        return i + delimiter.length();
      }

      if (singleLine && isLineBreak(chars[i])) {
        return ERROR;
      }

      i++;
    }

    return ERROR;
  }

  private int numberEnd(final int start) {
    final char radix = at(start + 1);
    if (chars[start] == '0' && (radix == 'x' || radix == 'X')) {
      return realEnd(start + 2, JavaLexer::isHexDigit, 'p');
    }

    if (chars[start] == '0' && (radix == 'b' || radix == 'B')) {
      return suffixEnd(skip(start + 2, withUnderscores(JavaLexer::isDigit)));
    }

    return realEnd(start, JavaLexer::isDigit, 'e');
  }

  // Digits, an optional fraction, an optional signed exponent, and an optional type suffix; a
  // leading '.' (as in .5) is just an empty integer part.
  private int realEnd(final int start, final IntPredicate digit, final char exponent) {
    int i = skip(start, withUnderscores(digit));
    if (at(i) == '.') {
      i = skip(i + 1, withUnderscores(digit));
    }

    if (Character.toLowerCase(at(i)) == exponent) {
      final int sign = at(i + 1) == '+' || at(i + 1) == '-' ? i + 2 : i + 1;
      i = skip(sign, withUnderscores(JavaLexer::isDigit));
    }

    return suffixEnd(i);
  }

  private int suffixEnd(final int i) {
    return "lLfFdD".indexOf(at(i)) >= 0 ? i + 1 : i;
  }

  private int identifierEnd(final int start) {
    int i = start;
    while (i < length) {
      final int codePoint = Character.codePointAt(chars, i, length);
      if (!Character.isJavaIdentifierPart(codePoint)) {
        break;
      }

      i += Character.charCount(codePoint);
    }

    return i;
  }

  private int skip(final int start, final IntPredicate matches) {
    int i = start;
    while (i < length && matches.test(chars[i])) {
      i++;
    }

    return i;
  }

  private char at(final int i) {
    return i < length ? chars[i] : END;
  }

  private boolean startsWith(final int i, final String text) {
    for (int k = 0; k < text.length(); k++) {
      if (at(i + k) != text.charAt(k)) {
        return false;
      }
    }

    return true;
  }

  private static IntPredicate withUnderscores(final IntPredicate digit) {
    return ch -> ch == '_' || digit.test(ch);
  }

  private static boolean isWhitespace(final int c) {
    return c == ' ' || c == '\t' || c == '\f' || isLineBreak(c);
  }

  private static boolean isLineBreak(final int c) {
    return c == '\n' || c == '\r';
  }

  private static boolean isDigit(final int c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isHexDigit(final int c) {
    return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }
}
