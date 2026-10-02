package gemini3d.trace.gcode;

/**
 * One point on the parsed toolpath = the resolved (X,Y,Z) position after
 * applying modal state, plus everything a renderer or a future arm-orientation
 * step will need.
 *
 * nx/ny/nz is the surface normal (ADAXIS/robotic non-planar deposition
 * convention: "nX nY nZ" params). Not used by the extruder-head-only view
 * yet, but captured now so the parser doesn't need to change again when the
 * full-arm phase needs wrist orientation.
 */
public final class ToolpathPoint {

    public final int lineNumber;      // 1-based source line, for diagnostics
    public final double x, y, z;
    public final double nx, ny, nz;   // surface normal (modal; defaults to 0,0,-1 if never specified)
    public final double e;            // cumulative extrusion value at this point
    public final double f;            // feedrate in effect
    public final MoveType moveType;
    public final int layer;           // 1-based; 0 if no layer info could be determined
    public final double cumulativeLength; // 3D arc length from the first point, in source units

    public ToolpathPoint(int lineNumber, double x, double y, double z,
                         double nx, double ny, double nz,
                         double e, double f, MoveType moveType,
                         int layer, double cumulativeLength) {
        this.lineNumber = lineNumber;
        this.x = x; this.y = y; this.z = z;
        this.nx = nx; this.ny = ny; this.nz = nz;
        this.e = e; this.f = f;
        this.moveType = moveType;
        this.layer = layer;
        this.cumulativeLength = cumulativeLength;
    }

    @Override
    public String toString() {
        return String.format(
                "L%d [%s] (%.3f, %.3f, %.3f) layer=%d len=%.2f",
                lineNumber, moveType, x, y, z, layer, cumulativeLength);
    }
}