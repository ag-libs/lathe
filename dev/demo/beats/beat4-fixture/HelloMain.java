package com.example.jpms;

import java.util.logging.Logger;

public final class HelloMain {

  private static final Logger logger = Logger.getLogger(HelloMain.class.getName());

  private HelloMain() {}

  public static void main(final String[] args) throws InterruptedException {
    logger.info("starting");
    System.out.println(Hello.greet("Main"));
    Thread.sleep(1000);
    logger.info("done");
  }
}
