package com.edupulse.retrievalpost.grounding;

/**
 * Raised when grounded answer generation fails (for example, the LLM provider is unreachable).
 */
public class GroundingException extends RuntimeException {

	public GroundingException(String message) {
		super(message);
	}

	public GroundingException(String message, Throwable cause) {
		super(message, cause);
	}
}
