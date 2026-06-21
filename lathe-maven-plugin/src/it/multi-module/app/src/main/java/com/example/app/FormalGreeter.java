package com.example.app;

import com.example.core.Greeter;

public class FormalGreeter implements Greeter {
  @Override
  public String greet(final String name) {
    return "Hello, " + name + ".";
  }
}
