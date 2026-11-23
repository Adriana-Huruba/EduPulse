package com.edupulse.ingeststatic.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TextCleanerTest {

    @Test
    void normalizesLigaturesHyphenationAndControlCharacters() {
        String raw = "Ef\uFB01cient infor-\nmation\u00A0retrieval\u200B\u0007.\r\n\r\n\r\nNext paragraph.";

        String cleaned = TextCleaner.clean(raw);

        assertThat(cleaned).isEqualTo("Efficient information retrieval.\n\nNext paragraph.");
    }

    @Test
    void removesRunningHeadersFootersAndPageNumbersAcrossPages() {
        List<PageText> pages = List.of(
                new PageText(1, "EduPulse Technical Guide\n\nFirst page body text.\n\nPage 1 of 3"),
                new PageText(2, "EduPulse Technical Guide\n\nSecond page body text.\n\nPage 2 of 3"),
                new PageText(3, "EduPulse Technical Guide\n\nThird page body text.\n\nPage 3 of 3")
        );

        List<PageText> cleaned = TextCleaner.cleanPages(pages);

        assertThat(cleaned).extracting(PageText::text)
                .containsExactly("First page body text.", "Second page body text.", "Third page body text.");
    }

    @Test
    void dropsMostlyGarbledPages() {
        String garbled = "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFDabc";
        List<PageText> cleaned = TextCleaner.cleanPages(List.of(
                new PageText(1, garbled),
                new PageText(2, "Readable text on page two.")
        ));

        assertThat(cleaned.get(0).text()).isEmpty();
        assertThat(cleaned.get(1).text()).isEqualTo("Readable text on page two.");
    }
}
