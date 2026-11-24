package com.edupulse.retrievalpost.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Story 4, Task 2 (neighbor stitching by {@code chunk_index}).
 */
@Validated
@ConfigurationProperties(prefix = "app.stitch")
public class StitchProperties {

	private boolean enabled = true;

	@Min(0)
	@Max(5)
	private int windowBefore = 1;

	@Min(0)
	@Max(5)
	private int windowAfter = 1;

	@Min(50)
	@Max(32000)
	private int maxTokenBudget = 2000;

	@Min(1)
	@Max(200)
	private int minOverlapChars = 15;

	@Min(20)
	@Max(2000)
	private int maxOverlapChars = 400;

	@Pattern(regexp = "^[a-zA-Z0-9_.]+$", message = "vectorTableName must be a valid SQL identifier")
	private String vectorTableName = "vector_store";

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getWindowBefore() {
		return windowBefore;
	}

	public void setWindowBefore(int windowBefore) {
		this.windowBefore = windowBefore;
	}

	public int getWindowAfter() {
		return windowAfter;
	}

	public void setWindowAfter(int windowAfter) {
		this.windowAfter = windowAfter;
	}

	public int getMaxTokenBudget() {
		return maxTokenBudget;
	}

	public void setMaxTokenBudget(int maxTokenBudget) {
		this.maxTokenBudget = maxTokenBudget;
	}

	public int getMinOverlapChars() {
		return minOverlapChars;
	}

	public void setMinOverlapChars(int minOverlapChars) {
		this.minOverlapChars = minOverlapChars;
	}

	public int getMaxOverlapChars() {
		return maxOverlapChars;
	}

	public void setMaxOverlapChars(int maxOverlapChars) {
		this.maxOverlapChars = maxOverlapChars;
	}

	public String getVectorTableName() {
		return vectorTableName;
	}

	public void setVectorTableName(String vectorTableName) {
		this.vectorTableName = vectorTableName;
	}
}
