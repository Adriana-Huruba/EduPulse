package com.edupulse.ingestdynamic.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import com.edupulse.ingestdynamic.config.IngestProperties;
import com.edupulse.ingestdynamic.extract.HtmlExtractor;
import com.edupulse.ingestdynamic.extract.PdfExtractor;

import com.edupulse.ingestdynamic.extract.PlainTextExtractor;
import com.edupulse.ingestdynamic.fetch.HttpSourceFetcher;
import com.edupulse.ingestdynamic.support.StubServer;
import com.edupulse.ingestdynamic.support.TestPdfs;

/**
 * End-to-end tests of ingestion with real HTTP (against a local stub server) and a mocked
 * vector store, so no database or embedding model is needed.
 */
class IngestionServiceTest {

	private static final String HTML_PAGE = """
			<html><head><title>Ingestion guide</title><script>var t = "TRACKING_JS";</script></head>
			<body>
			<nav>Home | Products | NAV_MENU</nav>
			<div class="cookie-banner">We use cookies COOKIE_NOTICE</div>
			<main>
			<h1>Ingestion guide</h1>
			<p>%s</p>
			<p>%s</p>
			<p>%s</p>
			</main>
			<footer>Copyright FOOTER_TEXT</footer>
			</body></html>
			""".formatted("Alpha sentence about fetching pages. ".repeat(6), "Beta sentence about cleaning text. ".repeat(6),
			"Gamma sentence about storing chunks. ".repeat(6));

	private StubServer server;

	private VectorStore vectorStore;

	private IngestionService service;

