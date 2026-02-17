package gemini3d.trace.emulator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reading = State = Measurement (Meas)
 */
public class Reading {

    /** Sentinel value to signal end-of-stream through the FIFO. */
    public static final Reading END_OF_STREAM = new Reading(-1, Collections.emptyMap());

    private final int index;
    private final Map<String, Object> values;

    public Reading(int index, Map<String, Object> values) {
        this.index = index;
        this.values = new LinkedHashMap<>(values);
    }

    public int getIndex() {
        return index;
    }

    public Map<String, Object> getValues() {
        return values;
    }

    public Object get(String column) {
        return values.get(column);
    }

    public Double getDouble(String column) {
        Object val = values.get(column);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Measurement number (m1, m2, m3...) = index + 1 */
    public int getMeasurementNumber() {
        return index + 1;
    }

    public boolean isEndOfStream() {
        return this == END_OF_STREAM;
    }

    public String toStateString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public String toString() {
        if (isEndOfStream()) return "[END_OF_STREAM]";
        return "m" + getMeasurementNumber() + ": " + toStateString();
    }
}