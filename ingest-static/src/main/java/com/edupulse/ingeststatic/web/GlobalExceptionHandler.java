package com.edupulse.ingeststatic.web;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.edupulse.ingeststatic.exception.DocumentParsingException;
import com.edupulse.ingeststatic.exception.IngestionException;
import com.edupulse.ingeststatic.exception.InvalidDocumentException;
import com.edupulse.ingeststatic.exception.SourceNotFoundException;
import com.edupulse.ingeststatic.exception.StorageException;

/**
 * Translates ingestion exceptions into structured JSON error responses and logs failures.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidDocumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidDocument(InvalidDocumentException ex) {
        log.warn("Invalid document request: {}", ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Map<String, Object>> handleMissingParam(Exception ex) {
        log.warn("Missing request parameter or part: {}", ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected because file exceeds maximum size: {}", ex.getMessage());
        return error(HttpStatus.CONTENT_TOO_LARGE, "Uploaded file exceeds the configured size limit");
    }

    @ExceptionHandler(SourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(SourceNotFoundException ex) {
        log.warn("Source not found: {}", ex.getMessage());
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DocumentParsingException.class)
    public ResponseEntity<Map<String, Object>> handleParsingError(DocumentParsingException ex) {
        log.error("PDF parsing failed: {}", ex.getMessage(), ex);
        return error(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Map<String, Object>> handleStorageError(StorageException ex) {
        log.error("Vector store or embedding service error: {}", ex.getMessage(), ex);
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(IngestionException.class)
    public ResponseEntity<Map<String, Object>> handleIngestionError(IngestionException ex) {
        log.error("Ingestion error: {}", ex.getMessage(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? status.getReasonPhrase() : message));
    }
}
