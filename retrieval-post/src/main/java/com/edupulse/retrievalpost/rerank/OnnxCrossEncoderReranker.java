package com.edupulse.retrievalpost.rerank;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.edupulse.retrievalpost.config.RerankProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local in-process ONNX Runtime cross-encoder reranker.
 *
 * <p>Lazily loads the pinned ONNX model and WordPiece vocabulary on first scoring request so
 * service startup is never blocked, and normalizes raw logits via sigmoid into (0, 1).
 */
@Component
public class OnnxCrossEncoderReranker implements Reranker, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(OnnxCrossEncoderReranker.class);

	private final RerankProperties properties;
	private final PinnedModelManager modelManager;

	private volatile LoadedModel loadedModel;

	public OnnxCrossEncoderReranker(RerankProperties properties, PinnedModelManager modelManager) {
		this.properties = properties;
		this.modelManager = modelManager;
	}

	@Override
	public double[] score(String query, List<String> passages) {
		if (passages == null || passages.isEmpty()) {
			return new double[0];
		}
		LoadedModel model = ensureLoaded();
		int batchSize = passages.size();
		WordPieceTokenizer.EncodedPair[] encoded = new WordPieceTokenizer.EncodedPair[batchSize];
		int maxLen = 0;
		for (int i = 0; i < batchSize; i++) {
			encoded[i] = model.tokenizer().encodePair(query, passages.get(i), properties.getMaxSequenceLength());
			maxLen = Math.max(maxLen, encoded[i].inputIds().length);
		}

		long[][] inputIds = new long[batchSize][maxLen];
		long[][] attentionMask = new long[batchSize][maxLen];
		long[][] tokenTypeIds = new long[batchSize][maxLen];
		long padId = model.tokenizer().getPadId();

		for (int i = 0; i < batchSize; i++) {
			int len = encoded[i].inputIds().length;
			System.arraycopy(encoded[i].inputIds(), 0, inputIds[i], 0, len);
			System.arraycopy(encoded[i].attentionMask(), 0, attentionMask[i], 0, len);
			System.arraycopy(encoded[i].tokenTypeIds(), 0, tokenTypeIds[i], 0, len);
			for (int j = len; j < maxLen; j++) {
				inputIds[i][j] = padId;
			}
		}

		try (OnnxTensor idsTensor = OnnxTensor.createTensor(model.env(), inputIds);
				OnnxTensor maskTensor = OnnxTensor.createTensor(model.env(), attentionMask);
				OnnxTensor typeTensor = OnnxTensor.createTensor(model.env(), tokenTypeIds)) {

			Map<String, OnnxTensor> inputs = new HashMap<>(3);
			Set<String> expectedInputs = model.session().getInputNames();
			inputs.put("input_ids", idsTensor);
			if (expectedInputs.contains("attention_mask")) {
				inputs.put("attention_mask", maskTensor);
			}
			if (expectedInputs.contains("token_type_ids")) {
				inputs.put("token_type_ids", typeTensor);
			}

			try (OrtSession.Result result = model.session().run(inputs)) {
				Object raw = result.get(0).getValue();
				return extractSigmoidScores(raw, batchSize);
			}
		}
		catch (OrtException ex) {
			throw new RerankerException("ONNX cross-encoder inference failed for model " + getModelDescriptor(), ex);
		}
	}

	@Override
	public String getModelDescriptor() {
		return properties.pinnedDescriptor();
	}

	private LoadedModel ensureLoaded() {
		LoadedModel current = this.loadedModel;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (this.loadedModel != null) {
				return this.loadedModel;
			}
			long startNanos = System.nanoTime();
			PinnedModelManager.ModelArtifacts artifacts = modelManager.resolveArtifacts();
			try {
				WordPieceTokenizer tokenizer = WordPieceTokenizer.fromVocabFile(artifacts.vocabPath());
				OrtEnvironment env = OrtEnvironment.getEnvironment();
				OrtSession.SessionOptions options = new OrtSession.SessionOptions();
				options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
				OrtSession session = env.createSession(artifacts.onnxModelPath().toString(), options);
				long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
				log.info("Loaded pinned ONNX cross-encoder {} (vocab={} tokens, inputs={}) in {} ms",
						getModelDescriptor(), tokenizer.getVocabSize(), session.getInputNames(), elapsedMs);
				this.loadedModel = new LoadedModel(env, session, tokenizer);
				return this.loadedModel;
			}
			catch (IOException | OrtException ex) {
				throw new RerankerException("Failed to initialize ONNX cross-encoder " + getModelDescriptor(), ex);
			}
		}
	}

	private static double[] extractSigmoidScores(Object raw, int batchSize) {
		double[] scores = new double[batchSize];
		if (raw instanceof float[][] matrix) {
			for (int i = 0; i < batchSize; i++) {
				float logit = matrix[i].length == 1 ? matrix[i][0] : matrix[i][matrix[i].length - 1];
				scores[i] = sigmoid(logit);
			}
			return scores;
		}
		if (raw instanceof float[] vector) {
			for (int i = 0; i < batchSize; i++) {
				scores[i] = sigmoid(vector[i]);
			}
			return scores;
		}
		throw new RerankerException("Unexpected ONNX output tensor type: " + (raw == null ? "null" : raw.getClass()));
	}

	static double sigmoid(double logit) {
		if (logit >= 0) {
			double z = Math.exp(-logit);
			return 1.0 / (1.0 + z);
		}
		double z = Math.exp(logit);
		return z / (1.0 + z);
	}

	@PreDestroy
	@Override
	public void close() {
		LoadedModel current = this.loadedModel;
		if (current != null) {
			try {
				current.session().close();
			}
			catch (OrtException ex) {
				log.warn("Error closing ONNX reranker session", ex);
			}
			this.loadedModel = null;
		}
	}

	private record LoadedModel(OrtEnvironment env, OrtSession session, WordPieceTokenizer tokenizer) {
	}
}
