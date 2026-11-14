package com.xgen.mongot.index.statistics;

/** A diagnostic exceeds its bounded metadata or result budget. */
public final class StatisticsLimitException extends RuntimeException {
  public StatisticsLimitException(String message) {
    super(message);
  }
}
