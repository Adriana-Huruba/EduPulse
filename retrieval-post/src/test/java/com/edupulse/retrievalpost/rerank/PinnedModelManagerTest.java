package com.edupulse.retrievalpost.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edupulse.retrievalpost.config.RerankProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinnedModelManagerTest {

	@TempDir
	Path tempDir;

	@Test
	void resolvesCachedArtifactsWhenSha256Matches() throws IOException {
		RerankProperties props = new RerankProperties();
		props.setModelCacheDir(tempDir.toString());
		props.setModelId("org/test-model");
		props.setModelRevision("abc123");
		props.setOnnxFile("onnx/model.onnx");
		props.setVocabFile("vocab.txt");
		props.setAutoDownload(false);

		Path revDir = tempDir.resolve("org_test-model").resolve("abc123");
		Files.createDirectories(revDir);
		Path onnx = revDir.resolve("model.onnx");
		Path vocab = revDir.resolve("vocab.txt");
		Files.writeString(onnx, "fake-onnx-bytes", StandardCharsets.UTF_8);
		Files.writeString(vocab, "[PAD]\n[UNK]\n[CLS]\n[SEP]\n", StandardCharsets.UTF_8);

		props.setOnnxSha256(PinnedModelManager.sha256Hex(onnx));

		PinnedModelManager manager = new PinnedModelManager(props);
		PinnedModelManager.ModelArtifacts artifacts = manager.resolveArtifacts();

		assertThat(artifacts.onnxModelPath()).isEqualTo(onnx);
		assertThat(artifacts.vocabPath()).isEqualTo(vocab);
	}

	@Test
	void rejectsCorruptedModelWithSha256Mismatch() throws IOException {
		RerankProperties props = new RerankProperties();
		props.setModelCacheDir(tempDir.toString());
		props.setModelId("org/test-model");
		props.setModelRevision("abc123");
		props.setOnnxFile("onnx/model.onnx");
		props.setVocabFile("vocab.txt");
		props.setOnnxSha256("0000000000000000000000000000000000000000000000000000000000000000");
		props.setAutoDownload(false);

		Path revDir = tempDir.resolve("org_test-model").resolve("abc123");
		Files.createDirectories(revDir);
		Files.writeString(revDir.resolve("model.onnx"), "tampered-bytes", StandardCharsets.UTF_8);
		Files.writeString(revDir.resolve("vocab.txt"), "[PAD]\n", StandardCharsets.UTF_8);

		PinnedModelManager manager = new PinnedModelManager(props);
		assertThatThrownBy(manager::resolveArtifacts)
				.isInstanceOf(RerankerException.class)
				.hasMessageContaining("SHA-256 mismatch");
	}

	@Test
	void failsFastWhenArtifactMissingAndAutoDownloadDisabled() {
		RerankProperties props = new RerankProperties();
		props.setModelCacheDir(tempDir.toString());
		props.setAutoDownload(false);

		PinnedModelManager manager = new PinnedModelManager(props);
		assertThatThrownBy(manager::resolveArtifacts)
				.isInstanceOf(RerankerException.class)
				.hasMessageContaining("auto-download=false");
	}
}
