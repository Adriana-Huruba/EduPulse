package com.edupulse.ingestdynamic.ingest;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.edupulse.ingestdynamic.config.IngestProperties;
import com.edupulse.ingestdynamic.fetch.FetchException;
import com.edupulse.ingestdynamic.fetch.FetchedResource;
import com.edupulse.ingestdynamic.fetch.SourceFetcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the documentation files of a GitHub repository (the README and everything under
 * {@code docs/}) using the git tree API, and builds the raw-download URL of each.
 * All HTTP goes through the {@link SourceFetcher}.
 */
@Component
public class GitHubRepoResolver {

	/** A documentation file in a repository. */
	public record RepoFile(String path, String rawUrl, String htmlUrl) {
	}

	private static final Set<String> DOC_EXTENSIONS = Set.of("md", "markdown", "txt", "rst", "adoc", "asciidoc");

	private final SourceFetcher fetcher;

	private final IngestProperties properties;

	private final JsonMapper mapper = JsonMapper.builder().build();

	public GitHubRepoResolver(SourceFetcher fetcher, IngestProperties properties) {
		this.fetcher = fetcher;
		this.properties = properties;
	}

	public List<RepoFile> resolve(String repoUrl) {
		String[] ownerAndRepo = parseOwnerAndRepo(repoUrl);
		String owner = ownerAndRepo[0];
		String repo = ownerAndRepo[1];

		String treeUrl = trimSlash(properties.githubApiBase()) + "/repos/" + owner + "/" + repo
				+ "/git/trees/HEAD?recursive=1";
		FetchedResource tree = fetcher.fetch(treeUrl);

		List<RepoFile> files = new ArrayList<>();
		try {
			for (JsonNode entry : mapper.readTree(new String(tree.body(), StandardCharsets.UTF_8)).path("tree")) {
				String path = entry.path("path").asString("");
				boolean isBlob = "blob".equals(entry.path("type").asString(""));
				long size = entry.path("size").asLong(0);
				if (isBlob && size <= properties.maxBytes() && isDocFile(path)) {
					files.add(new RepoFile(path,
							trimSlash(properties.githubRawBase()) + "/" + owner + "/" + repo + "/HEAD/" + encodePath(path),
							"https://github.com/" + owner + "/" + repo + "/blob/HEAD/" + encodePath(path)));
				}
			}
		}
		catch (RuntimeException ex) {
			throw new FetchException("Could not read the file list of " + repoUrl + ": " + ex.getMessage(), ex);
		}

		if (files.isEmpty()) {
			throw new FetchException("No README or docs files found in " + repoUrl);
		}
		files.sort(Comparator.comparing((RepoFile f) -> !isReadme(f.path())).thenComparing(RepoFile::path));
		return files.size() > properties.repoMaxFiles() ? files.subList(0, properties.repoMaxFiles()) : files;
	}

	static String[] parseOwnerAndRepo(String repoUrl) {
		URI uri;
		try {
			uri = URI.create(repoUrl.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new FetchException("Invalid repository URL: " + repoUrl, ex);
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		if (!host.equals("github.com") && !host.equals("www.github.com")) {
			throw new FetchException("Only github.com repositories are supported: " + repoUrl);
		}
		String[] parts = Arrays.stream(uri.getPath().split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
		if (parts.length < 2) {
			throw new FetchException("Expected a URL like https://github.com/owner/repo: " + repoUrl);
		}
		String repo = parts[1].endsWith(".git") ? parts[1].substring(0, parts[1].length() - 4) : parts[1];
		return new String[] { parts[0], repo };
	}

	private static boolean isDocFile(String path) {
		int dot = path.lastIndexOf('.');
		String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
		if (!DOC_EXTENSIONS.contains(extension)) {
			return false;
		}
		return isReadme(path) || path.toLowerCase(Locale.ROOT).startsWith("docs/");
	}

	private static boolean isReadme(String path) {
		return !path.contains("/") && path.toLowerCase(Locale.ROOT).startsWith("readme");
	}

	private static String encodePath(String path) {
		return Arrays.stream(path.split("/"))
				.map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
				.collect(Collectors.joining("/"));
	}

	private static String trimSlash(String base) {
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

}
