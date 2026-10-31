package gecko.rest.integration;

import gecko.rest.service.WebSocketProgressService;
import gecko.rest.model.SimulationProgressMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.context-path=", "gecko.api.auth-enabled=true", "gecko.api.keys=socket-test-key"
})
class WebSocketAuthenticationIntegrationTest {
    @LocalServerPort
    private int port;
    private WebSocketStompClient client;
    @Autowired
    private WebSocketProgressService progressService;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void startClient() {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        client.setMessageConverter(converter);
    }

    @AfterEach
    void stopClient() {
        client.stop();
    }

    private StompSession connect(String key) throws Exception {
        StompHeaders headers = new StompHeaders();
        if (key != null) {
            headers.add("X-API-Key", key);
        }
        return client.connectAsync("ws://localhost:" + port + "/ws-raw",
                new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
    }

    @Test
    void missingKeyCannotOpenAStompSession() {
        assertThrows(ExecutionException.class, () -> connect(null));
    }

    @Test
    void invalidKeyCannotOpenAStompSession() {
        assertThrows(ExecutionException.class, () -> connect("wrong-key"));
    }

    @Test
    void validKeyCanSubscribeAndReceiveProgress() throws Exception {
        StompSession session = connect("socket-test-key");
        assertTrue(session.isConnected());
        ArrayBlockingQueue<SimulationProgressMessage> frames = new ArrayBlockingQueue<>(10);
        session.subscribe("/topic/simulations/authorized", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return SimulationProgressMessage.class;
            }
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.offer((SimulationProgressMessage) payload);
            }
        });
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            progressService.broadcastProgress("authorized", 50, 0.5, 1, 5, 10, "RUNNING");
            assertTrue(session.isConnected());
            SimulationProgressMessage frame = frames.poll();
            assertNotNull(frame);
            assertEquals("authorized", frame.getSimulationId());
        });
        session.disconnect();
    }
}
