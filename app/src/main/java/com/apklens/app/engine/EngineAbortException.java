package com.apklens.app.engine;

/** Fatal, user-meaningful pipeline abort (invalid input, quotas…). */
public class EngineAbortException extends RuntimeException {
    public EngineAbortException(String message) { super(message); }
}
