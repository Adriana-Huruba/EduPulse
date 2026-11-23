package com.edupulse.ingestdynamic.extract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

/**
 * Turns an HTML page into clean text: picks the main content area, removes boilerplate
 * (navigation, footers, cookie banners, scripts, ...) and keeps paragraph structure.
 */
@Component
public class HtmlExtractor {

	/** Elements that never carry readable page content, or are page chrome. Reliable, always applied. */
	private static final String STRUCTURAL = String.join(", ",
			"script", "style", "noscript", "template", "iframe", "svg", "canvas", "object", "embed",
			"input", "select", "textarea", "button",
			"nav", "footer", "aside",
			"[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=complementary]", "[role=dialog]",
			"[aria-hidden=true]", "[hidden]");

	/**
	 * Guesses based on class and id names (cookie banners and the like). Useful, but a
	 * wrapper that happens to contain such a word would wrongly remove real content, so
	 * these are skipped when applying them leaves no text.
	 */
	private static final String HEURISTIC = String.join(", ",
			"[class*=cookie]", "[id*=cookie]", "[class*=consent]", "[id*=consent]", "[class*=gdpr]", "[id*=gdpr]",
			"[class*=breadcrumb]", "[id*=breadcrumb]");

	private static final Set<String> PARAGRAPH_BLOCKS = Set.of(
			"p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table", "figure");

	private static final Set<String> LINE_BLOCKS = Set.of(
			"div", "section", "article", "main", "li", "ul", "ol", "tr", "dt", "dd", "br", "header", "figcaption");

	private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

	public ExtractedContent extract(byte[] html, String contentType, String baseUrl) {
		String charset = charsetOf(contentType);
		String text;
		String title;
		try {
			Document full = parse(html, charset, baseUrl);
			title = titleOf(full);
			text = cleanText(full, STRUCTURAL + ", " + HEURISTIC);
			if (text.isBlank()) {
				text = cleanText(parse(html, charset, baseUrl), STRUCTURAL);
			}
		}
		catch (IOException ex) {
			throw new ExtractionException("Could not parse HTML from " + baseUrl, ex);
		}
		if (text.isBlank()) {
			return new ExtractedContent(title, List.of());
		}
		return new ExtractedContent(title, List.of(new ExtractedContent.Section(1, text)));
	}

	private static Document parse(byte[] html, String charset, String baseUrl) throws IOException {
		return Jsoup.parse(new ByteArrayInputStream(html), charset, baseUrl);
	}

	private static String charsetOf(String contentType) {
		if (contentType == null) {
			return null;
		}
		Matcher matcher = CHARSET.matcher(contentType);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static String titleOf(Document doc) {
		String title = doc.title();
		if (!title.isBlank()) {
			return title.strip();
		}
		Element h1 = doc.selectFirst("h1");
		return h1 == null ? "" : h1.text();
	}

	private static String cleanText(Document doc, String removeSelector) {
		Element root = doc.selectFirst("main, [role=main]");
		if (root == null && doc.select("article").size() == 1) {
			root = doc.selectFirst("article");
		}
		boolean wholePage = root == null;
		if (wholePage) {
			root = doc.body();
		}
		// A page-level <header> is boilerplate, but inside <main>/<article> it holds the title.
		String selector = wholePage ? removeSelector + ", header" : removeSelector;
		for (Element element : root.select(selector)) {
			if (element != root) {
				element.remove();
			}
		}
		TextCollector collector = new TextCollector();
		NodeTraversor.traverse(collector, root);
		return TextNormalizer.normalize(collector.toString());
	}

	/** Walks the tree, writing text and a newline or blank line at block boundaries. */
	private static final class TextCollector implements NodeVisitor {

		private final StringBuilder out = new StringBuilder();

		private int preDepth;

		@Override
		public void head(Node node, int depth) {
			if (node instanceof TextNode text) {
				out.append(preDepth > 0 ? text.getWholeText() : text.text());
			}
			else if (node instanceof Element element) {
				String name = element.normalName();
				if (name.equals("pre")) {
					preDepth++;
				}
				if (name.equals("br") || LINE_BLOCKS.contains(name)) {
					out.append('\n');
				}
				else if (PARAGRAPH_BLOCKS.contains(name)) {
					out.append("\n\n");
				}
			}
		}

		@Override
		public void tail(Node node, int depth) {
			if (node instanceof Element element) {
				String name = element.normalName();
				if (name.equals("pre")) {
					preDepth--;
				}
				if (PARAGRAPH_BLOCKS.contains(name)) {
					out.append("\n\n");
				}
				else if (LINE_BLOCKS.contains(name)) {
					out.append('\n');
				}
			}
		}

		@Override
		public String toString() {
			return out.toString();
		}

	}

}
