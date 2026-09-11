package com.apklens.app.engine;

public class CancelledException extends RuntimeException {
    public CancelledException() { super("Cancelled by user"); }
}
