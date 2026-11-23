package com.edupulse.ingestdynamic.fetch;

import java.time.Instant;

/**
 * A downloaded resource.
 *
 * @param url the final URL, after any redirects
 * @param body the raw response body
 * @param contentType the Content-Type header, or an empty string if absent
 * @param fetchedAt when the download finished
 */
public record FetchedResource(String url, byte[] body, String contentType, Instant fetchedAt) {
}
