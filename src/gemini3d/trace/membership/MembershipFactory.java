package gemini3d.trace.membership;

import gemini3d.trace.semantics.*;

import java.util.Optional;
import java.util.Set;


public class MembershipFactory {

    public static <M, O, C1, A2, C2>
    DeterministicIOSemantics<M, RelaxedMembershipResult, RelaxedMembershipAction, Pair<M, Set<C2>>>
    create(TraceSemanticsCip<M> traceSem,
           IOSemantics<StepCip<M>, O, A2, C2> specSem,
           boolean strict) {

        // Build the inner membership (package-private field access)
        RelaxedMembership<M, O, C1, A2, C2> inner = new RelaxedMembership<>();
        inner.traceSem = traceSem;
        inner.specSem = specSem;
        inner.strict = strict;

        // Wrap with fixed initial()
        return new DeterministicIOSemantics<>() {

            @Override
            public Optional<Pair<M, Set<C2>>> initial() {
                // Trace initial is empty (no previous measurement) — that's normal.
                // Use null as M, same convention as TraceSemanticsCip.
                M traceInit = traceSem.initial().orElse(null);

                var specInitials = specSem.initial();
                if (specInitials.isEmpty()) return Optional.empty();

                return Optional.of(new Pair<>(traceInit, Set.copyOf(specInitials)));
            }

            @Override
            public Optional<RelaxedMembershipAction> actions(
                    M input, Pair<M, Set<C2>> configuration) {
                return inner.actions(input, configuration);
            }

            @Override
            public Optional<Pair<RelaxedMembershipResult, Pair<M, Set<C2>>>> execute(
                    RelaxedMembershipAction action, M input, Pair<M, Set<C2>> configuration) {
                return inner.execute(action, input, configuration);
            }
        };
    }
}