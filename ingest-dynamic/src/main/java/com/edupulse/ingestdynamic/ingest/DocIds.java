package com.edupulse.ingestdynamic.ingest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Stable identifiers and checksums. */
public final class DocIds {

	private DocIds() {
	}

	/**
	 * A stable document id for a URL: the same URL always gives the same id, ignoring case
	 * of scheme and host, default ports and fragments.
	 */
	public static String forUrl(String url) {
		return sha256(normalizeUrl(url).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
	}

	/**
	 * A deterministic UUID for a chunk (the vector store's id column is a UUID), so
	 * re-ingesting the same content overwrites chunks instead of duplicating them.
	 */
	public static String chunkId(String docId, int version, int chunkIndex) {
		return UUID.nameUUIDFromBytes((docId + ":" + version + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8))
				.toString();
	}

	public static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	static String normalizeUrl(String url) {
		URI uri = URI.create(url.trim());
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		int port = uri.getPort();
		boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80)
				|| (scheme.equals("https") && port == 443);
		String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
		String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
		return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path + query;
	}

}
