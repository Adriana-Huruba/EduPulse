package com.edupulse.retrievalpost.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Story 4, Task 1 (cross-encoder reranker).
 *
 * <p>Model identity and revision SHA are pinned so inference results and latency
 * remain reproducible across environments.
 */
@Validated
@ConfigurationProperties(prefix = "app.rerank")
public class RerankProperties {

	private boolean enabled = true;

	@Min(1)
	@Max(200)
	private int topN = 20;

	@Min(1)
	@Max(100)
	private int topK = 5;

	@NotBlank
	private String modelId = "cross-encoder/ms-marco-MiniLM-L6-v2";

	@NotBlank
	private String modelRevision = "233902d25c440f23af6f7d6e94d2946bac0bee0a";

	@NotBlank
	private String onnxFile = "onnx/model.onnx";

	private String onnxSha256 = "5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a";

	@NotBlank
	private String vocabFile = "vocab.txt";

	@NotBlank
	private String modelCacheDir = "models/reranker";

	private boolean autoDownload = true;

	@Min(32)
	@Max(512)
	private int maxSequenceLength = 512;

	@Min(5)
	@Max(600)
	private int downloadTimeoutSeconds = 60;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getTopN() {
		return topN;
	}

	public void setTopN(int topN) {
		this.topN = topN;
	}

	public int getTopK() {
		return topK;
	}

	public void setTopK(int topK) {
		this.topK = topK;
	}

	public String getModelId() {
		return modelId;
	}

	public void setModelId(String modelId) {
		this.modelId = modelId;
	}

	public String getModelRevision() {
		return modelRevision;
	}

	public void setModelRevision(String modelRevision) {
		this.modelRevision = modelRevision;
	}

	public String getOnnxFile() {
		return onnxFile;
	}

	public void setOnnxFile(String onnxFile) {
		this.onnxFile = onnxFile;
	}

	public String getOnnxSha256() {
		return onnxSha256;
	}

	public void setOnnxSha256(String onnxSha256) {
		this.onnxSha256 = onnxSha256;
	}

	public String getVocabFile() {
		return vocabFile;
	}

	public void setVocabFile(String vocabFile) {
		this.vocabFile = vocabFile;
	}

	public String getModelCacheDir() {
		return modelCacheDir;
	}

	public void setModelCacheDir(String modelCacheDir) {
		this.modelCacheDir = modelCacheDir;
	}

	public boolean isAutoDownload() {
		return autoDownload;
	}

	public void setAutoDownload(boolean autoDownload) {
		this.autoDownload = autoDownload;
	}

	public int getMaxSequenceLength() {
		return maxSequenceLength;
	}

	public void setMaxSequenceLength(int maxSequenceLength) {
		this.maxSequenceLength = maxSequenceLength;
	}

	public int getDownloadTimeoutSeconds() {
		return downloadTimeoutSeconds;
	}

	public void setDownloadTimeoutSeconds(int downloadTimeoutSeconds) {
		this.downloadTimeoutSeconds = downloadTimeoutSeconds;
	}

	public String pinnedDescriptor() {
		return modelId + "@" + modelRevision + " (" + onnxFile + ")";
	}
}
