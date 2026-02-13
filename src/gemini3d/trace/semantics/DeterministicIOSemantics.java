package gemini3d.trace.semantics;

import java.util.Optional;

public interface DeterministicIOSemantics<I, O, A, C> {
    Optional<C> initial();
    Optional<A> actions(I input, C configuration);
    Optional<Pair<O, C>> execute(A action, I input, C configuration);
    record Pair<O, C>(O lhs, C rhs) {}
}
