package com.edupulse.retrievalpost.rerank;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java BERT uncased WordPiece tokenizer for cross-encoder (query, passage) pair encoding.
 *
 * <p>Avoids native tokenizer library dependencies across Windows and Linux containers while
 * matching HuggingFace {@code BertTokenizer(do_lower_case=True)} behavior for
 * {@code cross-encoder/ms-marco-MiniLM-L6-v2}.
 */
public class WordPieceTokenizer {

	private static final String UNK_TOKEN = "[UNK]";
	private static final String CLS_TOKEN = "[CLS]";
	private static final String SEP_TOKEN = "[SEP]";
	private static final String PAD_TOKEN = "[PAD]";
	private static final int MAX_INPUT_CHARS_PER_WORD = 100;

	private final Map<String, Long> vocab;
	private final long unkId;
	private final long clsId;
	private final long sepId;
	private final long padId;

	public WordPieceTokenizer(Map<String, Long> vocab) {
		this.vocab = Map.copyOf(vocab);
		this.unkId = requireToken(UNK_TOKEN);
		this.clsId = requireToken(CLS_TOKEN);
		this.sepId = requireToken(SEP_TOKEN);
		this.padId = requireToken(PAD_TOKEN);
	}

	public static WordPieceTokenizer fromVocabFile(Path vocabPath) throws IOException {
		Map<String, Long> map = new HashMap<>(32_768);
		try (BufferedReader reader = Files.newBufferedReader(vocabPath, StandardCharsets.UTF_8)) {
			String line;
			long index = 0;
			while ((line = reader.readLine()) != null) {
				if (!line.isEmpty()) {
					map.put(line, index);
				}
				index++;
			}
		}
		return new WordPieceTokenizer(map);
	}

	public EncodedPair encodePair(String query, String passage, int maxSequenceLength) {
		if (maxSequenceLength < 4) {
			throw new IllegalArgumentException("maxSequenceLength must be at least 4");
		}
		List<Long> queryIds = tokenizeToIds(query == null ? "" : query);
		List<Long> passageIds = tokenizeToIds(passage == null ? "" : passage);

		int maxContentTokens = maxSequenceLength - 3; // [CLS] + [SEP] + [SEP]
		truncatePair(queryIds, passageIds, maxContentTokens);

		int totalLen = queryIds.size() + passageIds.size() + 3;
		long[] inputIds = new long[totalLen];
		long[] attentionMask = new long[totalLen];
		long[] tokenTypeIds = new long[totalLen];

		int pos = 0;
		inputIds[pos] = clsId;
		attentionMask[pos] = 1L;
		tokenTypeIds[pos] = 0L;
		pos++;

		for (Long id : queryIds) {
			inputIds[pos] = id;
			attentionMask[pos] = 1L;
			tokenTypeIds[pos] = 0L;
			pos++;
		}

		inputIds[pos] = sepId;
		attentionMask[pos] = 1L;
		tokenTypeIds[pos] = 0L;
		pos++;

		for (Long id : passageIds) {
			inputIds[pos] = id;
			attentionMask[pos] = 1L;
			tokenTypeIds[pos] = 1L;
			pos++;
		}

		inputIds[pos] = sepId;
		attentionMask[pos] = 1L;
		tokenTypeIds[pos] = 1L;

		return new EncodedPair(inputIds, attentionMask, tokenTypeIds);
	}

	public long getPadId() {
		return padId;
	}

	public int getVocabSize() {
		return vocab.size();
	}

	List<Long> tokenizeToIds(String text) {
		List<Long> ids = new ArrayList<>();
		for (String basicToken : basicTokenize(text)) {
			wordPieceTokenize(basicToken, ids);
		}
		return ids;
	}

	private List<String> basicTokenize(String text) {
		String cleaned = cleanAndLower(text);
		List<String> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < cleaned.length(); i++) {
			char ch = cleaned.charAt(i);
			if (Character.isWhitespace(ch)) {
				flushToken(current, tokens);
			}
			else if (isPunctuation(ch)) {
				flushToken(current, tokens);
				tokens.add(String.valueOf(ch));
			}
			else {
				current.append(ch);
			}
		}
		flushToken(current, tokens);
		return tokens;
	}

	private void wordPieceTokenize(String word, List<Long> out) {
		if (word.length() > MAX_INPUT_CHARS_PER_WORD) {
			out.add(unkId);
			return;
		}
		List<Long> subTokens = new ArrayList<>();
		int start = 0;
		while (start < word.length()) {
			int end = word.length();
			Long matchedId = null;
			while (start < end) {
				String sub = word.substring(start, end);
				if (start > 0) {
					sub = "##" + sub;
				}
				Long id = vocab.get(sub);
				if (id != null) {
					matchedId = id;
					break;
				}
				end--;
			}
			if (matchedId == null) {
				out.add(unkId);
				return;
			}
			subTokens.add(matchedId);
			start = end;
		}
		out.addAll(subTokens);
	}

	private static String cleanAndLower(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
		StringBuilder sb = new StringBuilder(decomposed.length());
		for (int i = 0; i < decomposed.length(); i++) {
			char ch = decomposed.charAt(i);
			int type = Character.getType(ch);
			if (type == Character.NON_SPACING_MARK) {
				continue;
			}
			if (ch == '\0' || ch == 0xFFFD || isControl(ch)) {
				continue;
			}
			if (Character.isWhitespace(ch)) {
				sb.append(' ');
			}
			else {
				sb.append(ch);
			}
		}
		return sb.toString();
	}

	private static void truncatePair(List<Long> first, List<Long> second, int maxTotal) {
		while (first.size() + second.size() > maxTotal) {
			if (second.size() >= first.size() && !second.isEmpty()) {
				second.remove(second.size() - 1);
			}
			else if (!first.isEmpty()) {
				first.remove(first.size() - 1);
			}
			else {
				break;
			}
		}
	}

	private static void flushToken(StringBuilder current, List<String> tokens) {
		if (!current.isEmpty()) {
			tokens.add(current.toString());
			current.setLength(0);
		}
	}

	private static boolean isControl(char ch) {
		if (ch == '\t' || ch == '\n' || ch == '\r') {
			return false;
		}
		int type = Character.getType(ch);
		return type == Character.CONTROL || type == Character.FORMAT;
	}

	private static boolean isPunctuation(char ch) {
		if ((ch >= 33 && ch <= 47) || (ch >= 58 && ch <= 64)
				|| (ch >= 91 && ch <= 96) || (ch >= 123 && ch <= 126)) {
			return true;
		}
		int type = Character.getType(ch);
		return type == Character.CONNECTOR_PUNCTUATION
				|| type == Character.DASH_PUNCTUATION
				|| type == Character.START_PUNCTUATION
				|| type == Character.END_PUNCTUATION
				|| type == Character.INITIAL_QUOTE_PUNCTUATION
				|| type == Character.FINAL_QUOTE_PUNCTUATION
				|| type == Character.OTHER_PUNCTUATION;
	}

	private long requireToken(String token) {
		Long id = vocab.get(token);
		if (id == null) {
			throw new IllegalArgumentException("Vocabulary missing required special token: " + token);
		}
		return id;
	}

	public record EncodedPair(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
	}
}
