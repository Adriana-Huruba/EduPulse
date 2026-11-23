package com.edupulse.ingeststatic.exception;

/** The requested file or document does not exist. HTTP 404. */
public class SourceNotFoundException extends IngestionException {

    public SourceNotFoundException(String message) {
        super(message);
    }
}
