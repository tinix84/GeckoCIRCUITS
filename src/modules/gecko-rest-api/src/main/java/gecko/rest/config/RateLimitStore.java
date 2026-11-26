package gecko.rest.config;

import java.util.HashMap;
import java.util.Map;

interface RateLimitStore {
    Admission admit(String client, long now, boolean submission, int generalLimit, int simulationLimit);

    record Admission(boolean allowed, int general, int simulations, long reset, long retryAfter) { }

    final class InMemory implements RateLimitStore {
        private final Map<String, Budget> clients = new HashMap<>();

        @Override
        public synchronized Admission admit(String client, long now, boolean submission,
                                             int generalLimit, int simulationLimit) {
            long window = Math.floorDiv(now, 60);
            long reset = (window + 1) * 60;
            // Expired clients do not retain memory after their window ends.
            clients.values().removeIf(budget -> budget.window != window);
            Budget budget = clients.computeIfAbsent(client, ignored -> new Budget(window));
            boolean allowed = budget.general < generalLimit && (!submission || budget.simulations < simulationLimit);
            if (allowed) {
                budget.general++;
                if (submission) budget.simulations++;
            }
            return new Admission(allowed, budget.general, budget.simulations, reset, reset - now);
        }

        private static final class Budget {
            private final long window;
            private int general;
            private int simulations;
            Budget(long window) { this.window = window; }
        }
    }
}
