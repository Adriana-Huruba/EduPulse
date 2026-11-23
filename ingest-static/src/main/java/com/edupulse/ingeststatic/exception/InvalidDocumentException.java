package com.edupulse.ingeststatic.exception;

/** The request or the uploaded file is not acceptable (empty, not a PDF, too large, bad path). HTTP 400. */
public class InvalidDocumentException extends IngestionException {

    public InvalidDocumentException(String message) {
        super(message);
    }
}
