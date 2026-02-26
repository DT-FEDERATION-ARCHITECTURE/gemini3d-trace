package gemini3d.trace.membership;

import gemini3d.trace.emulator.Gemini3DEmulator;
import gemini3d.trace.emulator.Reading;
import gemini3d.trace.fifo.CircularFIFO;
import gemini3d.trace.semantics.*;
import gemini3d.trace.sequencer.Sequencer;

import org.digitaltwin.automaton.model.Automaton;
import org.digitaltwin.automaton.model.Transition;
import org.digitaltwin.automaton.semantics.Configuration;
import org.digitaltwin.automaton.str.AutomatonOutput;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs the full inclusion pipeline and reports results via callbacks.
 * Lives in membership package to access package-private types.
 */
public class InclusionRunner {

    public record StepResult(
            int step, String verdict, Map<String, Object> measurement,
            String previousState, String currentState, String firedTransition,
            List<String> enabledTransitions, Map<String, Object> valuation,
            int possibleConfigs, String reason
    ) {}

    public record FinalResult(int totalSteps, int ok, int fail) {}

    public enum EmulatorMode { FIXED_PERIOD, REAL_DELTA_T }

    private final Automaton automaton;
    private final String csvPath;
    private long emulatorPeriodMs;
    private EmulatorMode emulatorMode = EmulatorMode.FIXED_PERIOD;

    private Consumer<StepResult> onStep;
    private Consumer<FinalResult> onFinished;
    private Consumer<String> onError;

    private volatile Thread t1;
    private volatile Thread t2;

    public InclusionRunner(Automaton automaton, String csvPath, long periodMs) {
        this.automaton = automaton;
        this.csvPath = csvPath;
        this.emulatorPeriodMs = periodMs;
    }

    public InclusionRunner onStep(Consumer<StepResult> l)     { this.onStep = l; return this; }
    public InclusionRunner onFinished(Consumer<FinalResult> l) { this.onFinished = l; return this; }
    public InclusionRunner onError(Consumer<String> l)         { this.onError = l; return this; }
    public void setEmulatorMode(EmulatorMode m) { this.emulatorMode = m; }
    public void setEmulatorPeriodMs(long ms)    { this.emulatorPeriodMs = ms; }

    public void stop() {
        if (t1 != null) t1.interrupt();
        if (t2 != null) t2.interrupt();
    }

