package com.edupulse.retrievalpre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class QueryNormalizerTest {

    private final QueryNormalizer normalizer = new QueryNormalizer();

    @ParameterizedTest
    @CsvSource(value = {
            "audit policy | audit policy",
            "Audit policy? | Audit policy",
            "Audit policy??? | Audit policy",
            "'  Audit policy?  ' | Audit policy",
            "Please explain access? | explain access",
            "PLEASE, explain access? | explain access",
            "Kindly list controls | list controls",
            "Could you list controls? | list controls",
            "Would you list controls? | list controls",
            "Can you list controls? | list controls",
            "Could   you, kindly list controls?? | list controls",
            "Please, please, audit? | audit",
            "Can you please review policy? | review policy",
            "What? Show policy?? | What? Show policy",
            "Pleaseful policy? | Pleaseful policy",
            "Could youth benefit? | Could youth benefit",
            "Who benefits? It varies. | Who benefits? It varies.",
            "Politique de securite? | Politique de securite",
            "Audit policy? ? | Audit policy"
    }, delimiter = '|')
    void normalizesInDefinedOrderAndIsIdempotent(String query, String expected) {
        String normalized = normalizer.normalize(query);
        assertThat(normalized).isEqualTo(expected);
        assertThat(normalizer.normalize(normalized)).isEqualTo(normalized);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "???", "Could you?", "Please, ???", "audit\0policy"})
    void rejectsInvalidQueries(String query) {
        assertThatThrownBy(() -> normalizer.normalize(query)).isInstanceOf(QueryNormalizer.InvalidQuery.class);
    }

    @Test
    void processesLongNearMatchesAndRepeatedPrefixesWithinTimeLimit() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            String nearMatch = "could" + " ".repeat(4000) + "youth";
            assertThat(normalizer.normalize(nearMatch)).isEqualTo(nearMatch);
            assertThat(normalizer.normalize("please,".repeat(580) + " audit???")).isEqualTo("audit");
            assertThatThrownBy(() -> normalizer.normalize("x".repeat(QueryNormalizer.MAX_LENGTH + 1)))
                    .isInstanceOf(QueryNormalizer.InvalidQuery.class);
        });
    }

    @Test
    void keepsMultilineOriginalInOneLogEntry() {
        assertThat(QueryNormalizer.logValue("Please\r\ncheck\u2028policy?"))
                .isEqualTo("Please\\u000d\\u000acheck\\u2028policy?");
    }
}
