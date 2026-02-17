package gemini3d.trace;

import gemini3d.trace.emulator.Gemini3DEmulator;
import gemini3d.trace.emulator.Reading;
import gemini3d.trace.fifo.CircularFIFO;
import gemini3d.trace.semantics.MeasAction;
import gemini3d.trace.semantics.StepCip;
import gemini3d.trace.semantics.TraceSemanticsCip;
import gemini3d.trace.sequencer.Sequencer;

import java.time.Duration;
import java.util.Optional;

/**
 * Main -- Digital Twin Orchestrator
 *
 * Architecture (from the boss):
 *
 *   +--[T1: Emulator]--------+   +--[CircularFIFO]--+   +--[T2: Sequencer]------------------+
 *   |                         |   |                   |   |                                   |
 *   | trace.read() -> Reading |   |   ring buffer     |   |  config = sli.initial()           |
 *   | fifo.write(reading)     |==>|   write / read    |==>|  loop:                            |
 *   | sleep(period)           |   |   drops if full   |   |    meas = fifo.read()             |
 *   | loop                    |   |                   |   |    act  = sli.actions(meas, cfg)  |
 *   |                         |   |                   |   |    (out, cfg) = sli.execute(...)   |
 *   +-------------------------+   +-------------------+   |    viewer.display(out)             |
 *                                                         +-----------------------------------+
 *                                                                    |
 *                                                         +--[SLI: TraceSemanticsCip]---+
 *                                                         |  pure semantics (no threads)|
 *                                                         +----------------------------+
 *
 * Boss's pseudocode:
 *
 *   int main()
 *     fifo = new FIFO()
 *     emulator(trace, period, fifo)
 *     Sequencer(sli, fifo, viewer)
 */
public class Main {

    /** Ring buffer capacity. */
    private static final int FIFO_CAPACITY = 15;

    /** Emission period (ms). 0 = full speed, 500 = demo, 40 = real 25Hz sensor. */
    private static final long EMULATOR_PERIOD_MS = 500;

