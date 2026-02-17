package gemini3d.trace.membership;

import gemini3d.trace.semantics.*;

import java.util.*;

public class RelaxedMembership<M, O, C1, A2, C2> implements DeterministicIOSemantics<M, RelaxedMembershipResult, RelaxedMembershipAction, Pair<M, Set<C2>>> {
    TraceSemanticsCip<M> traceSem;
    IOSemantics<StepCip<M>, O, A2, C2> specSem;
    boolean strict;

    @Override
    public Optional<Pair<M, Set<C2>>> initial() {
        var tsIO = traceSem.initial();
        if (tsIO.isEmpty()) return Optional.empty();
        var specInitials = specSem.initial();
        if (specInitials.isEmpty()) return Optional.empty();
        return Optional.of(new Pair<>(tsIO.get(), Set.copyOf(specInitials)));
    }

    @Override
    public Optional<RelaxedMembershipAction> actions(M input, Pair<M, Set<C2>> configuration) {
        if (input == null) {
            return Optional.empty();
        };
        return Optional.of(new RelaxedMembershipAction());
    }

    @Override
    public Optional<Pair<RelaxedMembershipResult, Pair<M, Set<C2>>>> execute(RelaxedMembershipAction action, M input, Pair<M, Set<C2>> configuration) {
        if (input == null || action == null) { return Optional.empty(); }
        var tsAO = traceSem.actions(input, configuration.lhs());
        if (tsAO.isEmpty()) { return Optional.empty(); }

        var tsTargetO = traceSem.execute(tsAO.get(), input, configuration.lhs());
        if (tsTargetO.isEmpty()) { return Optional.empty(); }
        var tsPair = tsTargetO.get();

        var tsOutputO = tsPair.lhs();
        var tsTarget = tsPair.rhs();

        if (tsOutputO.isEmpty()) {
            return Optional.of(new Pair<>(new RelaxedMembershipOk(), new Pair<>(tsTarget, configuration.rhs())));
        }

        var tsOutput = tsOutputO.get();
        var specTargets = new HashSet<C2>();
        for (C2 c2 : configuration.rhs()) {
            var specActions = specSem.actions(tsOutput, c2);

            for (A2 a2 : specActions) {
                var specTargetsA2c2 = specSem.execute(a2, tsOutput, c2);

                specTargets.addAll(specTargetsA2c2.stream().map(Pair::rhs).toList());
            }
        }

        if (specTargets.isEmpty()) {
            if (strict) {
                return Optional.of(new Pair<>(new RelaxedMembershipFail(), new Pair<>(tsTarget, specTargets)));
            }
            return Optional.of(new Pair<>(new RelaxedMembershipFail(), new Pair<>(tsTarget, configuration.rhs())));
        }

        return Optional.of(new Pair<>(new RelaxedMembershipOk(), new Pair<>(tsTarget, specTargets)));
    }
}

interface RelaxedMembershipResult {}
record RelaxedMembershipFail() implements RelaxedMembershipResult {}
record RelaxedMembershipOk() implements RelaxedMembershipResult {}
record RelaxedMembershipAction() {}
