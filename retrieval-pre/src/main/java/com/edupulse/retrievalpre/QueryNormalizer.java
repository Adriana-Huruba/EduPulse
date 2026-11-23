package com.edupulse.retrievalpre;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
class QueryNormalizer {

    static final int MAX_LENGTH = 4096;

    private static final Pattern POLITE_PREFIX = Pattern.compile(
            "(?:please|kindly|could\\h+you|would\\h+you|can\\h+you)"
                    + "(?:[\\h,]+|(?=$))", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_CONTROLS = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

    String normalize(String query) {
        if (query == null || query.length() > MAX_LENGTH || query.indexOf('\0') >= 0) {
            throw new InvalidQuery("q is required, must be at most " + MAX_LENGTH
                    + " characters, and must not contain a null character.");
        }

        int end = query.length();
        while (end > 0 && (Character.isWhitespace(query.charAt(end - 1)) || query.charAt(end - 1) == '?')) {
            end--;
        }

        // Keep offsets into the same string when stripping successive prefixes.
        Matcher prefix = POLITE_PREFIX.matcher(query);
        int start = 0;
        while (start < end) {
            if (Character.isWhitespace(query.charAt(start))) {
                start++;
            } else if (prefix.region(start, end).lookingAt()) {
                start = prefix.end();
            } else {
                return query.substring(start, end);
            }
        }
        throw new InvalidQuery("q must contain searchable text after normalization.");
    }

    static String logValue(String original) {
        return LINE_CONTROLS.matcher(original).replaceAll(match ->
                Matcher.quoteReplacement("\\u%04x".formatted((int) match.group().charAt(0))));
    }

    static final class InvalidQuery extends IllegalArgumentException {
        InvalidQuery(String message) {
            super(message);
        }
    }
}
