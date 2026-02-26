package gemini3d.trace.membership;

import gemini3d.trace.emulator.Gemini3DEmulator;
import gemini3d.trace.emulator.Reading;
import gemini3d.trace.fifo.CircularFIFO;
import gemini3d.trace.semantics.*;
import gemini3d.trace.sequencer.Sequencer;

import org.digitaltwin.automaton.model.Automaton;
import org.digitaltwin.automaton.model.Transition;
import org.digitaltwin.automaton.parser.AutomatonParser;
import org.digitaltwin.automaton.semantics.Configuration;
import org.digitaltwin.automaton.str.AutomatonOutput;

import java.io.File;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * InclusionDemo — Full integration with DETAILED tracking.
 *
 * Shows every layer of the process:
 *   [EMULATOR]  → reads CSV, produces Reading
 *   [TRACE SLI] → produces (mi, Δt, mi+1)
 *   [SPEC SLI]  → evaluates guards, fires transition, produces (state, {N,V})
 *   [INCLUSION]  → verdict: OK or FAIL
 *
 */
public class InclusionDemo {

    private static final int FIFO_CAPACITY = 100;
    private static final long EMULATOR_PERIOD_MS = 200;

    public static void main(String[] args) {

        final Object printLock = new Object();

        try {
            String csvFile = (args.length > 0) ? args[0] : "egm-fault.csv";
            String automatonFile = (args.length > 1) ? args[1] : "egm-automaton.json";

            // ══════════════════════════════════════════════════════
            // 1. PARSE AUTOMATON
            // ══════════════════════════════════════════════════════
            Automaton automaton = AutomatonParser.parse(new File(automatonFile));

            System.out.println();
            System.out.println("+==================================================================+");
            System.out.println("|              INCLUSION ENGINE — FULL TRACKING                     |");
            System.out.println("+==================================================================+");
            System.out.println("|  Trace file  : " + csvFile);
            System.out.println("|  Automaton   : " + automaton.getName());
            System.out.println("|  States      : " + automaton.getSpace().getStates());
            System.out.println("|  Variables   : " + automaton.getSpace().getVariables());
            System.out.println("|  Transitions : " + automaton.getTransitions().size());
            System.out.println("+==================================================================+");
            System.out.println();

            // ══════════════════════════════════════════════════════
            // 2. TRACE SLI
            // ══════════════════════════════════════════════════════
            TraceSemanticsCip<Reading> traceSli = new TraceSemanticsCip<>((old, current) -> {
                Double t1 = getTimeValue(old);
                Double t2 = getTimeValue(current);
                if (t1 != null && t2 != null) {
                    long nanos = (long) ((t2 - t1) * 1_000_000_000L);
                    return Duration.ofNanos(Math.abs(nanos));
                }
                return Duration.ofSeconds(current.getIndex() - old.getIndex());
            });

            // ══════════════════════════════════════════════════════
            // 3. AUTOMATON SLI (via adapter)
            // ══════════════════════════════════════════════════════
            AutomatonSLIAdapter specSli = new AutomatonSLIAdapter(automaton);

            // ══════════════════════════════════════════════════════
            // 4. MEMBERSHIP (wraps both — IS itself an SLI)
            // ══════════════════════════════════════════════════════
            var membership = MembershipFactory
                    .<Reading, AutomatonOutput, Object, Transition, Configuration>create(
                            traceSli, specSli, false);

            // ══════════════════════════════════════════════════════
            // 5. WRAP membership with detailed logging
            // ══════════════════════════════════════════════════════
            final int[] counts = {0, 0, 0}; // total, ok, fail
            final String[] lastSpecState = {"(initial)"};

            DeterministicIOSemantics<Reading, RelaxedMembershipResult, RelaxedMembershipAction,
                    Pair<Reading, Set<Configuration>>> loggingMembership = new DeterministicIOSemantics<>() {

                @Override
                public Optional<Pair<Reading, Set<Configuration>>> initial() {
                    var result = membership.initial();
                    if (result.isPresent()) {
                        Set<Configuration> specConfigs = result.get().rhs();
                        synchronized (printLock) {
                            System.out.println("┌─ INITIALIZATION ───────────────────────────────────────────┐");
                            System.out.println("│  Trace SLI   : ready (no previous measurement)            │");
                            System.out.println("│  Spec SLI    : " + specConfigs.size() + " initial config(s)");
                            for (Configuration c : specConfigs) {
                                System.out.println("│               " + c);
                                lastSpecState[0] = c.currentState();
                            }
                            System.out.println("│  Membership  : ready                                      │");
                            System.out.println("└───────────────────────────────────────────────────────────┘");
                            System.out.println();
                        }
                    }
                    return result;
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

                    if (result.isPresent()) {
                        counts[0]++;
                        RelaxedMembershipResult verdict = result.get().lhs();
                        Pair<Reading, Set<Configuration>> newConfig = result.get().rhs();
                        Set<Configuration> specConfigs = newConfig.rhs();

                        synchronized (printLock) {
                            System.out.println("┌─ STEP " + counts[0] + " ──────────────────────────────────────────────┐");

                            // EMULATOR layer
                            System.out.println("│");
                            System.out.println("│  [EMULATOR] " + input);

                            // TRACE SLI layer
                            if (counts[0] == 1) {
                                System.out.println("│  [TRACE SLI] first measurement — no step yet");
                            } else {
                                Reading prev = config.lhs();
                                if (prev != null) {
                                    System.out.println("│  [TRACE SLI] step: " + prev.getMeasurementNumber()
                                            + " → " + input.getMeasurementNumber());
                                }
                            }

                            // SPEC SLI layer
                            String prevState = lastSpecState[0];
                            if (!specConfigs.isEmpty()) {
                                Configuration specC = specConfigs.iterator().next();
                                String newState = specC.currentState();

                                System.out.println("│  [SPEC SLI]");

                                // Show key input values
                                Double vitTCP = input.getDouble("Vitesse TCP");
                                Double vitExt = input.getDouble("Vitesse Extrudeur");
                                Double xVal = input.getDouble("x");
                                System.out.printf("│    input values: x=%.1f, vitesseTCP=%.4f, vitesseExtrudeur=%.1f%n",
                                        xVal != null ? xVal : 0.0,
                                        vitTCP != null ? vitTCP : 0.0,
                                        vitExt != null ? vitExt : 0.0);

                                if (!newState.equals(prevState)) {
                                    System.out.println("│    state: " + prevState + " → " + newState
                                            + "  ◀◀◀ STATE CHANGE");
                                } else {
                                    System.out.println("│    state: " + newState);
                                }

                                System.out.println("│    output: " + new org.digitaltwin.automaton.str.AutomatonOutput(
                                        newState, specC.valuation().toMap()));

                                lastSpecState[0] = newState;
                            } else {
                                System.out.println("│  [SPEC SLI] NO CONFIGS ALIVE");
                            }

                            // INCLUSION verdict
                            System.out.println("│");
                            if (verdict instanceof RelaxedMembershipOk) {
                                counts[1]++;
                                System.out.println("│  [INCLUSION] >>> OK");
                            } else {
                                counts[2]++;
                                System.out.println("│  [INCLUSION] >>> FAIL — no valid transition!");
                            }

                            System.out.println("│");
                            System.out.println("└───────────────────────────────────────────────────────────┘");
                            System.out.println();
                        }
                    }
                    return result;
                }
            };

            // ══════════════════════════════════════════════════════
            // 6. FIFO + SEQUENCER
            // ══════════════════════════════════════════════════════
            CircularFIFO<Reading> fifo = new CircularFIFO<>(FIFO_CAPACITY);

            Sequencer<Reading, RelaxedMembershipResult, RelaxedMembershipAction,
                    Pair<Reading, Set<Configuration>>> sequencer =
                    new Sequencer<>(loggingMembership, fifo, printLock);

            // ══════════════════════════════════════════════════════
            // 7. EMULATOR
            // ══════════════════════════════════════════════════════
            Gemini3DEmulator emulator = new Gemini3DEmulator(
                    csvFile, fifo, EMULATOR_PERIOD_MS, printLock);

            // ══════════════════════════════════════════════════════
            // 8. LAUNCH
            // ══════════════════════════════════════════════════════
            Thread t2 = new Thread(sequencer, "T2-Sequencer");
            Thread t1 = new Thread(emulator, "T1-Emulator");

            System.out.println("[MAIN] Starting T2 (Sequencer + Inclusion)...");
            t2.start();

            System.out.println("[MAIN] Starting T1 (Emulator from " + csvFile + ")...");
            System.out.println();
            t1.start();

            t1.join();
            t2.join();

            // ══════════════════════════════════════════════════════
            // 9. FINAL REPORT
            // ══════════════════════════════════════════════════════
            System.out.println();
            System.out.println("+==================================================================+");
            System.out.println("|                    INCLUSION REPORT                               |");
            System.out.println("+==================================================================+");
            System.out.printf("|  Trace       : %s (%d measurements)%n", csvFile, emulator.getReadingsProduced());
            System.out.printf("|  Automaton   : %s%n", automaton.getName());
            System.out.printf("|  Total steps : %d%n", counts[0]);
            System.out.printf("|  OK          : %d%n", counts[1]);
            System.out.printf("|  FAIL        : %d%n", counts[2]);
            System.out.println("|  Mode        : RELAXED");
            System.out.println("+------------------------------------------------------------------+");
            if (counts[2] == 0) {
                System.out.println("|  VERDICT: TRACE CONFORMS TO SPECIFICATION                       |");
            } else {
                System.out.println("|  VERDICT: VIOLATIONS DETECTED (" + counts[2] + " failures)");
            }
            System.out.println("+==================================================================+");

        } catch (Exception e) {
            System.err.println("[MAIN] Fatal error:");
            e.printStackTrace();
        }
    }

