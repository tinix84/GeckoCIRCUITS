package gecko.rest.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;

/** Authenticates STOMP before a client can subscribe to simulation progress. */
public final class ApiKeyStompInterceptor implements ChannelInterceptor {
    private final ApiKeyProperties properties;

    public ApiKeyStompInterceptor(ApiKeyProperties properties) {
        this.properties = properties;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (accessor.getCommand() == StompCommand.CONNECT || accessor.getCommand() == StompCommand.STOMP) {
            if (!properties.isValidKey(accessor.getFirstNativeHeader(ApiKeyAuthFilter.API_KEY_HEADER))) {
                throw new AccessDeniedException("Valid X-API-Key CONNECT header required");
            }
            accessor.removeNativeHeader(ApiKeyAuthFilter.API_KEY_HEADER);
            // Never expose the credential as a principal name.
            accessor.setUser(() -> "api-key");
        } else if (accessor.getCommand() != StompCommand.DISCONNECT
                && properties.isAuthEnabled() && !properties.getKeys().isEmpty()
                && accessor.getUser() == null) {
            throw new AccessDeniedException("Authenticated STOMP session required");
        }
        return message;
    }
}
