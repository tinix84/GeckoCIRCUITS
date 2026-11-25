package gecko.rest.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("redis")
final class RedisRateLimitStore implements RateLimitStore {
    // Keep both keys in one Redis Cluster slot. Keep the window in the key so expiry
    // delays or different replica clocks cannot extend a budget into the next minute.
    private static final RedisScript<List> ADMIT = RedisScript.of("""
            local general = tonumber(redis.call('GET', KEYS[1]) or '0')
            local simulations = tonumber(redis.call('GET', KEYS[2]) or '0')
            local submission = ARGV[3] == 'true'
            if general >= tonumber(ARGV[1]) or (submission and simulations >= tonumber(ARGV[2])) then
                return {0, general, simulations}
            end
            general = redis.call('INCR', KEYS[1])
            if general == 1 then redis.call('EXPIRE', KEYS[1], ARGV[4]) end
            if submission then
                simulations = redis.call('INCR', KEYS[2])
                if simulations == 1 then redis.call('EXPIRE', KEYS[2], ARGV[4]) end
            end
            return {1, general, simulations}
            """, List.class);

    private final StringRedisTemplate redis;
    private final String namespace;

    RedisRateLimitStore(StringRedisTemplate redis, @Value("${gecko.api.rate-limit-namespace:gecko}") String namespace) {
        this.redis = redis;
        this.namespace = namespace;
    }

    @Override
    public Admission admit(String client, long window, long ttl, boolean submission,
                           int generalLimit, int simulationLimit) {
        String prefix = namespace + ":rate:{" + ApiIdentity.keyId(client) + "}:" + window;
        List<?> result = redis.execute(ADMIT, List.of(prefix + ":all", prefix + ":simulation"),
                Integer.toString(generalLimit), Integer.toString(simulationLimit),
                Boolean.toString(submission), Long.toString(ttl + 60));
        if (result == null || result.size() != 3) {
            throw new DataRetrievalFailureException("Missing Redis admission result");
        }
        return new Admission(((Number) result.get(0)).longValue() == 1,
                ((Number) result.get(1)).intValue(), ((Number) result.get(2)).intValue());
    }
}
