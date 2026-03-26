package com.xgen.mongot.embedding.exceptions;

public class MaterializedViewTransientException extends RuntimeException {
  private final Reason reason;

  public MaterializedViewTransientException(String message) {
    super(message);
    this.reason = Reason.UNKNOWN;
  }

  public MaterializedViewTransientException(String message, Throwable cause) {
    super(message, cause);
    this.reason = Reason.UNKNOWN;
  }

  public MaterializedViewTransientException(Throwable throwable) {
    super(throwable);
    this.reason = Reason.UNKNOWN;
  }

  public MaterializedViewTransientException(String message, Reason reason) {
    super(message);
    this.reason = reason;
  }

  public Reason getReason() {
    return this.reason;
  }

  public enum Reason {
    // Can be caused by sync source is missing
    MONGO_CLIENT_NOT_AVAILABLE,
    UNKNOWN,
  }
}
