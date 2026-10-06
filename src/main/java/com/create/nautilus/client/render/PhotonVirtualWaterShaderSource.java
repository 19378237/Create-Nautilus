package com.create.nautilus.client.render;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, pre-AST Photon material adjustment for immediate virtual compartment water. */
public final class PhotonVirtualWaterShaderSource {
    private static final String MARKER = "// CREATE_NAUTILUS_PHOTON_VIRTUAL_WATER_MATERIAL";
    private static final Pattern WATER_MATERIAL = Pattern.compile(
            "\\bMaterial\\s+get_water_material\\s*\\(");
    private static final Pattern UNDERGROUND_HIGHLIGHT = Pattern.compile(
            "\\btexture_highlight\\s*\\*=\\s*"
                    + "(1(?:\\.0*)?\\s*-\\s*cube\\s*\\(\\s*linear_step\\s*\\("
                    + "\\s*0(?:\\.0*)?\\s*,\\s*0?\\.50*\\s*,"
                    + "\\s*light_levels\\s*\\.\\s*y\\s*\\)\\s*\\))\\s*;");
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
    private static final Pattern WATER_FOG = Pattern.compile("\\bmat2x3\\s+water_fog_simple\\s*\\(");
    private static final Pattern MINIMUM_WATER_DISTANCE = Pattern.compile(
            "\\bdist\\s*=\\s*(max\\s*\\(\\s*dist\\s*,\\s*2(?:\\.0*)?\\s*-"
                    + "\\s*1(?:\\.0*)?\\s*\\*\\s*skylight_factor\\s*\\))\\s*;");

    private PhotonVirtualWaterShaderSource() {
    }

    /** Real water retains the pack's exact formulas; virtual water has no artificial depth floor. */
    public static String patchFragment(final String source) {
        if (source == null || source.contains(MARKER)) return source;
        if (!withoutComments(source).contains("water_absorption_approx")) return source;
        String patched = patchUndergroundHighlight(source);
        patched = patchMinimumWaterDistance(patched);
        if (patched.equals(source)) return source;
        final String code = withoutComments(patched);
        final Matcher material = WATER_MATERIAL.matcher(code);
        if (!material.find()) return source;
        int declaration = material.start();
        final Matcher fog = WATER_FOG.matcher(code);
        if (fog.find()) declaration = Math.min(declaration, fog.start());
        // Both helpers precede main. Declare the draw-local switch before its first use.
        return patched.substring(0, declaration) + MARKER + "\n"
                + "uniform float " + SodiumOcclusionShaderSource.VIRTUAL_WATER_UNIFORM + ";\n"
                + patched.substring(declaration);
    }

    private static String patchUndergroundHighlight(final String source) {
        final String code = withoutComments(source);
        final Matcher material = WATER_MATERIAL.matcher(code);
        if (!material.find()) return source;
        final int body = code.indexOf('{', material.end());
        if (body < 0) return source;
        int end = body + 1;
        int depth = 1;
        while (end < code.length() && depth > 0) {
            final char value = code.charAt(end++);
            if (value == '{') depth++;
            else if (value == '}') depth--;
        }
        if (depth != 0) return source;
        final Matcher highlight = UNDERGROUND_HIGHLIGHT.matcher(code);
        if (!highlight.find(body) || highlight.end() > end) return source;
        final int gateStart = highlight.start();
        final int gateEnd = highlight.end();
        final String nativeFactor = source.substring(highlight.start(1), highlight.end(1));
        if (highlight.find() && highlight.start() < end) return source;

        // Keep the original factor for real water, but do not reintroduce the cave texture
        // on generated compartment surfaces when the ship's exterior skylight decreases.
        return source.substring(0, gateStart)
                + "texture_highlight *= " + SodiumOcclusionShaderSource.VIRTUAL_WATER_UNIFORM
                + " > 0.5 ? 0.0 : (" + nativeFactor + ");"
                + source.substring(gateEnd);
    }

    private static String patchMinimumWaterDistance(final String source) {
        final String code = withoutComments(source);
        if (!WATER_MATERIAL.matcher(code).find()) return source;
        final Matcher fog = WATER_FOG.matcher(code);
        if (!fog.find()) return source;
        final int body = code.indexOf('{', fog.end());
        if (body < 0) return source;
        int end = body + 1;
        int depth = 1;
        while (end < code.length() && depth > 0) {
            final char value = code.charAt(end++);
            if (value == '{') depth++;
            else if (value == '}') depth--;
        }
        if (depth != 0) return source;
        final Matcher minimum = MINIMUM_WATER_DISTANCE.matcher(code);
        if (!minimum.find(body) || minimum.end() > end) return source;
        final int replacementStart = minimum.start();
        final int replacementEnd = minimum.end();
        final String nativeMinimum = source.substring(minimum.start(1), minimum.end(1));
        if (minimum.find() && minimum.start() < end) return source;
        // The pack's visibility floor turns even a film against a wall into one/two blocks of
        // dark water. Generated room water must tend to transparent as the actual distance -> 0.
        return source.substring(0, replacementStart)
                + "dist = " + SodiumOcclusionShaderSource.VIRTUAL_WATER_UNIFORM
                + " > 0.5 ? max(dist, 0.0) : (" + nativeMinimum + ");"
                + source.substring(replacementEnd);
    }

    private static String withoutComments(final String source) {
        final StringBuilder code = new StringBuilder(source);
        final Matcher comments = COMMENTS.matcher(source);
        while (comments.find()) {
            for (int index = comments.start(); index < comments.end(); index++) {
                if (code.charAt(index) != '\n' && code.charAt(index) != '\r') code.setCharAt(index, ' ');
            }
        }
        return code.toString();
    }
}
