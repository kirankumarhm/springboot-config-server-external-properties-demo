package com.example.config.inventory.exception;

/** Raised when a reservation exceeds the currently configured maximum order quantity. */
public class OrderQuantityExceededException extends RuntimeException {

  private final int requested;
  private final int allowed;

  public OrderQuantityExceededException(int requested, int allowed) {
    super("Requested quantity " + requested + " exceeds configured maximum " + allowed);
    this.requested = requested;
    this.allowed = allowed;
  }

  public int getRequested() {
    return this.requested;
  }

  public int getAllowed() {
    return this.allowed;
  }
}
