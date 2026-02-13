package gemini3d.trace.emulator;

import gemini3d.trace.fifo.CircularFIFO;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini3DEmulator -- Producer (T1)
 *
 * Simulates the Gemini3D robot sensor by reading a CSV trace file
 * line-by-line and writing each measurement into the CircularFIFO.
 *
 * In production, this would be replaced by a real sensor socket/API.
 * The contract is: "I produce measurements, I write them to the FIFO,
 * I don't care who consumes them or how fast."
 *
 * The period parameter controls the emission rate:
 *   - 0ms     = full speed (for batch replay)
 *   - 40ms    = ~25Hz (real Gemini3D sensor rate)
 *   - custom  = any rate for testing
 *
 * Pseudocode (from the architecture):
 *
 *   emulator(trace, period, fifo):
 *     while (true)
 *       var measurement = trace.read()
 *       fifo.write(measurement)
 *       Thread.sleep(period)
 *
 * Implements Runnable so it can be launched as its own thread.
 */
public class Gemini3DEmulator implements Runnable {

    private final String filePath;
    private final CircularFIFO<Reading> fifo;
    private final long periodMs;
    private final Object printLock;

    private char delimiter;
    private List<String> columns;

    // Tracking
    private boolean showTracking = false;

    // Stats
    private int readingsProduced = 0;
    private long startTimeMs;
    private long endTimeMs;

    /**
     * @param filePath  path to the CSV trace file
     * @param fifo      the shared CircularFIFO to write measurements into
     * @param periodMs  milliseconds to sleep between emissions (0 = full speed)
     * @param printLock shared lock for tracking output
     */
    public Gemini3DEmulator(String filePath, CircularFIFO<Reading> fifo,
                            long periodMs, Object printLock) {
        this.filePath = filePath;
        this.fifo = fifo;
        this.periodMs = periodMs;
        this.printLock = printLock;
        this.columns = new ArrayList<>();
    }

    public void setShowTracking(boolean show) {
        this.showTracking = show;
    }

    public List<String> getColumns()       { return columns; }
    public int getReadingsProduced()       { return readingsProduced; }
    public long getElapsedMs()             { return endTimeMs - startTimeMs; }

    // --- Runnable -------------------------------------------------------------

    @Override
    public void run() {
        try {
            start();
        } catch (IOException e) {
            System.err.println("[T1: EMULATOR] Fatal I/O error: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("[T1: EMULATOR] Interrupted -- shutting down.");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Read the CSV line-by-line, write each Reading into the FIFO,
     * sleep between emissions, then close the FIFO when done.
     */
    public void start() throws IOException, InterruptedException {
        startTimeMs = System.currentTimeMillis();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {

            // -- Header -------------------------------------------------------
            String headerLine = br.readLine();
            if (headerLine == null) {
                throw new IOException("CSV file is empty!");
            }

            delimiter = detectDelimiter(headerLine);
            columns.clear();
            for (String h : headerLine.split(String.valueOf(delimiter))) {
                columns.add(h.trim());
            }

            if (showTracking) {
                synchronized (printLock) {
                    System.out.println("+--[T1: GEMINI3D EMULATOR]--------------------------------------+");
                    System.out.println("|  File      : " + filePath);
                    System.out.println("|  Columns   : " + columns);
                    System.out.println("|  Delimiter : '" + (delimiter == '\t' ? "TAB" : delimiter) + "'");
                    System.out.println("|  Period    : " + (periodMs == 0 ? "full speed" : periodMs + "ms"));
                    System.out.println("|  FIFO      : CircularFIFO (capacity=" + fifo.getCapacity() + ")");
                    System.out.println("+---------------------------------------------------------------+");
                    System.out.println();
                }
            }

            // -- Stream line by line ------------------------------------------
            String line;
            int index = 0;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Reading reading = parseLine(index, line);

                if (showTracking) {
                    synchronized (printLock) {
                        System.out.println("[T1: EMULATOR] CSV line " + (index + 1)
                                + " -> " + reading);
                        System.out.println("[T1: EMULATOR] ==> fifo.write(m"
                                + reading.getMeasurementNumber() + ")"
                                + "                     [fifo: " + fifo.size()
                                + "/" + fifo.getCapacity() + "]");
                    }
                }

                // Write into FIFO -- NEVER blocks (overwrites if full)
                fifo.write(reading);

                readingsProduced++;
                index++;

                // Sleep to simulate real sensor emission rate
                if (periodMs > 0) {
                    Thread.sleep(periodMs);
                }
            }

            // -- Close FIFO: signal consumer that no more data is coming ------
            fifo.close();
            endTimeMs = System.currentTimeMillis();

            if (showTracking) {
                synchronized (printLock) {
                    System.out.println();
                    System.out.println("[T1: EMULATOR] DONE -- " + readingsProduced
                            + " readings written, " + fifo.getTotalDropped() + " dropped, "
                            + getElapsedMs() + "ms");
                }
            }
        }
    }

    // --- CSV Parsing ---------------------------------------------------------

    private Reading parseLine(int index, String line) {
        String[] values = line.split(String.valueOf(delimiter), -1);
        Map<String, Object> data = new LinkedHashMap<>();

        for (int i = 0; i < columns.size() && i < values.length; i++) {
            data.put(columns.get(i), parseValue(values[i].trim()));
        }

        return new Reading(index, data);
    }

    private Object parseValue(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            String num = value.replace(',', '.');
            if (num.contains(".")) return Double.parseDouble(num);
            else return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private char detectDelimiter(String headerLine) {
        int s = count(headerLine, ';');
        int c = count(headerLine, ',');
        int t = count(headerLine, '\t');
        if (t >= s && t >= c) return '\t';
        if (s >= c) return ';';
        return ',';
    }

    private int count(String s, char c) {
        int n = 0;
        for (char ch : s.toCharArray()) if (ch == c) n++;
        return n;
    }
}