    public static void main(String[] args) {

        final Object printLock = new Object();

        System.out.println();
        System.out.println("+==================================================================+");
        System.out.println("|          GEMINI3D  .  DIGITAL TWIN  .  TRACE SYSTEM              |");
        System.out.println("|==================================================================|");
        System.out.println("|                                                                  |");
        System.out.println("|  +--[T1: Emulator]-+  +--[CircularFIFO]--+  +--[T2: Sequencer]--+");
        System.out.println("|  | CSV -> Reading   |=>| ring buffer      |=>| SLI loop          |");
        System.out.println("|  | fifo.write()     |  | drops if full    |  | sli.execute()     |");
        System.out.println("|  | sleep(period)    |  |                  |  | output -> viewer   |");
        System.out.println("|  +------------------+  +------------------+  +-------------------+");
        System.out.println("|                                                                  |");
        System.out.println("+==================================================================+");
        System.out.println();

        try {
            String csvFile = (args.length > 0) ? args[0] : "egm.csv";

            // ==============================================================
            // 1. FIFO = new CircularFIFO(capacity)
            // ==============================================================
            CircularFIFO<Reading> fifo = new CircularFIFO<>(FIFO_CAPACITY);

            // ==============================================================
            // 2. SLI = new TraceSemanticsCip(durationFunction)
            // ==============================================================
            TraceSemanticsCip<Reading> sli = new TraceSemanticsCip<>((old, current) -> {
                String timeCol = detectTimeColumn(old);
                Double t1 = getTimeSeconds(old, timeCol);
                Double t2 = getTimeSeconds(current, timeCol);
                if (t1 != null && t2 != null) {
                    long nanos = (long) ((t2 - t1) * 1_000_000_000L);
                    return Duration.ofNanos(nanos);
                }
                return Duration.ofSeconds(current.getIndex() - old.getIndex());
            });

            // ==============================================================
            // 3. Sequencer(sli, fifo, viewer)
            // ==============================================================
            Sequencer<Reading, Optional<StepCip<Reading>>, MeasAction, Reading> sequencer =
                    new Sequencer<>(sli, fifo, printLock);
            sequencer.setShowTracking(true);

            // -- viewer.display(output) -- will become NextJS API later --
            sequencer.onOutput(output -> {
                synchronized (printLock) {
                    if (output.isPresent()) {
                        StepCip<Reading> step = output.get();
                        double dt = step.t().toNanos() / 1_000_000_000.0;
                        System.out.println("            +--[STEP OUTPUT]----------------------------------+");
                        System.out.println("            | conf:(" + step.last()
                                + ", deltaT=" + String.format("%.6f", dt) + "s, "
                                + step.current() + ")");
                        System.out.println("            +------------------------------------------------+");
                        System.out.println("            --> [Inclusion]");
                    } else {
                        System.out.println("            (first reading -- no step yet, config initialized)");
                    }
                }
            });

            // ==============================================================
            // 4. Emulator(trace, period, fifo)
            // ==============================================================
            Gemini3DEmulator emulator = new Gemini3DEmulator(
                    csvFile, fifo, EMULATOR_PERIOD_MS, printLock);
            emulator.setShowTracking(true);

            // ==============================================================
            // 5. Launch threads: T2 first (consumer), then T1 (producer)
            // ==============================================================
            Thread t2 = new Thread(sequencer, "T2-Sequencer");
            Thread t1 = new Thread(emulator,  "T1-Emulator");

            System.out.println("[MAIN] Starting T2 (Sequencer)...");
            t2.start();

            System.out.println("[MAIN] Starting T1 (Emulator)...");
            t1.start();

            // ==============================================================
            // 6. Wait for completion
            // ==============================================================
            t1.join();
            System.out.println("[MAIN] T1 (Emulator) finished.");

            t2.join();
            System.out.println("[MAIN] T2 (Sequencer) finished.");

            // ==============================================================
            // 7. Final report
            // ==============================================================
            System.out.println();
            System.out.println("+==================================================================+");
            System.out.println("|                         FINAL REPORT                             |");
            System.out.println("+==================================================================+");
            System.out.printf("|  T1 Emulator          : %5d readings written                   |%n",
                    emulator.getReadingsProduced());
            System.out.printf("|  CircularFIFO          : capacity=%d, peak=%d                    |%n",
                    fifo.getCapacity(), fifo.getPeakSize());
            System.out.printf("|  CircularFIFO          : %5d written / %5d read / %5d dropped|%n",
                    fifo.getTotalWritten(), fifo.getTotalRead(), fifo.getTotalDropped());
            System.out.printf("|  T2 Sequencer          : %5d inputs / %5d outputs              |%n",
                    sequencer.getInputsProcessed(), sequencer.getOutputsProduced());
            System.out.printf("|  FIFO remaining        : %5d                                   |%n",
                    fifo.size());
            System.out.println("+==================================================================+");

        } catch (Exception e) {
            System.err.println("[MAIN] Fatal error:");
            e.printStackTrace();
        }
    }

    // --- Time helpers --------------------------------------------------------

    private static String detectTimeColumn(Reading reading) {
        for (String key : reading.getValues().keySet()) {
            String lower = key.toLowerCase();
            if (lower.contains("time") || lower.equals("t") || lower.contains("delta")) {
                return key;
            }
        }
        return reading.getValues().keySet().iterator().next();
    }

    private static Double getTimeSeconds(Reading reading, String timeColumn) {
        Object val = reading.get(timeColumn);
        if (val == null) return null;

        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }

        String str = val.toString();

        // Handle "0 days 00:03:36.192123" format
        if (str.contains("days")) {
            return parseTimeDelta(str);
        }

        try {
            return Double.parseDouble(str.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseTimeDelta(String str) {
        try {
            String[] parts = str.split(" days ");
            if (parts.length != 2) return null;
            int days = Integer.parseInt(parts[0].trim());
            String[] timeParts = parts[1].trim().split(":");
            if (timeParts.length != 3) return null;
            int hours   = Integer.parseInt(timeParts[0]);
            int minutes = Integer.parseInt(timeParts[1]);
            double secs = Double.parseDouble(timeParts[2]);
            return days * 86400.0 + hours * 3600.0 + minutes * 60.0 + secs;
        } catch (Exception e) {
            return null;
        }
    }
}