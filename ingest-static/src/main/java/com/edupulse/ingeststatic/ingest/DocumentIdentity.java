package com.edupulse.ingeststatic.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Deterministic identifiers.
 *
 * <ul>
 *   <li>{@code doc_id} depends only on the normalised source path, so it stays the same when the
 *       file content changes. This is what makes re-ingestion and later updates/deletes possible.</li>
 *   <li>Chunk ids depend on {@code doc_id}, version and chunk index. Re-ingesting the same content
 *       produces the same ids, and the vector store upserts on id, so it can never duplicate.</li>
 *   <li>{@code checksum} is the SHA-256 of the file bytes and detects content changes.</li>
 * </ul>
 *
 * Ids are name-based (type 3) UUIDs because the pgvector table uses a {@code uuid} primary key.
 */
public final class DocumentIdentity {

    private static final String NAMESPACE = "edupulse:ingest-static:";

    private DocumentIdentity() {
    }

    /** Normalises a source path: forward slashes, no leading "./" or "/", no surrounding blanks. */
    public static String normalizeSource(String source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        String normalized = source.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        return normalized;
    }

    public static String docId(String source) {
        return nameUuid(NAMESPACE + "doc:" + normalizeSource(source));
    }

    public static String chunkId(String docId, int version, int chunkIndex) {
        return nameUuid(NAMESPACE + "chunk:" + docId + ":v" + version + ":" + chunkIndex);
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String nameUuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
