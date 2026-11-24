package com.edupulse.retrievalpost.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Story 4, Task 3 (citation headers, grounded prompt,
 * and similarity-threshold "I don't know." fallback).
 */
@Validated
@ConfigurationProperties(prefix = "app.grounding")
public class GroundingProperties {

	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double similarityThreshold = 0.5;

	@NotBlank
	private String refusalMessage = "I don't know.";

	@NotBlank
	private String chatModel = "llama3.2";

	private boolean chatEnabled = true;

	public double getSimilarityThreshold() {
		return similarityThreshold;
	}

	public void setSimilarityThreshold(double similarityThreshold) {
		this.similarityThreshold = similarityThreshold;
	}

	public String getRefusalMessage() {
		return refusalMessage;
	}

	public void setRefusalMessage(String refusalMessage) {
		this.refusalMessage = refusalMessage;
	}

	public String getChatModel() {
		return chatModel;
	}

	public void setChatModel(String chatModel) {
		this.chatModel = chatModel;
	}

	public boolean isChatEnabled() {
		return chatEnabled;
	}

	public void setChatEnabled(boolean chatEnabled) {
		this.chatEnabled = chatEnabled;
	}
}
