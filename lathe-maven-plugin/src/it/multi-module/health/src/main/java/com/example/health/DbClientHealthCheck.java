package com.example.health;

import com.example.core.DbClient;

public final class DbClientHealthCheck {
  private final DbClient dbClient;

  public DbClientHealthCheck(final DbClient dbClient) {
    this.dbClient = dbClient;
  }

  public String name() {
    return dbClient.dbType();
  }
}
