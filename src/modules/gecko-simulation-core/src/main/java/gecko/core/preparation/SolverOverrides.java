package gecko.core.preparation;

import gecko.core.allg.SolverType;

/** Optional settings. Null fields inherit the captured values. */
public record SolverOverrides(SolverType solver, Double timeStep, Double duration) {
    /**
     * Leaves every setting absent so preparation inherits each captured value.
     *
     * @return overrides with no supplied setting
     */
    public static SolverOverrides none() { return new SolverOverrides(null, null, null); }
}
