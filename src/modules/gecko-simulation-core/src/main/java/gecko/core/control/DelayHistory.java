package gecko.core.control;

/** Owns chronological delay samples independently of desktop and headless signal wiring. */
public final class DelayHistory {
    private double pendingDelay;
    private double activeDelay;
    private double sampleStep;
    private double[] samples;
    private int next;
    private double beforeOldest;
    private int available;

    /**
     * Creates an uninitialized delay history.
     *
     * @param delay the finite, nonnegative delay in seconds.
     * @throws IllegalArgumentException if the delay is invalid.
     */
    public DelayHistory(double delay) { setDelayTime(delay); }

    /**
     * Defers a delay change until the next restart or time-step callback.
     *
     * @param delay the finite, nonnegative delay in seconds.
     * @throws IllegalArgumentException if the delay is invalid.
     */
    public void setDelayTime(double delay) {
        if (!Double.isFinite(delay) || delay < 0) throw new IllegalArgumentException("Delay time must be finite and nonnegative");
        pendingDelay = delay;
    }

    /** @return the delay activated by the most recent lifecycle callback. */
    public double getDelayTime() { return activeDelay; }

    /**
     * Starts a new run with zero history and the pending delay.
     *
     * @param dt the finite, positive time step in seconds.
     * @throws IllegalArgumentException if the step or required history size is invalid.
     */
    public void restart(double dt) {
        double[] replacement = new double[bufferSize(dt)];
        samples = replacement;
        next = 0;
        beforeOldest = 0;
        available = 0;
        sampleStep = dt;
        activeDelay = pendingDelay;
    }

    /**
     * Preserves known chronological history on a new sample grid.
     * Missing older samples are zero. Interpolation uses the two neighboring recorded samples.
     *
     * @param dt the finite, positive time step in seconds.
     * @throws IllegalArgumentException if the step or required history size is invalid.
     */
    public void changeTimeStep(double dt) {
        double[] replacement = new double[bufferSize(dt)];
        double preceding = 0;
        int known = 0;
        if (samples != null) {
            for (int i = 0; i < replacement.length; i++) {
                double age = (replacement.length - 1.0 - i) * dt;
                double position = samples.length - 1.0 - age / sampleStep;
                replacement[i] = interpolate(position);
                if (isKnown(position)) known++;
            }
            double position = samples.length - 1.0 - replacement.length * dt / sampleStep;
            preceding = interpolate(position);
            if (isKnown(position)) known++;
        }
        samples = replacement;
        next = 0;
        beforeOldest = preceding;
        available = known;
        sampleStep = dt;
        activeDelay = pendingDelay;
    }

    private int bufferSize(double dt) {
        validateStep(dt);
        double size = pendingDelay / dt;
        if (!Double.isFinite(size) || size >= Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("Delay history must fit a Java array");
        }
        return Math.max(1, (int) size);
    }

    private static void validateStep(double dt) {
        if (!Double.isFinite(dt) || dt <= 0) throw new IllegalArgumentException("Time step must be finite and positive");
    }

    private double interpolate(double position) {
        if (!isKnown(position) || position >= samples.length) return 0;
        int lower = (int) Math.floor(position);
        double fraction = position - lower;
        return sample(lower) * (1 - fraction) + sample(lower + 1) * fraction;
    }

    private boolean isKnown(double position) { return position >= samples.length - available; }

    private double sample(int position) {
        if (position == -1) return beforeOldest;
        if (position >= samples.length) return 0;
        return samples[(next + position) % samples.length];
    }

    private void requireInitialized() {
        if (samples == null) throw new IllegalStateException("Delay history requires a restart or time-step initialization");
    }

    /**
     * Reads the oldest sample without capturing or advancing an input.
     *
     * @return the delayed output for the current buffered step.
     * @throws IllegalStateException if the history has not been initialized.
     */
    public double publish() {
        requireInitialized();
        return samples[next];
    }

    /**
     * Captures one settled input and advances history by one sample.
     *
     * @param input the current input value.
     * @throws IllegalStateException if the history has not been initialized.
     */
    public void capture(double input) {
        requireInitialized();
        beforeOldest = samples[next];
        samples[next] = input;
        next = (next + 1) % samples.length;
        if (available <= samples.length) available++;
    }

    /**
     * Calculates one output and captures the current input, including pass-through operation.
     *
     * @param input the current input value.
     * @param dt the finite, positive time step in seconds.
     * @return the delayed output, or the current input when the delay is shorter than the step.
     * @throws IllegalArgumentException if the step is invalid.
     * @throws IllegalStateException if the history has not been initialized.
     */
    public double step(double input, double dt) {
        validateStep(dt);
        double output = dt > activeDelay ? input : publish();
        capture(input);
        return output;
    }
}
