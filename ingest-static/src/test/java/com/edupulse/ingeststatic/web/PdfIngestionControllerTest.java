package com.edupulse.ingeststatic.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.edupulse.ingeststatic.config.IngestProperties;
import com.edupulse.ingeststatic.exception.DocumentParsingException;
import com.edupulse.ingeststatic.exception.InvalidDocumentException;
import com.edupulse.ingeststatic.exception.SourceNotFoundException;
import com.edupulse.ingeststatic.exception.StorageException;
import com.edupulse.ingeststatic.ingest.IngestionResult;
import com.edupulse.ingeststatic.ingest.IngestionStatus;
import com.edupulse.ingeststatic.ingest.PdfIngestionService;

@WebMvcTest(PdfIngestionController.class)
@Import(GlobalExceptionHandler.class)
class PdfIngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PdfIngestionService ingestionService;

    @MockitoBean
    private IngestProperties properties;

    @Test
    void uploadReturns200WithIngestionSummary() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.pdf", "application/pdf", "%PDF-1.7\ncontent".getBytes());
        when(ingestionService.ingestUpload(eq("sample.pdf"), any()))
                .thenReturn(new IngestionResult("doc-1", "sample.pdf", IngestionStatus.INGESTED, 1, "abc", 2, 4, 50,
                        "Ingested 4 chunks"));

        mockMvc.perform(multipart("/api/v1/ingest/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value("doc-1"))
                .andExpect(jsonPath("$.status").value("INGESTED"))
                .andExpect(jsonPath("$.chunks").value(4));
    }

    @Test
    void mapsDomainExceptionsToExpectedHttpStatuses() throws Exception {
        when(ingestionService.ingestFile("bad.txt"))
                .thenThrow(new InvalidDocumentException("Only .pdf files can be ingested"));
        when(ingestionService.ingestFile("missing.pdf"))
                .thenThrow(new SourceNotFoundException("File not found"));
        when(ingestionService.ingestFile("corrupt.pdf"))
                .thenThrow(new DocumentParsingException("PDF could not be parsed"));
        when(ingestionService.ingestFile("down.pdf"))
                .thenThrow(new StorageException("Ollama unreachable", new RuntimeException("refused")));

        mockMvc.perform(post("/api/v1/ingest/file").param("path", "bad.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only .pdf files can be ingested"));

        mockMvc.perform(post("/api/v1/ingest/file").param("path", "missing.pdf"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/ingest/file").param("path", "corrupt.pdf"))
                .andExpect(status().isUnprocessableContent());

        mockMvc.perform(post("/api/v1/ingest/file").param("path", "down.pdf"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void rejectsInvalidSampleSize() throws Exception {
        mockMvc.perform(get("/api/v1/ingest/chunks/sample").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsSampleChunks() throws Exception {
        when(ingestionService.sampleChunks(20)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/ingest/chunks/sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