    private static Double getTimeValue(Reading reading) {
        for (String key : reading.getValues().keySet()) {
            String lower = key.toLowerCase();
            if (lower.contains("time") && !lower.contains("delta")) {
                // skip time_delta, use date or numeric time
                continue;
            }
            if (lower.equals("time") || lower.equals("t")) {
                Object val = reading.get(key);
                if (val instanceof Number) return ((Number) val).doubleValue();
                try {
                    return Double.parseDouble(val.toString().replace(',', '.'));
                } catch (NumberFormatException e) { /* skip */ }
            }
        }
        // Fallback: use time_delta parsed
        Object td = reading.get("time_delta");
        if (td != null) {
            String str = td.toString();
            if (str.contains("days")) return parseTimeDelta(str);
        }
        return (double) reading.getIndex();
    }

    private static Double parseTimeDelta(String str) {
        try {
            String[] parts = str.split(" days ");
            if (parts.length != 2) return null;
            int days = Integer.parseInt(parts[0].trim());
            String[] timeParts = parts[1].trim().split(":");
            if (timeParts.length != 3) return null;
            int hours = Integer.parseInt(timeParts[0]);
            int minutes = Integer.parseInt(timeParts[1]);
            double secs = Double.parseDouble(timeParts[2]);
            return days * 86400.0 + hours * 3600.0 + minutes * 60.0 + secs;
        } catch (Exception e) {
            return null;
        }
    }
}