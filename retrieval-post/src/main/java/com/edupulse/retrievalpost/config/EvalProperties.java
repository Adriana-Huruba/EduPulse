package com.edupulse.retrievalpost.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Story 4, Task 4 (evaluation harness and pre-merge regression gate).
 */
@Validated
@ConfigurationProperties(prefix = "app.eval")
public class EvalProperties {

	@NotBlank
	private String goldenSetPath = "/eval/golden-set.json";

	@NotBlank
	private String corpusPath = "/eval/corpus/compliance-chunks.json";

	@NotBlank
	private String baselineReportPath = "eval/reports/baseline-report.json";

	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double minRecallAtK = 0.90;

	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double minMrrAtK = 0.85;

	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double minFaithfulnessRate = 0.90;

	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double minRefusalAccuracy = 1.00;

	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double minSupportRatio = 0.70;

	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double maxMetricDrop = 0.02;

	public String getGoldenSetPath() {
		return goldenSetPath;
	}

	public void setGoldenSetPath(String goldenSetPath) {
		this.goldenSetPath = goldenSetPath;
	}

	public String getCorpusPath() {
		return corpusPath;
	}

	public void setCorpusPath(String corpusPath) {
		this.corpusPath = corpusPath;
	}

	public String getBaselineReportPath() {
		return baselineReportPath;
	}

	public void setBaselineReportPath(String baselineReportPath) {
		this.baselineReportPath = baselineReportPath;
	}

	public double getMinRecallAtK() {
		return minRecallAtK;
	}

	public void setMinRecallAtK(double minRecallAtK) {
		this.minRecallAtK = minRecallAtK;
	}

	public double getMinMrrAtK() {
		return minMrrAtK;
	}

	public void setMinMrrAtK(double minMrrAtK) {
		this.minMrrAtK = minMrrAtK;
	}

	public double getMinFaithfulnessRate() {
		return minFaithfulnessRate;
	}

	public void setMinFaithfulnessRate(double minFaithfulnessRate) {
		this.minFaithfulnessRate = minFaithfulnessRate;
	}

	public double getMinRefusalAccuracy() {
		return minRefusalAccuracy;
	}

	public void setMinRefusalAccuracy(double minRefusalAccuracy) {
		this.minRefusalAccuracy = minRefusalAccuracy;
	}

	public double getMinSupportRatio() {
		return minSupportRatio;
	}

	public void setMinSupportRatio(double minSupportRatio) {
		this.minSupportRatio = minSupportRatio;
	}

	public double getMaxMetricDrop() {
		return maxMetricDrop;
	}

	public void setMaxMetricDrop(double maxMetricDrop) {
		this.maxMetricDrop = maxMetricDrop;
	}
}
