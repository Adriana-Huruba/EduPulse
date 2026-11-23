package com.edupulse.ingestdynamic.fetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.edupulse.ingestdynamic.config.IngestProperties;

/**
 * Downloads resources with the JDK HTTP client.
 *
 * Redirects are followed by hand (never automatically) so that Task 4 can validate every
 * hop. The configured timeout and maximum size are applied here; the allowlist, SSRF and
 * content-type controls are Task 4.
 */
@Component
public class HttpSourceFetcher implements SourceFetcher {

	private static final Logger log = LoggerFactory.getLogger(HttpSourceFetcher.class);

	static final int MAX_REDIRECTS = 5;

	private final HttpClient client;

	private final Duration timeout;

	private final long maxBytes;

	public HttpSourceFetcher(IngestProperties properties) {
		this.timeout = Duration.ofSeconds(properties.timeoutSeconds());
		this.maxBytes = properties.maxBytes();
		this.client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(timeout)
				.build();
	}

	@Override
	public FetchedResource fetch(String url) {
		URI uri = parse(url);
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			final URI current = uri;
			requireHttp(current);
			HttpRequest request = HttpRequest.newBuilder(uri)
					.timeout(timeout)
					.header("User-Agent", "EduPulse-Ingest/1.0")
					.header("Accept", "text/html,application/pdf,text/plain,text/markdown;q=0.9,*/*;q=0.5")
					.GET()
					.build();
			HttpResponse<InputStream> response = send(request, uri);
			try (InputStream body = response.body()) {
				int status = response.statusCode();
				if (status >= 300 && status < 400) {
					String location = response.headers().firstValue("Location")
							.orElseThrow(() -> new FetchException("Redirect without Location header from " + current));
					uri = uri.resolve(location);
					log.debug("Following redirect to {}", uri);
					continue;
				}
				if (status < 200 || status >= 300) {
					throw new FetchException("HTTP " + status + " from " + uri);
				}
				byte[] bytes = readLimited(body, uri);
				String contentType = response.headers().firstValue("Content-Type").orElse("");
				return new FetchedResource(uri.toString(), bytes, contentType, Instant.now());
			}
			catch (IOException ex) {
				throw new FetchException("Failed to read response from " + uri + ": " + ex.getMessage(), ex);
			}
		}
		throw new FetchException("Too many redirects (more than " + MAX_REDIRECTS + ") for " + url);
	}

	private HttpResponse<InputStream> send(HttpRequest request, URI uri) {
		try {
			return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new FetchException("Interrupted while fetching " + uri, ex);
		}
		catch (IOException ex) {
			String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			throw new FetchException("Could not fetch " + uri + ": " + reason, ex);
		}
	}

	private byte[] readLimited(InputStream in, URI uri) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		long total = 0;
		int read;
		while ((read = in.read(buffer)) != -1) {
			total += read;
			if (total > maxBytes) {
				throw new FetchException("Response from " + uri + " is larger than the limit of " + maxBytes + " bytes");
			}
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	private static URI parse(String url) {
		try {
			return new URI(url.trim());
		}
		catch (Exception ex) {
			throw new FetchException("Invalid URL: " + url, ex);
		}
	}

	private static void requireHttp(URI uri) {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new FetchException("Unsupported URL scheme '" + scheme + "' in " + uri);
		}
		if (uri.getHost() == null) {
			throw new FetchException("URL has no host: " + uri);
		}
	}

}
