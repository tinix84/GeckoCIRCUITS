package gecko.rest.controller;

import gecko.rest.model.analysis.SteadyStateResponse;
import gecko.rest.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller unit tests for the steady-state endpoint in {@link AnalysisController}.
 */
@WebMvcTest(AnalysisController.class)
@Import(gecko.rest.config.TestSecurityConfig.class)
class SteadyStateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalysisService analysisService;

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void steadyState_converged_returns200WithConvergedTrue() throws Exception {
        SteadyStateResponse mockResponse = buildConvergedResponse();
        when(analysisService.computeSteadyState(any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/analysis/steady-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "circuitFile": "circuits/buck.ipes",
                        "timeStep": 1e-7,
                        "period": 1e-4,
                        "maxPeriods": 200,
                        "tolerance": 1e-3
                    }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converged").value(true))
                .andExpect(jsonPath("$.status").value("CONVERGED"))
                .andExpect(jsonPath("$.periodsSimulated").value(42))
                .andExpect(jsonPath("$.convergenceError").value(5e-4))
                .andExpect(jsonPath("$.period").value(1e-4))
                .andExpect(jsonPath("$.executionTimeMs").value(1500));
    }

    @Test
    void steadyState_maxPeriodsReached_returns200WithConvergedFalse() throws Exception {
        SteadyStateResponse mockResponse = new SteadyStateResponse();
        mockResponse.setConverged(false);
        mockResponse.setStatus("MAX_PERIODS_REACHED");
        mockResponse.setPeriodsSimulated(200);
        mockResponse.setConvergenceError(0.05);
        mockResponse.setPeriod(1e-4);
        mockResponse.setTimeArray(new double[]{0.0, 1e-7});
        mockResponse.setSignals(Map.of("V_out", new double[]{5.0, 5.1}));

        when(analysisService.computeSteadyState(any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/analysis/steady-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "circuitFile": "circuits/buck.ipes",
                        "timeStep": 1e-7,
                        "period": 1e-4,
                        "maxPeriods": 200,
                        "tolerance": 1e-5
                    }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converged").value(false))
                .andExpect(jsonPath("$.status").value("MAX_PERIODS_REACHED"))
                .andExpect(jsonPath("$.periodsSimulated").value(200));
    }

    @Test
    void steadyState_responseContainsTimeArrayAndSignals() throws Exception {
        SteadyStateResponse mockResponse = buildConvergedResponse();
        when(analysisService.computeSteadyState(any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/analysis/steady-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "circuitFile": "circuits/buck.ipes",
                        "timeStep": 1e-7,
                        "period": 1e-4
                    }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeArray").isArray())
                .andExpect(jsonPath("$.signals").isMap())
                .andExpect(jsonPath("$.signals.V_out").isArray());
    }

    @Test
    void steadyState_withOptionalParameters_passesRequestToService() throws Exception {
        SteadyStateResponse mockResponse = buildConvergedResponse();
        when(analysisService.computeSteadyState(any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/analysis/steady-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "circuitFile": "circuits/buck.ipes",
                        "timeStep": 1e-7,
                        "period": 1e-4,
                        "maxPeriods": 100,
                        "tolerance": 1e-4,
                        "solverType": "SOLVER_TRZ",
                        "parameters": {"R_load": 10.0}
                    }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converged").value(true));
    }

    @Test
    void steadyState_defaultMaxPeriodsAndTolerance_notRequired() throws Exception {
        SteadyStateResponse mockResponse = buildConvergedResponse();
        when(analysisService.computeSteadyState(any())).thenReturn(mockResponse);

        // maxPeriods and tolerance are optional (have defaults)
        mockMvc.perform(post("/api/v1/analysis/steady-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "circuitFile": "circuits/buck.ipes",
                        "timeStep": 1e-7,
                        "period": 1e-4
                    }
                    """))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Error scenarios delegated to service
    // -------------------------------------------------------------------------

    @Test
    void steadyState_serviceThrows_propagatesStatus() throws Exception {
        when(analysisService.computeSteadyState(any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "Simulation failed"));

        mockMvc.perform(post("/api/v1/analysis/steady-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "circuitFile": "circuits/bad.ipes",
                        "timeStep": 1e-7,
                        "period": 1e-4
                    }
                    """))
                .andExpect(status().isInternalServerError());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SteadyStateResponse buildConvergedResponse() {
        SteadyStateResponse r = new SteadyStateResponse();
        r.setConverged(true);
        r.setStatus("CONVERGED");
        r.setPeriodsSimulated(42);
        r.setConvergenceError(5e-4);
        r.setPeriod(1e-4);
        r.setTimeArray(new double[]{0.0, 1e-7, 2e-7});
        r.setSignals(Map.of("V_out", new double[]{48.0, 48.1, 48.0}));
        r.setExecutionTimeMs(1500);
        return r;
    }
}
