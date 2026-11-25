package gecko.rest.config;

import java.util.HashMap;
import java.util.Map;

interface RateLimitStore {
    Admission admit(String client, long window, long ttl, boolean submission, int generalLimit, int simulationLimit);

    record Admission(boolean allowed, int general, int simulations) { }

    final class InMemory implements RateLimitStore {
        private final Map<String, Budget> clients = new HashMap<>();

        @Override
        public synchronized Admission admit(String client, long window, long ttl, boolean submission,
                                             int generalLimit, int simulationLimit) {
            // Expired clients do not retain memory after their window ends.
            clients.values().removeIf(budget -> budget.window != window);
            Budget budget = clients.computeIfAbsent(client, ignored -> new Budget(window));
            boolean allowed = budget.general < generalLimit && (!submission || budget.simulations < simulationLimit);
            if (allowed) {
                budget.general++;
                if (submission) budget.simulations++;
            }
            return new Admission(allowed, budget.general, budget.simulations);
        }

        private static final class Budget {
            private final long window;
            private int general;
            private int simulations;
            Budget(long window) { this.window = window; }
        }
    }
}
