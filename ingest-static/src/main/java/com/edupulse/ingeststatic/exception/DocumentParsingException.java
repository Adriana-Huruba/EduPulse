package com.edupulse.ingeststatic.exception;

/** The file looks like a PDF but cannot be parsed or has no extractable text. HTTP 422. */
public class DocumentParsingException extends IngestionException {

    public DocumentParsingException(String message) {
        super(message);
    }

    public DocumentParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
