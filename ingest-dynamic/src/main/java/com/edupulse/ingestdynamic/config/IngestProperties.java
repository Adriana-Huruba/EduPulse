package com.edupulse.ingestdynamic.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Settings for dynamic ingestion, bound from {@code app.ingest.*}.
 *
 * Allowlist and private-network blocking are only bound here. Enforcing them is
 * Story 2, Task 4. Size and timeout are already applied by the fetcher.
 *
 * @param chunkSize approximate maximum chunk length in characters
 * @param chunkOverlap characters repeated at the start of the next chunk
 * @param githubApiBase base URL of the GitHub REST API (overridable for tests)
 * @param githubRawBase base URL for raw file downloads (overridable for tests)
 * @param repoMaxFiles maximum number of documentation files ingested per repository
 */
@Validated
@ConfigurationProperties("app.ingest")
public record IngestProperties(
		@DefaultValue List<String> allowedDomains,
		@DefaultValue("true") boolean blockPrivateNetworks,
		@DefaultValue("10485760") @Min(1) long maxBytes,
		@DefaultValue("15") @Min(1) int timeoutSeconds,
		@DefaultValue("800") @Min(1) int chunkSize,
		@DefaultValue("100") @Min(0) int chunkOverlap,
		@DefaultValue("https://api.github.com") @NotBlank String githubApiBase,
		@DefaultValue("https://raw.githubusercontent.com") @NotBlank String githubRawBase,
		@DefaultValue("25") @Min(1) int repoMaxFiles) {

	public IngestProperties {
		if (chunkOverlap >= chunkSize) {
			throw new IllegalArgumentException(
					"app.ingest.chunk-overlap (" + chunkOverlap + ") must be smaller than app.ingest.chunk-size ("
							+ chunkSize + ")");
		}
	}

}
