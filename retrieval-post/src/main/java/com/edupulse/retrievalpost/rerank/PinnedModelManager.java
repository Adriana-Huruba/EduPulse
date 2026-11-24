package com.edupulse.retrievalpost.rerank;

import com.edupulse.retrievalpost.config.RerankProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ensures the pinned cross-encoder ONNX model and vocabulary file are available locally,
 * downloading from the pinned HuggingFace commit SHA if missing and verifying the SHA-256 digest.
 */
@Component
public class PinnedModelManager {

	private static final Logger log = LoggerFactory.getLogger(PinnedModelManager.class);
	private static final String HF_RESOLVE_TEMPLATE = "https://huggingface.co/%s/resolve/%s/%s";

	private final RerankProperties properties;
	private final HttpClient httpClient;

	public PinnedModelManager(RerankProperties properties) {
		this.properties = properties;
		this.httpClient = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(Math.min(15, properties.getDownloadTimeoutSeconds())))
				.build();
	}

	public ModelArtifacts resolveArtifacts() {
		Path revisionDir = Path.of(properties.getModelCacheDir())
				.resolve(properties.getModelId().replace('/', '_'))
				.resolve(properties.getModelRevision());

		Path onnxPath = revisionDir.resolve(Path.of(properties.getOnnxFile()).getFileName());
		Path vocabPath = revisionDir.resolve(Path.of(properties.getVocabFile()).getFileName());

		ensureFile(onnxPath, properties.getOnnxFile(), properties.getOnnxSha256());
		ensureFile(vocabPath, properties.getVocabFile(), null);

		return new ModelArtifacts(onnxPath, vocabPath);
	}

	private void ensureFile(Path localPath, String remoteRelativePath, String expectedSha256) {
		if (Files.exists(localPath)) {
			if (expectedSha256 != null && !expectedSha256.isBlank()) {
				verifyChecksum(localPath, expectedSha256);
			}
			log.debug("Using cached reranker artifact: {}", localPath);
			return;
		}
		if (!properties.isAutoDownload()) {
			throw new RerankerException(
					"Pinned reranker artifact missing at " + localPath + " and app.rerank.auto-download=false");
		}
		downloadFile(localPath, remoteRelativePath, expectedSha256);
	}

	private void downloadFile(Path destination, String remoteRelativePath, String expectedSha256) {
		String url = HF_RESOLVE_TEMPLATE.formatted(
				properties.getModelId(),
				properties.getModelRevision(),
				remoteRelativePath);
		log.info("Downloading pinned reranker artifact from {} to {}", url, destination);

		try {
			Files.createDirectories(destination.getParent());
			Path tempFile = destination.resolveSibling(destination.getFileName() + ".part");

			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(properties.getDownloadTimeoutSeconds()))
					.GET()
					.build();

			HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				Files.deleteIfExists(tempFile);
				throw new RerankerException(
						"Failed to download reranker artifact " + url + " (HTTP " + response.statusCode() + ")");
			}

			if (expectedSha256 != null && !expectedSha256.isBlank()) {
				try {
					verifyChecksum(tempFile, expectedSha256);
				}
				catch (RerankerException ex) {
					Files.deleteIfExists(tempFile);
					throw ex;
				}
			}

			try {
				Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex) {
				Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
			}
			log.info("Downloaded and verified pinned reranker artifact: {} ({} bytes)",
					destination, Files.size(destination));
		}
		catch (RerankerException ex) {
			throw ex;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RerankerException("Interrupted while downloading reranker artifact " + url, ex);
		}
		catch (IOException ex) {
			throw new RerankerException("I/O error downloading reranker artifact " + url, ex);
		}
	}

	private void verifyChecksum(Path file, String expectedSha256) {
		String actual = sha256Hex(file);
		if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
			throw new RerankerException(
					"SHA-256 mismatch for " + file + ": expected " + expectedSha256 + " but got " + actual);
		}
	}

	static String sha256Hex(Path file) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[65_536];
			try (InputStream in = Files.newInputStream(file)) {
				int read;
				while ((read = in.read(buffer)) != -1) {
					digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (IOException | NoSuchAlgorithmException ex) {
			throw new RerankerException("Failed to compute SHA-256 for " + file, ex);
		}
	}

	public record ModelArtifacts(Path onnxModelPath, Path vocabPath) {
	}
}
