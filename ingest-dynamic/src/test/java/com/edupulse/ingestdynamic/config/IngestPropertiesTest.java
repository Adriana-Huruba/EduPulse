package com.edupulse.ingestdynamic.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class IngestPropertiesTest {

	private IngestProperties bind(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values))
				.bind("app.ingest", IngestProperties.class)
				.get();
	}

	@Test
	void bindsConfiguredValues() {
		IngestProperties props = bind(Map.of(
				"app.ingest.allowed-domains", "docs.spring.io,example.org",
				"app.ingest.max-bytes", "2048",
				"app.ingest.timeout-seconds", "5",
				"app.ingest.chunk-size", "500",
				"app.ingest.chunk-overlap", "50"));

		assertThat(props.allowedDomains()).containsExactly("docs.spring.io", "example.org");
		assertThat(props.maxBytes()).isEqualTo(2048);
		assertThat(props.timeoutSeconds()).isEqualTo(5);
		assertThat(props.chunkSize()).isEqualTo(500);
		assertThat(props.chunkOverlap()).isEqualTo(50);
		assertThat(props.blockPrivateNetworks()).isTrue();
	}

	@Test
	void emptyAllowlistBindsToEmptyList() {
		IngestProperties props = bind(Map.of("app.ingest.allowed-domains", ""));

		assertThat(props.allowedDomains()).isEmpty();
	}

	@Test
	void overlapNotSmallerThanChunkSizeIsRejected() {
		assertThatThrownBy(() -> bind(Map.of(
				"app.ingest.chunk-size", "100",
				"app.ingest.chunk-overlap", "100")))
				.isInstanceOf(BindException.class)
				.hasRootCauseMessage(
						"app.ingest.chunk-overlap (100) must be smaller than app.ingest.chunk-size (100)");
	}

}
