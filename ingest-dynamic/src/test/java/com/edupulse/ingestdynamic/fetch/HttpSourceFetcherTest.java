package com.edupulse.ingestdynamic.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.edupulse.ingestdynamic.config.IngestProperties;
import com.edupulse.ingestdynamic.support.StubServer;

class HttpSourceFetcherTest {

	private StubServer server;

	private HttpSourceFetcher fetcher;

	@BeforeEach
	void setUp() throws IOException {
		server = StubServer.start();
		fetcher = new HttpSourceFetcher(new IngestProperties(List.of(), true, 100, 5, 800, 100,
				"https://api.github.com", "https://raw.githubusercontent.com", 25));
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void returnsBodyContentTypeAndFetchTime() {
		server.respond("/ok", 200, "text/plain", "hello");

		FetchedResource resource = fetcher.fetch(server.url("/ok"));

		assertThat(new String(resource.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
		assertThat(resource.contentType()).startsWith("text/plain");
		assertThat(resource.url()).isEqualTo(server.url("/ok"));
		assertThat(resource.fetchedAt()).isNotNull();
	}

	@Test
	void followsRedirectsAndReportsTheFinalUrl() {
		server.respond("/final", 200, "text/plain", "moved");
		server.redirect("/old", "/final");

		FetchedResource resource = fetcher.fetch(server.url("/old"));

		assertThat(resource.url()).isEqualTo(server.url("/final"));
		assertThat(new String(resource.body(), StandardCharsets.UTF_8)).isEqualTo("moved");
	}

	@Test
	void redirectLoopFails() {
		server.redirect("/loop", "/loop");

		assertThatThrownBy(() -> fetcher.fetch(server.url("/loop")))
				.isInstanceOf(FetchException.class)
				.hasMessageContaining("Too many redirects");
	}

	@Test
	void errorStatusFails() {
		server.respond("/missing", 404, "text/plain", "nope");

		assertThatThrownBy(() -> fetcher.fetch(server.url("/missing")))
				.isInstanceOf(FetchException.class)
				.hasMessageContaining("HTTP 404");
	}

	@Test
	void responseLargerThanTheLimitFails() {
		server.respond("/big", 200, "text/plain", "x".repeat(500));

		assertThatThrownBy(() -> fetcher.fetch(server.url("/big")))
				.isInstanceOf(FetchException.class)
				.hasMessageContaining("larger than the limit");
	}

	@Test
	void nonHttpSchemeFails() {
		assertThatThrownBy(() -> fetcher.fetch("file:///etc/passwd"))
				.isInstanceOf(FetchException.class)
				.hasMessageContaining("Unsupported URL scheme");
	}

	@Test
	void unreachableHostFails() throws IOException {
		int port = StubServer.unusedPort();

		assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1:" + port + "/x"))
				.isInstanceOf(FetchException.class);
	}

}
