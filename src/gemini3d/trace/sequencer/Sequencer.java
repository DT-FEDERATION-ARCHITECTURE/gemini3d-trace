package gemini3d.trace.sequencer;

import gemini3d.trace.fifo.CircularFIFO;
import gemini3d.trace.semantics.DeterministicIOSemantics;
import gemini3d.trace.semantics.DeterministicIOSemantics.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sequencer -- Generic SLI execution engine.
 *
 * The Sequencer drives any DeterministicIOSemantics by:
 *   1. Getting the initial configuration from the SLI
 *   2. Reading inputs from the CircularFIFO
 *   3. Calling sli.actions() and sli.execute() in a loop
 *   4. Forwarding outputs to a viewer/listener
 *
 * Pseudocode (from the architecture):
 *
 *   Sequencer(sli, fifo, viewer):
 *     current = sli.initial().any
 *     while (current != NULL)
 *       var measurement = fifo.read()
 *       action = sli.actions(measurement, current).any
 *       if (action == NULL) break
 *       (output, current) = sli.execute(action, measurement, current).any
 *       viewer.display(output)
 *
 * The Sequencer doesn't know what kind of semantics it's running.
 * Swap the SLI, get different behavior. Same Sequencer.
 *
 * Type parameters:
 *   I -- Input type  (e.g., Reading/Meas)
 *   O -- Output type (e.g., Optional(StepCip))
 *   A -- Action type (e.g., MeasAction)
 *   C -- Config type (e.g., Reading/Meas -- the "last" measurement)
 */
public class Sequencer<I, O, A, C> implements Runnable {

    private final DeterministicIOSemantics<I, O, A, C> sli;
    private final CircularFIFO<I> fifo;
    private final Object printLock;

    // Tracking
    private boolean showTracking = false;

    // Listeners (viewer replacement for now -- will become NextJS API later)
    private final List<Consumer<O>> outputListeners;
    private final List<BiConsumer<I, C>> inputListeners;

    // Stats
    private int inputsProcessed = 0;
    private int outputsProduced = 0;
    private long startTimeMs;
    private long endTimeMs;

    public Sequencer(DeterministicIOSemantics<I, O, A, C> sli,
                     CircularFIFO<I> fifo,
                     Object printLock) {
        this.sli = sli;
        this.fifo = fifo;
        this.printLock = printLock;
        this.outputListeners = new ArrayList<>();
        this.inputListeners  = new ArrayList<>();
    }

    public void setShowTracking(boolean show) {
        this.showTracking = show;
    }

    /**
     * Registers a listener for outputs (Steps).
     * This is the viewer.display(output) from the pseudocode.
     * Will become a NextJS API endpoint later.
     */
    public void onOutput(Consumer<O> listener) {
        outputListeners.add(listener);
    }

    /**
     * Registers a listener for inputs (for tracking/display).
     * Called each time a measurement is read from the FIFO.
     */
    public void onInput(BiConsumer<I, C> listener) {
        inputListeners.add(listener);
    }

    // --- Runnable -------------------------------------------------------------

    @Override
    public void run() {
        try {
            start();
        } catch (InterruptedException e) {
            System.err.println("[T2: SEQUENCER] Interrupted -- shutting down.");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The SLI execution loop.
     *
     * Follows the boss's pseudocode exactly:
     *   current = sli.initial().any
     *   loop:
     *     measurement = fifo.read()
     *     action = sli.actions(measurement, current).any
     *     (output, current) = sli.execute(action, measurement, current).any
     */
    public void start() throws InterruptedException {
        startTimeMs = System.currentTimeMillis();

        // 1. Get initial configuration from SLI
        C current = sli.initial().orElse(null);
        // For trace semantics: current = null (no previous measurement yet)

        if (showTracking) {
            synchronized (printLock) {
                System.out.println("[T2: SEQUENCER] Started. Initial config: "
                        + (current == null ? "null (awaiting first input)" : current));
                System.out.println("[T2: SEQUENCER] Blocking on fifo.read()...");
                System.out.println();
            }
        }

        // 2. Main execution loop
        while (true) {

            // -- Read next input from FIFO (blocks if empty) --
            I measurement = fifo.read();

            // -- null means FIFO is closed (end of stream) --
            if (measurement == null) {
                if (showTracking) {
                    synchronized (printLock) {
                        System.out.println("[T2: SEQUENCER] FIFO closed. End of stream.");
                    }
                }
                break;
            }

            inputsProcessed++;

            // Notify input listeners (tracking)
            for (BiConsumer<I, C> l : inputListeners) {
                l.accept(measurement, current);
            }

            if (showTracking) {
                synchronized (printLock) {
                    System.out.println("            [FIFO] ---> [SEQUENCER]         [fifo: " + fifo.size() + "]");
                    System.out.println("            [T2: SEQUENCER] fifo.read() -> " + measurement);
                }
            }

            // -- Ask the SLI what action to take --
            Optional<A> actionOpt = sli.actions(measurement, current);
            if (actionOpt.isEmpty()) {
                if (showTracking) {
                    synchronized (printLock) {
                        System.out.println("            [T2: SEQUENCER] sli.actions() -> empty. Stopping.");
                    }
                }
                break;
            }
            A action = actionOpt.get();

            if (showTracking) {
                synchronized (printLock) {
                    System.out.println("            [T2: SEQUENCER] sli.actions() -> " + action);
                }
            }

            // -- Execute the action --
            Optional<Pair<O, C>> resultOpt = sli.execute(action, measurement, current);
            if (resultOpt.isEmpty()) {
                if (showTracking) {
                    synchronized (printLock) {
                        System.out.println("            [T2: SEQUENCER] sli.execute() -> empty. Stopping.");
                    }
                }
                break;
            }

            O output   = resultOpt.get().lhs();
            current    = resultOpt.get().rhs();
            outputsProduced++;

            if (showTracking) {
                synchronized (printLock) {
                    System.out.println("            [T2: SEQUENCER] sli.execute() -> output + new config");
                }
            }

            // -- Forward output to viewer/listeners --
            for (Consumer<O> l : outputListeners) {
                l.accept(output);
            }

            if (showTracking) {
                synchronized (printLock) {
                    System.out.println();
                }
            }
        }

        endTimeMs = System.currentTimeMillis();

        if (showTracking) {
            synchronized (printLock) {
                System.out.println("[T2: SEQUENCER] DONE -- " + inputsProcessed
                        + " inputs, " + outputsProduced + " outputs in "
                        + getElapsedMs() + "ms");
            }
        }
    }

    // --- Stats ---------------------------------------------------------------

    public int getInputsProcessed()  { return inputsProcessed; }
    public int getOutputsProduced()  { return outputsProduced; }
    public long getElapsedMs()       { return endTimeMs - startTimeMs; }
}