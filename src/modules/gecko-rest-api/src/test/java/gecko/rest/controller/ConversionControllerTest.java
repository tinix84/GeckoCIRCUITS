package gecko.rest.controller;

import gecko.rest.model.conversion.LtspiceConversionResponse;
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
 * Unit tests for {@link ConversionController} using MockMvc.
 */
@WebMvcTest(ConversionController.class)
@Import(gecko.rest.config.TestSecurityConfig.class)
class ConversionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConversionService conversionService;

    @Test
    void testConvertLtspice_success() throws Exception {
        LtspiceConversionResponse mockResponse = LtspiceConversionResponse.success(
                "circuit-abc",
                "buck.asc",
                3,
                List.of("Simulation parameters set to defaults."),
                "H4sIAAAAAAAA..."
        );
        when(conversionService.convert(any())).thenReturn(mockResponse);

        MockMultipartFile file = new MockMultipartFile(
                "file", "buck.asc", MediaType.TEXT_PLAIN_VALUE, "Version 4".getBytes());

        mockMvc.perform(multipart("/api/v1/convert/ltspice").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.circuitId").value("circuit-abc"))
                .andExpect(jsonPath("$.status").value("converted"))
                .andExpect(jsonPath("$.filename").value("buck.asc"))
                .andExpect(jsonPath("$.componentCount").value(3))
                .andExpect(jsonPath("$.warnings[0]").value("Simulation parameters set to defaults."))
                .andExpect(jsonPath("$.ipesBase64").value("H4sIAAAAAAAA..."))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    void testConvertLtspice_failure_returnsBadRequest() throws Exception {
        LtspiceConversionResponse mockResponse = LtspiceConversionResponse.failure(
                "bad.asc",
                "Failed to parse .asc file: No circuit elements found"
        );
        when(conversionService.convert(any())).thenReturn(mockResponse);

        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.asc", MediaType.TEXT_PLAIN_VALUE, "not asc".getBytes());

        mockMvc.perform(multipart("/api/v1/convert/ltspice").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.errorMessage").value("Failed to parse .asc file: No circuit elements found"))
                .andExpect(jsonPath("$.circuitId").doesNotExist());
    }

    @Test
    void testConvertLtspice_missingFile_returnsServerError() throws Exception {
        // Spring returns 500 in WebMvcTest context when required multipart parameter is absent
        mockMvc.perform(multipart("/api/v1/convert/ltspice"))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void testConvertLtspice_withWarnings() throws Exception {
        LtspiceConversionResponse mockResponse = LtspiceConversionResponse.success(
                "circuit-xyz",
                "test.asc",
                2,
                List.of("Unsupported component type 'nmos' skipped.",
                        "Simulation parameters set to defaults."),
                "H4sI..."
        );
        when(conversionService.convert(any())).thenReturn(mockResponse);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.asc", MediaType.TEXT_PLAIN_VALUE, "Version 4".getBytes());

        mockMvc.perform(multipart("/api/v1/convert/ltspice").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.warnings.length()").value(2));
    }
}
