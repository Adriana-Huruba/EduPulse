package com.edupulse.retrievalpost.rerank;

/**
 * Raised when the cross-encoder reranker cannot load its model or fails during inference.
 */
public class RerankerException extends RuntimeException {

	public RerankerException(String message) {
		super(message);
	}

	public RerankerException(String message, Throwable cause) {
		super(message, cause);
	}
}
