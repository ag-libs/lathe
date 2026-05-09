package com.example.jpms;

import io.github.aglibs.validcheck.ValidCheck;

public final class Hello {

  public static String greet(final String name) {
    ValidCheck.requireNotNull(name, "name");
    return "Hello, " + name + "!";
  }
}
