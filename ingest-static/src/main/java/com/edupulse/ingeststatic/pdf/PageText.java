package com.edupulse.ingeststatic.pdf;

/**
 * Text extracted from a single PDF page.
 *
 * @param pageNumber 1-based page number
 * @param text       extracted text, paragraphs separated by a blank line
 */
public record PageText(int pageNumber, String text) {
}
