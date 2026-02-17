package gemini3d.trace.semantics;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BiFunction;

public class TraceSemanticsCip<Meas> implements DeterministicIOSemantics<Meas, Optional<StepCip<Meas>>, MeasAction, Meas> {
   // Meas last;
    BiFunction<Meas, Meas, Duration> getDuration;

    public TraceSemanticsCip(BiFunction<Meas, Meas, Duration> durationBiFunction) {
        getDuration = durationBiFunction;
    }

    public Optional<Meas> initial() {
        return Optional.empty();
    }

    public Optional<MeasAction> actions(Meas current, Meas old) {
        return Optional.of(new MeasAction());
    }

    public Optional<Pair<Optional<StepCip<Meas>>, Meas>> execute(MeasAction action, Meas current, Meas old) {
        if (old == null) {
            return Optional.of(new Pair<>(Optional.empty(), current));
        }
        return Optional.of(
                new Pair<>(
                        Optional.of(new StepCip<>(old, getDuration.apply(old, current), current)),
                        current));
    }
}

