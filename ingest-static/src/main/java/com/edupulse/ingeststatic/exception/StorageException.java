package com.edupulse.ingeststatic.exception;

/** Embedding or vector store call failed (Ollama or Postgres unreachable, ...). HTTP 503. */
public class StorageException extends IngestionException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
