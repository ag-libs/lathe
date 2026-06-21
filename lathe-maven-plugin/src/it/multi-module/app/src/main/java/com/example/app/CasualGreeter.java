package com.example.app;

import com.example.core.Greeter;

public class CasualGreeter implements Greeter {
  @Override
  public String greet(final String name) {
    return "Hey " + name + "!";
  }
}
