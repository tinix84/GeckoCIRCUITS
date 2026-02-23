package gecko.rest.controller;

import gecko.rest.model.circuit.PlecsConversionResponse;
import gecko.rest.service.ConversionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link ConversionController}.
 */
@WebMvcTest(ConversionController.class)
@Import(gecko.rest.config.TestSecurityConfig.class)
class ConversionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConversionService conversionService;

    @Test
    void convertPlecs_success_returns201WithCircuitId() throws Exception {
        PlecsConversionResponse mockResponse = PlecsConversionResponse.success(
                "circuit-abc", "buck.plecs", 5, List.of());
        when(conversionService.convertPlecs(any())).thenReturn(mockResponse);

        MockMultipartFile file = new MockMultipartFile(
                "file", "buck.plecs", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "<PLECS/>".getBytes());

        mockMvc.perform(multipart("/api/v1/convert/plecs").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("converted"))
                .andExpect(jsonPath("$.circuitId").value("circuit-abc"))
                .andExpect(jsonPath("$.filename").value("buck.plecs"))
                .andExpect(jsonPath("$.componentCount").value(5))
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.errorMessage").isEmpty());
    }

    @Test
    void convertPlecs_withWarnings_returns201AndWarnings() throws Exception {
        List<String> warnings = List.of("Unsupported PLECS block type ignored: 'Transformer'");
        PlecsConversionResponse mockResponse = PlecsConversionResponse.success(
                "circuit-xyz", "bridge.plecs", 3, warnings);
        when(conversionService.convertPlecs(any())).thenReturn(mockResponse);

        MockMultipartFile file = new MockMultipartFile(
                "file", "bridge.plecs", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "<PLECS/>".getBytes());

        mockMvc.perform(multipart("/api/v1/convert/plecs").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("converted"))
                .andExpect(jsonPath("$.warnings[0]")
                        .value("Unsupported PLECS block type ignored: 'Transformer'"));
    }

    @Test
    void convertPlecs_failure_returns400() throws Exception {
        PlecsConversionResponse mockResponse = PlecsConversionResponse.failure(
                "invalid.plecs", "Failed to parse PLECS file: Expected <PLECS> root element");
        when(conversionService.convertPlecs(any())).thenReturn(mockResponse);

        MockMultipartFile file = new MockMultipartFile(
                "file", "invalid.plecs", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "not xml".getBytes());

        mockMvc.perform(multipart("/api/v1/convert/plecs").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.circuitId").isEmpty())
                .andExpect(jsonPath("$.errorMessage").isNotEmpty());
    }

    @Test
    void convertPlecs_noFile_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/convert/plecs"))
                .andExpect(status().isBadRequest());
    }
}
