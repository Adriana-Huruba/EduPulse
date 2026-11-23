package com.edupulse.retrievalpre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.datasource.password=unused"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RetrievalPreApplicationTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChunkSearchRepository repository;

    @Test
    void normalizesTheQueryLogsTheOriginalAndForwardsTypedConditions(CapturedOutput output) throws Exception {
        given(repository.search(eq("audit policy"), any())).willReturn(List.of(
                new ChunkSearchRepository.Chunk("chunk-1", "Audit policy", "doc-1", "manual.pdf", 2, 3, 0)));

        mvc.perform(get("/search")
                        .param("q", "Could you, audit policy???")
                        .param("filter", "page:gte:3", "source:eq:manual.pdf")
                        .param("filterMode", "OR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.normalizedQuery").value("audit policy"))
                .andExpect(jsonPath("$.results[0].docId").value("doc-1"));

        var captor = ArgumentCaptor.forClass(MetadataFilter.class);
        verify(repository).search(eq("audit policy"), captor.capture());
        assertThat(captor.getValue().mode()).isEqualTo(MetadataFilter.Mode.OR);
        assertThat(captor.getValue().conditions()).containsExactly(
                new MetadataFilter.Condition(MetadataFilter.Field.PAGE, MetadataFilter.Operator.GTE, 3),
                new MetadataFilter.Condition(MetadataFilter.Field.SOURCE, MetadataFilter.Operator.EQ, "manual.pdf"));
        assertThat(output.getOut()).contains("Search query=\"Could you, audit policy???\"");
    }

    @Test
    void omittedFilterUsesTheStandardSearch() throws Exception {
        var empty = new MetadataFilter(MetadataFilter.Mode.AND, List.of());
        given(repository.search("audit", empty)).willReturn(List.of());

        mvc.perform(get("/search").param("q", "Please audit?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isEmpty());

        verify(repository).search("audit", empty);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "unknown:eq:x", "page:contains:3", "source:gt:manual.pdf", "version:eq:zero",
            "is_latest:eq:yes", "page:eq:0", "chunk_index:eq:-1", "source:eq:", ""
    })
    void invalidFilterListsAllowedFieldsAndNeverCallsTheRepository(String filter) throws Exception {
        mvc.perform(get("/search").param("q", "audit").param("filter", filter))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_filter"))
                .andExpect(jsonPath("$.validFields").isArray())
                .andExpect(jsonPath("$.validFields[0]").value("doc_id"));

        verifyNoInteractions(repository);
    }

    @Test
    void invalidModeReturnsBadRequest() throws Exception {
        mvc.perform(get("/search").param("q", "audit").param("filterMode", "XOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_filter"));
        verifyNoInteractions(repository);
    }

    @Test
    void queryWithoutSearchableTextReturnsBadRequest() throws Exception {
        mvc.perform(get("/search").param("q", "Please, ???"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_query"));
        verifyNoInteractions(repository);
    }
}