    public void run() {
        final Object lock = new Object();
        final int[] counts = {0, 0, 0};
        final String[] lastState = {"(initial)"};

        try {
            TraceSemanticsCip<Reading> traceSli = new TraceSemanticsCip<>((old, cur) -> {
                Double v1 = getTimeValue(old);
                Double v2 = getTimeValue(cur);
                if (v1 != null && v2 != null)
                    return Duration.ofNanos((long) (Math.abs(v2 - v1) * 1_000_000_000L));
                return Duration.ofSeconds(cur.getIndex() - old.getIndex());
            });

            AutomatonSLIAdapter specSli = new AutomatonSLIAdapter(automaton);

            var membership = MembershipFactory
                    .<Reading, AutomatonOutput, Object, Transition, Configuration>create(
                            traceSli, specSli, false);

            DeterministicIOSemantics<Reading, RelaxedMembershipResult, RelaxedMembershipAction,
                    Pair<Reading, Set<Configuration>>> reporting = new DeterministicIOSemantics<>() {

                @Override
                public Optional<Pair<Reading, Set<Configuration>>> initial() {
                    return membership.initial();
                }

                @Override
                public Optional<RelaxedMembershipAction> actions(Reading input,
                                                                 Pair<Reading, Set<Configuration>> config) {
                    return membership.actions(input, config);
                }

                @Override
                public Optional<Pair<RelaxedMembershipResult, Pair<Reading, Set<Configuration>>>> execute(
                        RelaxedMembershipAction action, Reading input,
                        Pair<Reading, Set<Configuration>> config) {

                    var result = membership.execute(action, input, config);
                    if (result.isPresent() && onStep != null) {
                        counts[0]++;
                        RelaxedMembershipResult verdict = result.get().lhs();
                        Set<Configuration> specs = result.get().rhs().rhs();

                        String prev = lastState[0], cur = prev;
                        Map<String, Object> val = Map.of();
                        String fired = null;
                        List<String> enabled = List.of();

                        if (!specs.isEmpty()) {
                            Configuration c = specs.iterator().next();
                            cur = c.currentState();
                            val = c.valuation().toMap();
                        }

                        if (verdict instanceof RelaxedMembershipOk) {
                            counts[1]++;
                            fired = inferTransition(prev, cur);
                            enabled = getTransitionsFrom(prev);
                            lastState[0] = cur;
                            onStep.accept(new StepResult(counts[0], "OK",
                                    input.getValues(), prev, cur, fired, enabled,
                                    val, specs.size(), null));
                        } else {
                            counts[2]++;
                            onStep.accept(new StepResult(counts[0], "FAIL",
                                    input.getValues(), prev, prev, null, List.of(),
                                    val, specs.size(), "No transition enabled from " + prev));
                        }
                    }
                    return result;
                }
            };

            CircularFIFO<Reading> fifo = new CircularFIFO<>(100);
            Sequencer<Reading, RelaxedMembershipResult, RelaxedMembershipAction,
                    Pair<Reading, Set<Configuration>>> sequencer = new Sequencer<>(reporting, fifo, lock);

            long period = (emulatorMode == EmulatorMode.FIXED_PERIOD) ? emulatorPeriodMs : 0;
            Gemini3DEmulator emulator = new Gemini3DEmulator(csvPath, fifo, period, lock);

            // Real deltaT: add delay in sequencer's input listener
            if (emulatorMode == EmulatorMode.REAL_DELTA_T) {
                final Reading[] prev = {null};
                sequencer.onInput((measurement, cfg) -> {
                    if (prev[0] != null) {
                        Double v1 = getTimeValue(prev[0]);
                        Double v2 = getTimeValue(measurement);
                        if (v1 != null && v2 != null) {
                            long ms = Math.min((long) (Math.abs(v2 - v1) * 1000), 5000);
                            if (ms > 0) try { Thread.sleep(ms); } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    prev[0] = measurement;
                });
            }

            t2 = new Thread(sequencer, "T2-Seq");
            t1 = new Thread(emulator, "T1-Emu");
            t2.start(); t1.start(); t1.join(); t2.join();

            if (onFinished != null) onFinished.accept(new FinalResult(counts[0], counts[1], counts[2]));
        } catch (Exception e) {
            if (onError != null) onError.accept(e.getMessage());
        }
    }

    private String inferTransition(String from, String to) {
        for (Transition t : automaton.getTransitions())
            if (t.getFrom().equals(from) && t.getTo().equals(to)) return t.getName();
        return null;
    }

    private List<String> getTransitionsFrom(String state) {
        List<String> r = new ArrayList<>();
        for (Transition t : automaton.getTransitions())
            if (t.getFrom().equals(state)) r.add(t.getName());
        return r;
    }

    private static Double getTimeValue(Reading r) {
        Object td = r.get("time_delta");
        if (td != null && td.toString().contains("days")) {
            try {
                String[] p = td.toString().split(" days ");
                String[] tp = p[1].trim().split(":");
                return Integer.parseInt(p[0].trim()) * 86400.0 + Integer.parseInt(tp[0]) * 3600.0
                        + Integer.parseInt(tp[1]) * 60.0 + Double.parseDouble(tp[2]);
            } catch (Exception e) { /* fallback */ }
        }
        for (String k : r.getValues().keySet()) {
            if (k.equalsIgnoreCase("time") || k.equalsIgnoreCase("t")) {
                Object v = r.get(k);
                if (v instanceof Number) return ((Number) v).doubleValue();
                try { return Double.parseDouble(v.toString().replace(',', '.')); }
                catch (NumberFormatException e) { /* skip */ }
            }
        }
        return (double) r.getIndex();
    }
}