package io.github.aglibs.lathe.core;

public record Stopwatch(long startMs) {

  public static Stopwatch start() {
    return new Stopwatch(System.currentTimeMillis());
  }

  public long elapsedMs() {
    return System.currentTimeMillis() - startMs;
  }
}
