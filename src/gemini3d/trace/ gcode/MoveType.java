package gemini3d.trace.gcode;

/**
 * Whether a toolpath point was reached while depositing material (EXTRUDE)
 * or while moving without depositing (TRAVEL).
 *
 * Classified by delta-E (cumulative extrusion increase), not by the G0/G1
 * command alone -- some dialects issue G1 for travel moves too.
 */
public enum MoveType {
    TRAVEL,
    EXTRUDE
}