	@BeforeEach
	void setUp() throws IOException {
		server = StubServer.start();
		vectorStore = mock(VectorStore.class);
		IngestProperties properties = new IngestProperties(List.of(), true, 1_000_000, 5, 300, 50,
				server.url("/api"), server.url("/raw"), 10);
		HttpSourceFetcher fetcher = new HttpSourceFetcher(properties);
		service = new IngestionService(fetcher, new GitHubRepoResolver(fetcher, properties), new HtmlExtractor(),
				new PdfExtractor(), new PlainTextExtractor(), vectorStore, properties);
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@SuppressWarnings("unchecked")
	private List<List<Document>> storedBatches() {
		ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
		verify(vectorStore, org.mockito.Mockito.atLeast(0)).add(captor.capture());
		return captor.getAllValues();
	}

	private List<Document> allStored() {
		List<Document> all = new ArrayList<>();
		storedBatches().forEach(all::addAll);
		return all;
	}

	@Test
	void webPageIsIngestedWithMetadataAndWithoutBoilerplate() {
		server.respond("/guide.html", 200, "text/html; charset=utf-8", HTML_PAGE);
		String url = server.url("/guide.html");

		BatchResult result = service.ingestAll(List.of(new SourceRequest(url, null)));

		assertThat(result.ok()).isEqualTo(1);
		SourceResult source = result.results().get(0);
		assertThat(source.status()).isEqualTo(SourceResult.Status.OK);
		List<Document> stored = allStored();
		assertThat(stored).hasSize(source.chunks()).hasSizeGreaterThan(1);

		for (int i = 0; i < stored.size(); i++) {
			Document chunk = stored.get(i);
			assertThat(chunk.getMetadata())
					.containsEntry(MetadataKeys.DOC_ID, DocIds.forUrl(url))
					.containsEntry(MetadataKeys.VERSION, 1)
					.containsEntry(MetadataKeys.SOURCE, url)
					.containsEntry(MetadataKeys.PAGE, 1)
					.containsEntry(MetadataKeys.CHUNK_INDEX, i)
					.containsEntry(MetadataKeys.SOURCE_URL, url)
					.containsKey(MetadataKeys.CHECKSUM)
					.containsKey(MetadataKeys.FETCH_DATE);
			assertThat(Instant.parse((String) chunk.getMetadata().get(MetadataKeys.FETCH_DATE))).isNotNull();
			assertThat(chunk.getText()).doesNotContain("NAV_MENU", "COOKIE_NOTICE", "FOOTER_TEXT", "TRACKING_JS");
		}
		String allText = String.join(" ", stored.stream().map(Document::getText).toList());
		assertThat(allText).contains("Alpha sentence", "Beta sentence", "Gamma sentence");
	}

	@Test
	void remotePdfIsIngestedWithPageNumbers() throws IOException {
		server.respond("/paper.pdf", 200, "application/pdf",
				TestPdfs.withPages("alpha content on page one", "beta content on page two"));
		String url = server.url("/paper.pdf");

		BatchResult result = service.ingestAll(List.of(new SourceRequest(url, null)));

		assertThat(result.results().get(0).status()).isEqualTo(SourceResult.Status.OK);
		List<Document> stored = allStored();
		assertThat(stored).hasSize(2);
		assertThat(stored.get(0).getMetadata()).containsEntry(MetadataKeys.PAGE, 1);
		assertThat(stored.get(0).getText()).contains("alpha content on page one");
		assertThat(stored.get(1).getMetadata()).containsEntry(MetadataKeys.PAGE, 2);
		assertThat(stored.get(1).getMetadata()).containsEntry(MetadataKeys.CHUNK_INDEX, 1);
		assertThat(stored.get(1).getText()).contains("beta content on page two");
	}

	@Test
	void githubRepositoryDocsAreIngested() {
		server.respond("/api/repos/acme/widgets/git/trees/HEAD", 200, "application/json", """
				{"sha":"abc","truncated":false,"tree":[
				  {"path":"README.md","type":"blob","size":120},
				  {"path":"docs","type":"tree"},
				  {"path":"docs/guide.md","type":"blob","size":90},
				  {"path":"docs/logo.png","type":"blob","size":50},
				  {"path":"src/Main.java","type":"blob","size":40}
				]}
				""");
		server.respond("/raw/acme/widgets/HEAD/README.md", 200, "text/plain; charset=utf-8",
				"# Widgets\n\nWidgets are small reusable components.");
		server.respond("/raw/acme/widgets/HEAD/docs/guide.md", 200, "text/plain; charset=utf-8",
				"# Guide\n\nInstall the widgets with one command.");

		BatchResult result = service.ingestAll(
				List.of(new SourceRequest("https://github.com/acme/widgets", SourceType.GITHUB_REPO)));

		SourceResult source = result.results().get(0);
		assertThat(source.status()).isEqualTo(SourceResult.Status.OK);
		assertThat(source.documents()).isEqualTo(2);
		List<Document> stored = allStored();
		assertThat(stored).extracting(d -> d.getMetadata().get(MetadataKeys.SOURCE))
				.containsExactlyInAnyOrder("https://github.com/acme/widgets/blob/HEAD/README.md",
						"https://github.com/acme/widgets/blob/HEAD/docs/guide.md");
		assertThat(stored).allSatisfy(chunk -> assertThat((String) chunk.getMetadata().get(MetadataKeys.SOURCE_URL))
				.startsWith(server.url("/raw/acme/widgets/HEAD/")));
		assertThat(stored).extracting(Document::getText)
				.anyMatch(t -> t.contains("Widgets are small reusable components."))
				.anyMatch(t -> t.contains("Install the widgets with one command."));
	}

	@Test
	void oneFailedFileOfARepositoryGivesPartial() {
		server.respond("/api/repos/acme/widgets/git/trees/HEAD", 200, "application/json", """
				{"tree":[{"path":"README.md","type":"blob","size":10},{"path":"docs/guide.md","type":"blob","size":10}]}
				""");
		server.respond("/raw/acme/widgets/HEAD/README.md", 200, "text/plain", "Readme text.");
		server.respond("/raw/acme/widgets/HEAD/docs/guide.md", 404, "text/plain", "missing");

		SourceResult source = service.ingestAll(
				List.of(new SourceRequest("https://github.com/acme/widgets", SourceType.GITHUB_REPO)))
				.results().get(0);

		assertThat(source.status()).isEqualTo(SourceResult.Status.PARTIAL);
		assertThat(source.documents()).isEqualTo(1);
		assertThat(source.error()).contains("docs/guide.md");
	}

	@Test
	void failedSourcesAreLoggedAndDoNotStopTheBatch() throws IOException {
		server.respond("/missing", 404, "text/plain", "not found");
		server.respond("/guide.html", 200, "text/html", HTML_PAGE);
		server.respond("/data.bin", 200, "application/octet-stream", new byte[] { 1, 2, 3, 4 });
		server.respond("/empty.html", 200, "text/html", "<html><body><nav>only menu</nav></body></html>");
		int closedPort = StubServer.unusedPort();

		BatchResult result = service.ingestAll(List.of(
				new SourceRequest(server.url("/missing"), null),
				new SourceRequest(server.url("/guide.html"), null),
				new SourceRequest("http://127.0.0.1:" + closedPort + "/down", null),
				new SourceRequest("not a url", null),
				new SourceRequest(server.url("/data.bin"), null),
				new SourceRequest(server.url("/empty.html"), null)));

		assertThat(result.total()).isEqualTo(6);
		assertThat(result.ok()).isEqualTo(1);
		assertThat(result.failed()).isEqualTo(5);
		assertThat(result.results()).extracting(SourceResult::status).containsExactly(
				SourceResult.Status.FAILED, SourceResult.Status.OK, SourceResult.Status.FAILED,
				SourceResult.Status.FAILED, SourceResult.Status.FAILED, SourceResult.Status.FAILED);
		assertThat(result.results().get(0).error()).contains("404");
		assertThat(result.results().get(4).error()).contains("Unsupported content type");
		assertThat(result.results().get(5).error()).contains("No readable text");
		// Only the good source reached the vector store.
		verify(vectorStore, times(1)).add(anyList());
	}

	@Test
	void failedSourceWritesNothingToTheVectorStore() {
		server.respond("/missing", 404, "text/plain", "not found");

		service.ingestAll(List.of(new SourceRequest(server.url("/missing"), null)));

		verifyNoInteractions(vectorStore);
	}

	@Test
	void ingestingTheSameUrlAgainProducesTheSameChunkIds() {
		server.respond("/guide.html", 200, "text/html", HTML_PAGE);
		SourceRequest request = new SourceRequest(server.url("/guide.html"), null);

		service.ingestAll(List.of(request));
		service.ingestAll(List.of(request));

		List<List<Document>> batches = storedBatches();
		assertThat(batches).hasSize(2);
		assertThat(batches.get(0)).extracting(Document::getId)
				.containsExactlyElementsOf(batches.get(1).stream().map(Document::getId).toList())
				.doesNotHaveDuplicates();
	}

	@Test
	void explicitTypeOverridesContentTypeDetection() {
		server.respond("/notes", 200, "application/octet-stream", "Plain notes about ingestion.");

		BatchResult result = service.ingestAll(List.of(new SourceRequest(server.url("/notes"), SourceType.TEXT)));

		assertThat(result.ok()).isEqualTo(1);
		assertThat(allStored()).extracting(Document::getText).containsExactly("Plain notes about ingestion.");
	}

}
