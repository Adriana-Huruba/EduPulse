package com.edupulse.ingestdynamic.ingest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.edupulse.ingestdynamic.chunk.TextChunker;
import com.edupulse.ingestdynamic.config.IngestProperties;
import com.edupulse.ingestdynamic.extract.ExtractedContent;
import com.edupulse.ingestdynamic.extract.HtmlExtractor;
import com.edupulse.ingestdynamic.extract.PdfExtractor;
import com.edupulse.ingestdynamic.extract.PlainTextExtractor;
import com.edupulse.ingestdynamic.fetch.FetchedResource;
import com.edupulse.ingestdynamic.fetch.SourceFetcher;

/**
 * Fetches sources from the internet, extracts clean text, chunks it, attaches metadata and
 * stores the chunks in the vector store. A batch never stops at a failing source: each
 * source is handled on its own and its outcome is reported.
 */
@Service
public class IngestionService {

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	/** Versioning is Story 2, Task 2. Until then every document is version 1. */
	static final int INITIAL_VERSION = 1;

	private final SourceFetcher fetcher;

	private final GitHubRepoResolver repoResolver;

	private final HtmlExtractor htmlExtractor;

	private final PdfExtractor pdfExtractor;

	private final PlainTextExtractor textExtractor;

	private final VectorStore vectorStore;

	private final TextChunker chunker;

	public IngestionService(SourceFetcher fetcher, GitHubRepoResolver repoResolver, HtmlExtractor htmlExtractor,
			PdfExtractor pdfExtractor, PlainTextExtractor textExtractor, VectorStore vectorStore,
			IngestProperties properties) {
		this.fetcher = fetcher;
		this.repoResolver = repoResolver;
		this.htmlExtractor = htmlExtractor;
		this.pdfExtractor = pdfExtractor;
		this.textExtractor = textExtractor;
		this.vectorStore = vectorStore;
		this.chunker = new TextChunker(properties.chunkSize(), properties.chunkOverlap());
	}

	public BatchResult ingestAll(List<SourceRequest> sources) {
		List<SourceResult> results = new ArrayList<>();
		for (SourceRequest source : sources) {
			results.add(ingestOne(source));
		}
		BatchResult batch = BatchResult.of(results);
		log.info("Ingestion batch finished: {} sources, {} ok, {} partial, {} failed", batch.total(), batch.ok(),
				batch.partial(), batch.failed());
		return batch;
	}

	SourceResult ingestOne(SourceRequest source) {
		String url = source.url();
		try {
			if (source.effectiveType() == SourceType.GITHUB_REPO) {
				return ingestRepo(url);
			}
			int chunks = ingestDocument(url, url, source.effectiveType());
			return new SourceResult(url, SourceResult.Status.OK, 1, chunks, null);
		}
		catch (Exception ex) {
			log.error("Ingestion failed for {}: {}", url, ex.getMessage(), ex);
			return SourceResult.failed(url, ex.getMessage());
		}
	}

	private SourceResult ingestRepo(String repoUrl) {
		List<GitHubRepoResolver.RepoFile> files = repoResolver.resolve(repoUrl);
		int documents = 0;
		int chunks = 0;
		List<String> errors = new ArrayList<>();
		for (GitHubRepoResolver.RepoFile file : files) {
			try {
				chunks += ingestDocument(file.rawUrl(), file.htmlUrl(), SourceType.TEXT);
				documents++;
			}
			catch (Exception ex) {
				log.error("Ingestion failed for {} (file of {}): {}", file.path(), repoUrl, ex.getMessage(), ex);
				errors.add(file.path() + ": " + ex.getMessage());
			}
		}
		SourceResult.Status status = errors.isEmpty() ? SourceResult.Status.OK
				: documents == 0 ? SourceResult.Status.FAILED : SourceResult.Status.PARTIAL;
		return new SourceResult(repoUrl, status, documents, chunks, errors.isEmpty() ? null : String.join("; ", errors));
	}

	/**
	 * Fetches one document and stores its chunks.
	 *
	 * @param fetchUrl what to download; also the basis of the stable doc_id
	 * @param source the human-facing origin recorded in the {@code source} metadata
	 * @return the number of chunks stored
	 */
	private int ingestDocument(String fetchUrl, String source, SourceType type) {
		FetchedResource resource = fetcher.fetch(fetchUrl);
		ExtractedContent content = extract(resource, type);
		if (content.isEmpty()) {
			throw new IllegalStateException("No readable text found at " + fetchUrl);
		}

		String docId = DocIds.forUrl(fetchUrl);
		String checksum = DocIds.sha256(resource.body());
		String fetchDate = resource.fetchedAt().toString();

		List<Document> documents = new ArrayList<>();
		int chunkIndex = 0;
		for (ExtractedContent.Section section : content.sections()) {
			for (String text : chunker.chunk(section.text())) {
				Map<String, Object> metadata = new LinkedHashMap<>();
				metadata.put(MetadataKeys.DOC_ID, docId);
				metadata.put(MetadataKeys.VERSION, INITIAL_VERSION);
				metadata.put(MetadataKeys.SOURCE, source);
				metadata.put(MetadataKeys.PAGE, section.page());
				metadata.put(MetadataKeys.CHUNK_INDEX, chunkIndex);
				metadata.put(MetadataKeys.CHECKSUM, checksum);
				metadata.put(MetadataKeys.SOURCE_URL, resource.url());
				metadata.put(MetadataKeys.FETCH_DATE, fetchDate);
				documents.add(Document.builder()
						.id(DocIds.chunkId(docId, INITIAL_VERSION, chunkIndex))
						.text(text)
						.metadata(metadata)
						.build());
				chunkIndex++;
			}
		}

		vectorStore.add(documents);
		log.info("Ingested {} ({} chunks, doc_id {})", resource.url(), documents.size(), docId);
		return documents.size();
	}

	private ExtractedContent extract(FetchedResource resource, SourceType requested) {
		return switch (resolveType(resource, requested)) {
			case PDF -> pdfExtractor.extract(resource.body());
			case HTML -> htmlExtractor.extract(resource.body(), resource.contentType(), resource.url());
			case TEXT -> textExtractor.extract(resource.body(), "");
			default -> throw new IllegalStateException("Unsupported content type '" + resource.contentType()
					+ "' at " + resource.url());
		};
	}

	/** Decides the concrete type from the request, the Content-Type header, the URL and the bytes. */
	static SourceType resolveType(FetchedResource resource, SourceType requested) {
		if (requested == SourceType.PDF || requested == SourceType.HTML || requested == SourceType.TEXT) {
			return requested;
		}
		String contentType = resource.contentType().toLowerCase(Locale.ROOT);
		String path = pathOf(resource.url()).toLowerCase(Locale.ROOT);
		byte[] body = resource.body();
		boolean pdfMagic = body.length >= 5 && new String(body, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-");

		if (contentType.contains("pdf") || pdfMagic) {
			return SourceType.PDF;
		}
		if (contentType.contains("html")) {
			return SourceType.HTML;
		}
		if (contentType.startsWith("text/") || path.endsWith(".md") || path.endsWith(".txt")
				|| path.endsWith(".rst") || path.endsWith(".adoc")) {
			return SourceType.TEXT;
		}
		return SourceType.AUTO;
	}

	private static String pathOf(String url) {
		try {
			String path = URI.create(url).getPath();
			return path == null ? "" : path;
		}
		catch (IllegalArgumentException ex) {
			return "";
		}
	}

}
