package gemini3d.trace.semantics;

import java.time.Duration;

public record StepCip<Meas>(Meas last, Duration t, Meas current) {}
