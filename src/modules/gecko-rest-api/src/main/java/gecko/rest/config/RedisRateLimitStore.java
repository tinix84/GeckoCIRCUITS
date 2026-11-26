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
    // Redis selects the minute for all API replicas. One key keeps both counters atomic.
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ADMIT = RedisScript.of("""
            local now = tonumber(redis.call('TIME')[1])
            local reset = (math.floor(now / 60) + 1) * 60
            local ttl = reset - now
            local values = redis.call('HMGET', KEYS[1], 'reset', 'general', 'simulations')
            local general = 0
            local simulations = 0
            if tonumber(values[1]) == reset then
                general = tonumber(values[2])
                simulations = tonumber(values[3])
            end
            local submission = ARGV[3] == 'true'
            if general >= tonumber(ARGV[1]) or (submission and simulations >= tonumber(ARGV[2])) then
                return {0, general, simulations, reset, ttl}
            end
            general = general + 1
            if submission then simulations = simulations + 1 end
            redis.call('HSET', KEYS[1], 'reset', reset, 'general', general, 'simulations', simulations)
            redis.call('EXPIRE', KEYS[1], ttl)
            return {1, general, simulations, reset, ttl}
            """, List.class);

    private final StringRedisTemplate redis;
    private final String namespace;

    RedisRateLimitStore(StringRedisTemplate redis, @Value("${gecko.api.rate-limit-namespace:gecko}") String namespace) {
        this.redis = redis;
        this.namespace = namespace;
    }

    @Override
    public Admission admit(String client, long now, boolean submission,
                           int generalLimit, int simulationLimit) {
        String key = namespace + ":rate:{" + ApiIdentity.keyId(client) + "}:budget";
        List<?> result = redis.execute(ADMIT, List.of(key),
                Integer.toString(generalLimit), Integer.toString(simulationLimit),
                Boolean.toString(submission));
        if (result == null || result.size() != 5) {
            throw new DataRetrievalFailureException("Missing Redis admission result");
        }
        return new Admission(((Number) result.get(0)).longValue() == 1,
                ((Number) result.get(1)).intValue(), ((Number) result.get(2)).intValue(),
                ((Number) result.get(3)).longValue(), ((Number) result.get(4)).longValue());
    }
}
