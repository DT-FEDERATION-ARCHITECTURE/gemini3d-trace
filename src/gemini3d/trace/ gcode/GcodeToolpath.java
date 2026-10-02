package gemini3d.trace.gcode;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of parsing one G-code file: the full point list plus everything a
 * caller needs to render or fit-to-view it without re-scanning the points.
 */
public final class GcodeToolpath {

    public static final class Bounds {
        public final double minX, maxX, minY, maxY, minZ, maxZ;

        Bounds(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
            this.minX = minX; this.maxX = maxX;
            this.minY = minY; this.maxY = maxY;
            this.minZ = minZ; this.maxZ = maxZ;
        }

        public double sizeX() { return maxX - minX; }
        public double sizeY() { return maxY - minY; }
        public double sizeZ() { return maxZ - minZ; }

        @Override
        public String toString() {
            return String.format("Bounds[X:%.1f..%.1f Y:%.1f..%.1f Z:%.1f..%.1f]",
                    minX, maxX, minY, maxY, minZ, maxZ);
        }
    }

    private final List<ToolpathPoint> points;
    private final Bounds fullBounds;      // every point, including travel moves
    private final Bounds extrudeBounds;   // only EXTRUDE points -- use this to fit a camera to the actual part
    private final int layerCount;
    private final double totalExtrudeLength;

    /** Commands encountered but not handled (e.g. "G2", "M104"), with a count each. */
    private final Map<String, Integer> unsupportedCommands;

    GcodeToolpath(List<ToolpathPoint> points, Bounds fullBounds, Bounds extrudeBounds,
                  int layerCount, double totalExtrudeLength,
                  Map<String, Integer> unsupportedCommands) {
        this.points = Collections.unmodifiableList(points);
        this.fullBounds = fullBounds;
        this.extrudeBounds = extrudeBounds;
        this.layerCount = layerCount;
        this.totalExtrudeLength = totalExtrudeLength;
        this.unsupportedCommands = Collections.unmodifiableMap(unsupportedCommands);
    }

    public List<ToolpathPoint> getPoints() { return points; }
    public Bounds getFullBounds() { return fullBounds; }
    public Bounds getExtrudeBounds() { return extrudeBounds; }
    public int getLayerCount() { return layerCount; }
    public double getTotalExtrudeLength() { return totalExtrudeLength; }
    public Map<String, Integer> getUnsupportedCommands() { return unsupportedCommands; }
    public boolean hasUnsupportedCommands() { return !unsupportedCommands.isEmpty(); }
}