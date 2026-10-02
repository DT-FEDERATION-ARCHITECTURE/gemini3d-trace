package gemini3d.trace.gcode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses G-code produced by the team's toolchain (ADAXIS ADAONE-style output,
 * confirmed against Module2.gcode) into a full {@link GcodeToolpath}.
 *
 * Scope, stated plainly: this is tolerant of the *dialects this project
 * actually produces* -- modal-state omission, whitespace/case variance,
 * unknown/extra params ignored gracefully, ";Layer N" comments, and the
 * ADAXIS "nX nY nZ" surface-normal params. It does not attempt to support
 * arbitrary G-code in general (arcs, canned cycles, tool-change macros,
 * conditional G-code). Unrecognised commands are counted and reported
 * rather than silently dropped -- see {@link GcodeToolpath#getUnsupportedCommands()}.
 *
 * Only G0 (travel) and G1 (linear move, possibly extruding) are interpreted.
 * A G1 counts as EXTRUDE when cumulative E increases by more than
 * {@link #EXTRUDE_EPSILON} versus the previous point; G0 is always TRAVEL
 * even if an E param happens to be present.
 */
public final class GcodeParser {

    private static final double EXTRUDE_EPSILON = 1e-6;

    /** Matches whitespace runs, so multi-space / tab-separated files both work. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** ";Layer 3" / "; layer 3" -- case-insensitive, tolerant of spacing. */
    private static final Pattern LAYER_COMMENT = Pattern.compile("^;\\s*Layer\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private GcodeParser() { }

    public static GcodeToolpath parse(File file) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            return parse(br);
        }
    }

    public static GcodeToolpath parse(BufferedReader br) throws IOException {
        // Modal state -- null means "never set", carried forward once set.
        Double mX = null, mY = null, mZ = null, mE = null, mF = null;
        Double mNX = null, mNY = null, mNZ = null;

        int layer = 0;
        boolean sawAnyLayerComment = false;
        double lastExtrudeZ = Double.NaN; // for the Z-jump fallback when there are no layer comments

        List<ToolpathPoint> points = new ArrayList<>();
        Map<String, Integer> unsupported = new LinkedHashMap<>();

        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        double exMinX = Double.POSITIVE_INFINITY, exMaxX = Double.NEGATIVE_INFINITY;
        double exMinY = Double.POSITIVE_INFINITY, exMaxY = Double.NEGATIVE_INFINITY;
        double exMinZ = Double.POSITIVE_INFINITY, exMaxZ = Double.NEGATIVE_INFINITY;

        double cumLen = 0.0;
        double totalExtrudeLen = 0.0;
        Double prevX = null, prevY = null, prevZ = null;

        String rawLine;
        int lineNo = 0;
        while ((rawLine = br.readLine()) != null) {
            lineNo++;
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith(";")) {
                Matcher m = LAYER_COMMENT.matcher(line);
                if (m.find()) {
                    layer = Integer.parseInt(m.group(1));
                    sawAnyLayerComment = true;
                }
                continue; // full-line comment, nothing else to parse
            }

            // Strip an inline comment, if any.
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi).trim();
            if (line.isEmpty()) continue;

            String[] toks = WHITESPACE.split(line);
            String cmd = toks[0].toUpperCase();

            if (!cmd.equals("G0") && !cmd.equals("G1")) {
                unsupported.merge(cmd, 1, Integer::sum);
                continue;
            }

            Double eBefore = mE;
            boolean sawE = false;

            for (int i = 1; i < toks.length; i++) {
                String tok = toks[i];
                if (tok.isEmpty()) continue;

                // Two-letter surface-normal params ("nX0.000", "nY0.000", "nZ-1.000")
                // must be checked before the generic single-letter parse below, since
                // e.g. "nX0.000" is not a valid number after stripping only one char.
                if (tok.length() >= 2 && Character.toUpperCase(tok.charAt(0)) == 'N') {
                    char axis = Character.toUpperCase(tok.charAt(1));
                    if (axis == 'X' || axis == 'Y' || axis == 'Z') {
                        Double nval = parseDouble(tok.substring(2));
                        if (nval != null) {
                            if (axis == 'X') mNX = nval;
                            else if (axis == 'Y') mNY = nval;
                            else mNZ = nval;
                        }
                        continue;
                    }
                }

                char letter = Character.toUpperCase(tok.charAt(0));
                String numPart = tok.substring(1);
                Double val = parseDouble(numPart);
                if (val == null) continue; // unknown/unparseable param -- ignored on purpose

                switch (letter) {
                    case 'X': mX = val; break;
                    case 'Y': mY = val; break;
                    case 'Z': mZ = val; break;
                    case 'E': mE = val; sawE = true; break;
                    case 'F': mF = val; break;
                    default: break; // unknown single-letter param -- ignored on purpose
                }
            }

            if (mX == null || mY == null || mZ == null) {
                // No position established yet (shouldn't happen after line 1 in practice) -- skip.
                continue;
            }

            double dE = (sawE && eBefore != null) ? (mE - eBefore) : 0.0;
            MoveType type = (cmd.equals("G1") && dE > EXTRUDE_EPSILON) ? MoveType.EXTRUDE : MoveType.TRAVEL;

            // Layer fallback: if this file never uses ";Layer N" comments, infer layers
            // from Z climbing past the last extrude Z. Comment-based layers (when present)
            // are authoritative and this block does nothing in that case.
            if (!sawAnyLayerComment && type == MoveType.EXTRUDE) {
                if (layer == 0) {
                    layer = 1; // first extrude move ever, with no comments to go on
                } else if (!Double.isNaN(lastExtrudeZ) && mZ > lastExtrudeZ + EXTRUDE_EPSILON) {
                    layer++;
                }
                lastExtrudeZ = mZ;
            }

            if (prevX != null) {
                double dx = mX - prevX, dy = mY - prevY, dz = mZ - prevZ;
                double segLen = Math.sqrt(dx * dx + dy * dy + dz * dz);
                cumLen += segLen;
                if (type == MoveType.EXTRUDE) totalExtrudeLen += segLen;
            }
            prevX = mX; prevY = mY; prevZ = mZ;

            double nx = mNX != null ? mNX : 0.0;
            double ny = mNY != null ? mNY : 0.0;
            double nz = mNZ != null ? mNZ : -1.0; // sane default: normal pointing down, matches this toolchain's convention

            points.add(new ToolpathPoint(lineNo, mX, mY, mZ, nx, ny, nz,
                    mE != null ? mE : 0.0, mF != null ? mF : 0.0, type, layer, cumLen));

            minX = Math.min(minX, mX); maxX = Math.max(maxX, mX);
            minY = Math.min(minY, mY); maxY = Math.max(maxY, mY);
            minZ = Math.min(minZ, mZ); maxZ = Math.max(maxZ, mZ);
            if (type == MoveType.EXTRUDE) {
                exMinX = Math.min(exMinX, mX); exMaxX = Math.max(exMaxX, mX);
                exMinY = Math.min(exMinY, mY); exMaxY = Math.max(exMaxY, mY);
                exMinZ = Math.min(exMinZ, mZ); exMaxZ = Math.max(exMaxZ, mZ);
            }
        }

        if (points.isEmpty()) {
            throw new IOException("No G0/G1 moves found -- is this a supported G-code file?");
        }

        GcodeToolpath.Bounds fullBounds = new GcodeToolpath.Bounds(minX, maxX, minY, maxY, minZ, maxZ);
        // If there were no extrude moves at all, fall back to the full bounds rather than +Inf/-Inf.
        GcodeToolpath.Bounds extrudeBounds = Double.isInfinite(exMinX)
                ? fullBounds
                : new GcodeToolpath.Bounds(exMinX, exMaxX, exMinY, exMaxY, exMinZ, exMaxZ);

        int layerCount = points.stream().mapToInt(p -> p.layer).max().orElse(0);

        return new GcodeToolpath(points, fullBounds, extrudeBounds, layerCount, totalExtrudeLen, unsupported);
    }

    /** Same decimal-comma tolerance as Gemini3DEmulator's CSV value parsing, for consistency. */
    private static Double parseDouble(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}