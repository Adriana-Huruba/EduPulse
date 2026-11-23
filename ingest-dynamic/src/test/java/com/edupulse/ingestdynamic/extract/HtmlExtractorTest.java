package com.edupulse.ingestdynamic.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HtmlExtractorTest {

	private final HtmlExtractor extractor = new HtmlExtractor();

	private ExtractedContent extract(String html) {
		return extractor.extract(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8",
				"https://example.org/page");
	}

	@Test
	void removesBoilerplateAndKeepsMainContent() {
		ExtractedContent content = extract("""
				<html><head><title>Spring Guide</title><script>var t = "TRACKING_JS";</script>
				<style>.x { color: red } STYLE_RULE</style></head>
				<body>
				<nav>Home | Products | NAV_MENU</nav>
				<div id="cookie-banner">We use cookies COOKIE_NOTICE <button>Accept</button></div>
				<main>
				  <h1>Ingestion guide</h1>
				  <p>First paragraph about ingestion.</p>
				  <p>Second paragraph about chunks.</p>
				  <ul><li>item one</li><li>item two</li></ul>
				</main>
				<footer>Copyright FOOTER_TEXT</footer>
				</body></html>
				""");

		assertThat(content.title()).isEqualTo("Spring Guide");
		assertThat(content.sections()).hasSize(1);
		String text = content.sections().get(0).text();
		assertThat(text).contains("Ingestion guide", "First paragraph about ingestion.",
				"Second paragraph about chunks.", "item one", "item two");
		assertThat(text).doesNotContain("NAV_MENU", "COOKIE_NOTICE", "FOOTER_TEXT", "TRACKING_JS", "STYLE_RULE",
				"Accept");
	}

	@Test
	void keepsParagraphBreaks() {
		String text = extract("<html><body><p>First.</p><p>Second.</p></body></html>").sections().get(0).text();

		assertThat(text).isEqualTo("First.\n\nSecond.");
	}

	@Test
	void pageWithoutMainDropsHeaderNavAndFooter() {
		String text = extract("""
				<html><body>
				<header>Site header HEADER_TEXT</header>
				<nav>NAV_MENU</nav>
				<div class="content"><p>The real article text.</p></div>
				<aside>Related links SIDEBAR_TEXT</aside>
				<footer>FOOTER_TEXT</footer>
				</body></html>
				""").sections().get(0).text();

		assertThat(text).isEqualTo("The real article text.");
	}

	@Test
	void headerInsideArticleIsKeptBecauseItHoldsTheTitle() {
		String text = extract("""
				<html><body><nav>NAV_MENU</nav>
				<article><header><h1>Article title</h1></header><p>Body text.</p></article>
				</body></html>
				""").sections().get(0).text();

		assertThat(text).contains("Article title", "Body text.").doesNotContain("NAV_MENU");
	}

	@Test
	void fallsBackWhenCleanupWouldRemoveEverything() {
		ExtractedContent content = extract(
				"<html><body><div class=\"cookie-wrapper\"><p>Real content inside a badly named wrapper.</p></div></body></html>");

		assertThat(content.sections().get(0).text()).isEqualTo("Real content inside a badly named wrapper.");
	}

	@Test
	void emptyPageGivesNoSections() {
		ExtractedContent content = extract("<html><head><title>Empty</title></head><body><nav>menu</nav></body></html>");

		assertThat(content.isEmpty()).isTrue();
		assertThat(content.title()).isEqualTo("Empty");
	}

}
