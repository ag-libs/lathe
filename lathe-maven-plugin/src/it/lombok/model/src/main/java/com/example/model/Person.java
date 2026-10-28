package com.example.model;

import lombok.Data;

@Data
public class Person {
  private String name;

  public String greeting() {
    return "Hello, " + getName();
  }
}
