import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Locale.ENGLISH;

@SuppressWarnings("unused")
public class Workbench {

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Task {
    String value() default "";
  }

  enum State {
    PENDING,
    DONE
  }

  private static final String prefixConst = "item-";

  private final String labelField;
  private int scoreField;
  private final StringBuilder builderField = new StringBuilder();
  private final List<String> tagsField;

  public Workbench(final String labelParam, final List<String> tagsParam) {
    this.labelField = labelParam;
    this.scoreField = 0;
    this.tagsField = tagsParam;
  }

  public String getLabel() {
    return labelField;
  }

  public int getScore() {
    return scoreField;
  }

  public void localVarCases() {
    final StringBuilder builderVar = new StringBuilder();
    builderVar.append("x");
    final String stringVar = "hello";
    stringVar.length();
    final List<Integer> intListVar = List.of(1, 2, 3);
    intListVar.stream();
  }

  public void accessCases() {
    this.labelField.length();
    this.builderField.append("y");
    super.hashCode();
  }

  public void chainCases() {
    "hello".toUpperCase(ENGLISH).length();
    new StringBuilder().append("x").toString();
  }

  public void genericListCase() {
    final List<String> stringListVar = List.of("a", "b");
    stringListVar.get(0);
  }

  public void genericMapCase() {
    final Map<String, Integer> scoreMapVar = Map.of("k", 1);
    scoreMapVar.get("k");
  }

  public void lambdaCase() {
    tagsField.stream()
        .filter(tagItem -> tagItem.startsWith(prefixConst))
        .map(tagItem -> tagItem.toUpperCase())
        .toList();
  }

  public String overloaded(final String textParam) {
    return textParam;
  }

  public int overloaded(final int numberParam) {
    return numberParam;
  }

  public void useOverloads() {
    overloaded("hello");
    overloaded(42);
  }

  public <T> T wrap(final T wrapParam) {
    return wrapParam;
  }

  public void useWrap() {
    wrap("text");
  }

  public static String normalize(final String rawParam) {
    final String strippedVar = rawParam.strip();
    final String upperVar = strippedVar.toUpperCase(Locale.ROOT);
    return prefixConst + upperVar;
  }

  public Optional<String> findFirst(
      final List<String> searchListParam, final String prefixParam) {
    return searchListParam.stream()
        .filter(candidateItem -> candidateItem.startsWith(prefixParam))
        .map(candidateItem -> candidateItem.substring(prefixParam.length()))
        .findFirst();
  }

  public State getState() {
    return State.DONE;
  }

  public String formattedResult(final String valueParam) {
    Objects.requireNonNull(valueParam);
    return format("result: %s", valueParam);
  }

  public String localizedResult() {
    return "hello".toUpperCase(ENGLISH);
  }

  @Task("summarize")
  public Map<String, Long> summarize(final List<String> summaryListParam) {
    return summaryListParam.stream()
        .filter(summaryItem -> summaryItem.startsWith(prefixConst))
        .collect(
            Collectors.groupingBy(
                groupItem -> groupItem.substring(0, 1), Collectors.counting()));
  }

  /** A helper with Javadoc for hover tests. */
  public static class DocHelper {

    /** The maximum number of items. */
    public static final int MAX_ITEMS = 100;

    /**
     * Returns a greeting.
     *
     * @return the greeting string
     */
    public static String greet() {
      return "Hello";
    }

    /**
     * Repeats a value.
     *
     * @param <T> the value type
     * @param repeatValue the value to repeat
     * @param repeatCount how many times to repeat
     * @return a list of repeated values
     */
    public static <T> List<T> repeat(final T repeatValue, final int repeatCount) {
      return java.util.Collections.nCopies(repeatCount, repeatValue);
    }

    public void exercises() {
      final var docInstance = new DocHelper();
      final var maxVar = MAX_ITEMS;
      final var greetingVar = greet();
      final var repeatedVar = repeat("hi", 3);
      final var mappedVar = repeat("hi", 3).stream().map(repeatItem -> greet()).toList();
    }
  }
}
