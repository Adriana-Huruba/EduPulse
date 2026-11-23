package com.edupulse.ingeststatic.exception;

/**
 * Base class of every ingestion error. Subclasses map to an HTTP status in
 * {@link com.edupulse.ingeststatic.web.GlobalExceptionHandler}.
 */
public class IngestionException extends RuntimeException {

    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
