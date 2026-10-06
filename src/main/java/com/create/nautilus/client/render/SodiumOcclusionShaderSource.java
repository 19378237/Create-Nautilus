package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import net.minecraft.resources.ResourceLocation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Injects Nautilus' parent-water fragment mask into Sodium terrain shaders.
 *
 * <p>With Iris, each supported water program exports a stable pre-wave camera-relative lookup
 * position together with its final wave-displaced world height. The former owns voxel selection;
 * the latter owns the continuous water-level comparison. This prevents TAA and waves from moving
 * a whole surface across a half-open voxel boundary. The atlas is an ordinary two-dimensional
 * float texture and never exercises the unstable three-dimensional texture path. Without Iris,
 * the existing Sable close/far depth-mask path remains unchanged.</p>
 */
public final class SodiumOcclusionShaderSource {
    public static final String ENABLE_UNIFORM = "NautilusWaterOcclusionEnabled";
    public static final String SCREEN_SIZE_UNIFORM = "NautilusWaterOcclusionScreenSize";
    public static final String VIEWPORT_ORIGIN_UNIFORM =
            "NautilusWaterOcclusionViewportOrigin";
    public static final String DEPTH_SCALE_UNIFORM = "NautilusWaterOcclusionDepthScale";
    public static final String DEPTH_RANGE_SCALE_UNIFORM = "NautilusDepthRangeScale";
    public static final String DEPTH_RANGE_BIAS_UNIFORM = "NautilusDepthRangeBias";
    public static final String CLOSE_SAMPLER_UNIFORM = "NautilusWaterOcclusionCloseSampler";
    public static final String FAR_SAMPLER_UNIFORM = "NautilusWaterOcclusionFarSampler";
    public static final String VIRTUAL_WATER_UNIFORM = "NautilusVirtualWater";
    public static final String DEPTH_ACTIVE_UNIFORM = "NautilusDepthMasksActive";
    public static final String WATER_BLOCK_ID_UNIFORM = "NautilusWaterBlockId";
    public static final String FLUID_VARYING = "NautilusFluidGeometry";
    public static final String VOXEL_BASE_AND_HEIGHT_VARYING =
            "NautilusVoxelBaseAndRenderedY";
    public static final String VOXEL_POSITION_READY_UNIFORM =
            "NautilusVoxelPositionReady";

    /** Exact shader-pack water-mask interface, uploaded by {@link ShaderVoxelMaskAtlas}. */
    public static final String VOXEL_ATLAS_UNIFORM = "NautilusVoxelAtlas";
    public static final String VOXEL_ATLAS_SIZE_UNIFORM = "NautilusVoxelAtlasSize";
    public static final String VOXEL_CARRIER_COUNT_UNIFORM = "NautilusCarrierCount";

    /** Shared CPU/GLSL layout constants for the single RGBA32F atlas. */
    public static final int VOXEL_ATLAS_HEADER_TEXELS = 1;
    public static final int VOXEL_CARRIER_DESCRIPTOR_TEXELS = 8;
    public static final int VOXEL_BRICK_SIZE = 8;
    public static final int VOXEL_BRICK_TEXELS = VOXEL_BRICK_SIZE
            * VOXEL_BRICK_SIZE * VOXEL_BRICK_SIZE;
    public static final int MAX_EXACT_FLOAT_TEXELS = 1 << 24;

    /*
     * Kept as Java compatibility aliases while the runtime bridge is migrated.  They are never
     * declared in or consumed by the Iris water shader, so OBB clipping cannot be selected as a
     * fallback.
     */
    @Deprecated public static final String POSITION_VARYING = "NautilusViewPosition";
    @Deprecated public static final String MODEL_VIEW_UNIFORM = "NautilusModelView";
    @Deprecated public static final String CAMERA_POSITION_UNIFORM = "NautilusCameraPosition";
    @Deprecated public static final String COMPARTMENT_COUNT_UNIFORM = "NautilusCompartmentCount";
    @Deprecated public static final String COMPARTMENT_CENTER_UNIFORM = "NautilusCompCenter";
    @Deprecated public static final String COMPARTMENT_AXIS0_UNIFORM = "NautilusCompAxis0";
    @Deprecated public static final String COMPARTMENT_AXIS1_UNIFORM = "NautilusCompAxis1";
    @Deprecated public static final String COMPARTMENT_AXIS2_UNIFORM = "NautilusCompAxis2";
    @Deprecated public static final String COMPARTMENT_HALF_UNIFORM = "NautilusCompHalf";
    @Deprecated public static final String COMPARTMENT_WATER_LEVEL_UNIFORM =
            "NautilusCompWaterLevel";
    @Deprecated public static final int MAX_COMPARTMENTS = 16;

    private static final String FRAGMENT_MARKER =
            "// CREATE_NAUTILUS_VOXEL_WATER_OCCLUSION";
    private static final String VERTEX_MARKER =
            "// CREATE_NAUTILUS_SODIUM_FLUID_MARKER";

    /**
     * Iris stores {@code ((blockId + 1) << 1) | isFluid} in {@code mc_Entity}. Sodium without
     * Iris retains Nautilus' material bit in {@code a_LightAndData.z}.
     */
    private static final Pattern IRIS_ENTITY_UINT = Pattern.compile(
            "(?m)^\\s*(?:(?:flat|smooth|noperspective|centroid|sample)\\s+)*"
                    + "(?:in|attribute)\\s+"
                    + "(?:(?:flat|smooth|noperspective|centroid|sample)\\s+)*"
                    + "uint\\s+mc_Entity\\b");
    private static final Pattern LEGACY_ENTITY_VECTOR = Pattern.compile(
            "(?m)^\\s*(?:(?:flat|smooth|noperspective|centroid|sample)\\s+)*"
                    + "(?:in|attribute)\\s+"
                    + "(?:(?:flat|smooth|noperspective|centroid|sample)\\s+)*"
                    + "vec[234]\\s+mc_Entity\\b");
    private static final Pattern SODIUM_LIGHT_AND_DATA = Pattern.compile(
            "\\b(?:in|attribute)\\s+uvec4\\s+a_LightAndData\\b");
    private static final Pattern MAIN = Pattern.compile(
            "\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern VERSION = Pattern.compile(
            "(?m)^\\s*#version\\s+(\\d+)");
    private static final Pattern PHOTON_CAMERA_POSITION = Pattern.compile(
            "\\bposition_scene\\s*=\\s*animate_vertex\\s*\\(");
    private static final Pattern PHOTON_WATER_ABSORPTION = Pattern.compile(
            "\\bwater_absorption_approx\\s*\\(");
    private static final Pattern PHOTON_REFRACTION_XY_WRITE = Pattern.compile(
            "\\brefraction_data\\s*\\.\\s*xy\\s*=(?!=)");
    private static final Pattern PHOTON_REFRACTION_ZW_WRITE = Pattern.compile(
            "\\brefraction_data\\s*\\.\\s*zw\\s*=(?!=)");
    private static final Pattern BLISS_CAMERA_POSITION = Pattern.compile(
            "\\bgl_Position\\s*=\\s*toClipSpace3\\s*\\(\\s*position\\s*\\)");
    private static final Pattern COMPLEMENTARY_CAMERA_POSITION = Pattern.compile(
            "\\bDoWave\\s*\\(\\s*position\\.xyz\\s*,");
    private static final Pattern COMPLEMENTARY_CLIP_POSITION = Pattern.compile(
            "\\bgl_Position\\s*=\\s*(?:iris_ProjectionMatrix|gl_ProjectionMatrix)"
                    + "\\s*\\*\\s*gbufferModelView\\s*\\*\\s*position\\b");

    private static boolean fluidMarkerFallbackLogged;
    private static boolean positionStrategyFallbackLogged;
    private static boolean vertexInjectPathLogged;
    private static boolean fragmentInjectPathLogged;

    private static final String DEPTH_DISCARD_TEMPLATE = "\n"
            + "    if (" + ENABLE_UNIFORM + " > 0.5"
            + " && " + DEPTH_ACTIVE_UNIFORM + " > 0.5"
            + " && " + VIRTUAL_WATER_UNIFORM + " < 0.5"
            + " && " + FLUID_VARYING + " > 0.5) {\n"
            + "        vec2 nautilusScreenUv = (gl_FragCoord.xy - "
            + VIEWPORT_ORIGIN_UNIFORM + ")"
            + " / max(" + SCREEN_SIZE_UNIFORM + ", vec2(1.0))"
            + " * " + DEPTH_SCALE_UNIFORM + ";\n"
            + "        float nautilusCloseDepth = %s(" + CLOSE_SAMPLER_UNIFORM
            + ", nautilusScreenUv).r;\n"
            + "        float nautilusFarDepth = %s(" + FAR_SAMPLER_UNIFORM
            + ", nautilusScreenUv).r;\n"
            + "        float nautilusStdZ = gl_FragCoord.z"
            + " * " + DEPTH_RANGE_SCALE_UNIFORM + " + " + DEPTH_RANGE_BIAS_UNIFORM + ";\n"
            + "        if (nautilusFarDepth > 0.0001"
            + " && nautilusStdZ > nautilusCloseDepth"
            + " && nautilusStdZ < nautilusFarDepth) {\n"
            + "            discard;\n"
            + "        }\n"
            + "    }\n";

    private static final String VOXEL_DISCARD = "\n"
            + "    if (" + ENABLE_UNIFORM + " > 0.5"
            + " && " + VIRTUAL_WATER_UNIFORM + " < 0.5"
            + " && " + FLUID_VARYING + " > 0.5) {\n"
            + "        vec4 nautilusVoxelSample = "
            + VOXEL_BASE_AND_HEIGHT_VARYING + ";\n"
            + "        vec3 nautilusLookupWorld = nautilusVoxelSample.xyz"
            + " - vec3(0.0, 0.0009765625, 0.0);\n"
            + "        if (dot(nautilusLookupWorld, nautilusLookupWorld) < 1.0E14"
            + " && abs(nautilusVoxelSample.w) < 1.0E7"
            + " && nautilusVoxelAtlasContains(nautilusLookupWorld,"
            + " nautilusVoxelSample.w)) {\n"
            + "            discard;\n"
            + "        }\n"
            + "    }\n";

    private static final String PHOTON_VIRTUAL_WATER_REFRACTION_SUPPRESSION = "\n"
            + "    if (" + VIRTUAL_WATER_UNIFORM + " > 0.5) {\n"
            + "        refraction_data = vec4(0.0);\n"
            + "    }\n";

    private SodiumOcclusionShaderSource() {
    }

    /** Returns the original source unchanged for shader stages Nautilus does not own. */
    public static CharSequence inject(final CharSequence original, final String stage,
                                      final ResourceLocation shaderName) {
        if (!isSafeToUseCustomOcclusion() || !isTerrainShader(shaderName)) {
            return original;
        }
        if ("VERTEX".equals(stage)) {
            return injectVertex(original, shaderName);
        }
        if ("FRAGMENT".equals(stage)) {
            return injectFragment(original, shaderName);
        }
        return original;
    }

    public static boolean isSafeToUseCustomOcclusion() {
        return true;
    }

    private static boolean isTerrainShader(final ResourceLocation shaderName) {
        if (shaderName == null) {
            return false;
        }
        final String namespace = shaderName.getNamespace();
        final String path = shaderName.getPath();
        return ("sodium".equals(namespace) && path.startsWith("blocks/block_layer_opaque"))
                || ("iris".equals(namespace) && "sodium-shader-translucent".equals(path));
    }

    /** Adds the water discriminator and, for Iris, the final physical camera-relative position. */
    private static CharSequence injectVertex(final CharSequence original,
                                             final ResourceLocation shaderName) {
        if (original == null) {
            return null;
        }
        final String source = original.toString();
        if (source.contains(VERTEX_MARKER)) {
            return original;
        }
        final boolean irisWaterProgram = isIrisWaterProgram(shaderName);
        VoxelPositionStrategy positionStrategy = irisWaterProgram
                ? voxelPositionStrategy(source) : VoxelPositionStrategy.UNSUPPORTED;
        MainSpan main;
        if (irisWaterProgram && positionStrategy.supported()) {
            main = mainContaining(source, positionStrategy.anchor(source));
            if (main == null) {
                positionStrategy = VoxelPositionStrategy.UNSUPPORTED;
                logPositionStrategyFallback(
                        "recognized wave layout was not inside the active vertex main");
                main = vertexMain(source);
            }
        } else {
            main = irisWaterProgram ? vertexMain(source) : firstMain(source);
        }
        if (main == null) {
            return original;
        }
        final String expression = fluidMarkerExpression(source);
        if (!vertexInjectPathLogged && irisWaterProgram) {
            vertexInjectPathLogged = true;
            final boolean stableMaterialBit = SODIUM_LIGHT_AND_DATA.matcher(source).find();
            final String marker = stableMaterialBit
                    ? IRIS_ENTITY_UINT.matcher(source).find()
                            ? "stable-material-water-bit+uint-id-fallback"
                            : LEGACY_ENTITY_VECTOR.matcher(source).find()
                                    ? "stable-material-water-bit+vec-id-fallback"
                                    : "stable-material-water-bit"
                    : IRIS_ENTITY_UINT.matcher(source).find() ? "uint-water-id-fallback"
                    : LEGACY_ENTITY_VECTOR.matcher(source).find() ? "vec-blockId-fallback"
                    : "none";
            CreateNautilus.LOGGER.info(
                    "[occlusion] exact voxel water vertex marker={} position={} (GLSL {})",
                    marker, positionStrategy.label(), shaderVersion(source));
        }
        if (!irisWaterProgram) {
            return source.substring(0, main.start())
                    + "\n" + VERTEX_MARKER + "\n"
                    + varyingDeclaration(source, true) + "\n"
                    + "uniform float " + WATER_BLOCK_ID_UNIFORM + ";\n\n"
                    + source.substring(main.start(), main.bodyStart())
                    + "\n    " + FLUID_VARYING + " = " + expression + ";\n"
                    + source.substring(main.bodyStart());
        }
        final String readyUniform = positionStrategy.supported()
                ? "uniform float " + VOXEL_POSITION_READY_UNIFORM + ";\n" : "";
        final String endCapture = positionStrategy.capture(source, expression);
        return source.substring(0, main.start())
                + "\n" + VERTEX_MARKER + "\n"
                + varyingDeclaration(source, true) + "\n"
                + cameraPositionVaryingDeclaration(source, true) + "\n"
                + "uniform float " + WATER_BLOCK_ID_UNIFORM + ";\n"
                + readyUniform + "\n"
                + source.substring(main.start(), main.bodyStart())
                + "\n    " + FLUID_VARYING + " = 0.0;\n"
                + "    " + VOXEL_BASE_AND_HEIGHT_VARYING
                + " = vec4(1.0E20);\n"
                + source.substring(main.bodyStart(), main.close())
                + endCapture
                + source.substring(main.close());
    }

    /** Adds the exact atlas path to Iris water, preserving close/far masks for plain Sodium. */
    private static CharSequence injectFragment(final CharSequence original,
                                               final ResourceLocation shaderName) {
        if (original == null) {
            return null;
        }
        final String source = original.toString();
        if (source.contains(FRAGMENT_MARKER)) {
            return original;
        }
        final boolean irisWaterProgram = isIrisWaterProgram(shaderName);
        final MainSpan photonMain = irisWaterProgram ? photonRefractionMain(source) : null;
        final MainSpan main = photonMain != null ? photonMain : firstMain(source);
        if (main == null) {
            return original;
        }
        final String declarations = irisWaterProgram
                ? voxelAtlasDeclarations(source)
                : depthMaskDeclarations(source);
        final String discard = irisWaterProgram
                ? VOXEL_DISCARD
                : DEPTH_DISCARD_TEMPLATE.formatted(
                        textureFunction(source), textureFunction(source));
        if (!fragmentInjectPathLogged) {
            fragmentInjectPathLogged = true;
            CreateNautilus.LOGGER.info(
                    "[occlusion] water fragment path: {} (GLSL {})",
                    irisWaterProgram ? "exact-voxel-atlas" : "depth-textures",
                    shaderVersion(source));
        }
        final String refractionSuppression = photonMain != null
                ? PHOTON_VIRTUAL_WATER_REFRACTION_SUPPRESSION : "";
        return source.substring(0, main.start())
                + declarations
                + source.substring(main.start(), main.bodyStart())
                + discard
                + source.substring(main.bodyStart(), main.close())
                + refractionSuppression
                + source.substring(main.close());
    }

    private static String voxelAtlasDeclarations(final String source) {
        final String sample = textureFunction(source);
        return "\n" + FRAGMENT_MARKER + "\n"
                + varyingDeclaration(source, false) + "\n"
                + cameraPositionVaryingDeclaration(source, false) + "\n"
                + "uniform float " + ENABLE_UNIFORM + ";\n"
                + "uniform float " + VIRTUAL_WATER_UNIFORM + ";\n"
                + "uniform sampler2D " + VOXEL_ATLAS_UNIFORM + ";\n"
                + "uniform vec2 " + VOXEL_ATLAS_SIZE_UNIFORM + ";\n"
                + "uniform int " + VOXEL_CARRIER_COUNT_UNIFORM + ";\n\n"
                + "bool nautilusValidAtlasIndex(float linearIndex) {\n"
                + "    return linearIndex >= 0.0 && linearIndex < "
                + VOXEL_ATLAS_SIZE_UNIFORM + ".x * "
                + VOXEL_ATLAS_SIZE_UNIFORM + ".y;\n"
                + "}\n\n"
                + "vec4 nautilusAtlasTexel(float linearIndex) {\n"
                + "    float atlasX = mod(linearIndex, "
                + VOXEL_ATLAS_SIZE_UNIFORM + ".x);\n"
                + "    float atlasY = floor(linearIndex / "
                + VOXEL_ATLAS_SIZE_UNIFORM + ".x);\n"
                + "    vec2 atlasUv = (vec2(atlasX, atlasY) + vec2(0.5)) / "
                + VOXEL_ATLAS_SIZE_UNIFORM + ";\n"
                + "    return " + sample + "(" + VOXEL_ATLAS_UNIFORM + ", atlasUv);\n"
                + "}\n\n"
                + "bool nautilusVoxelAtlasContains(vec3 cameraRelativeBase,"
                + " float renderedWorldY) {\n"
                + "    if (" + VOXEL_ATLAS_SIZE_UNIFORM + ".x < 1.0"
                + " || " + VOXEL_ATLAS_SIZE_UNIFORM + ".y < 1.0"
                + " || !nautilusValidAtlasIndex(0.0)) return false;\n"
                + "    vec4 header = nautilusAtlasTexel(0.0);\n"
                + "    if (abs(header.z - " + VOXEL_ATLAS_SIZE_UNIFORM + ".x) > 0.5"
                + " || abs(header.w - " + VOXEL_ATLAS_SIZE_UNIFORM + ".y) > 0.5) return false;\n"
                + "    int headerCount = int(floor(header.x + 0.5));\n"
                + "    int carrierCount = " + VOXEL_CARRIER_COUNT_UNIFORM + ";\n"
                + "    if (carrierCount < 0) carrierCount = 0;\n"
                + "    if (headerCount < 0) headerCount = 0;\n"
                + "    if (headerCount < carrierCount) carrierCount = headerCount;\n"
                + "    float descriptorBase = floor(header.y + 0.5);\n"
                + "    if (descriptorBase < 1.0) return false;\n"
                + "    int descriptorCapacity = int(floor(("
                + VOXEL_ATLAS_SIZE_UNIFORM + ".x * "
                + VOXEL_ATLAS_SIZE_UNIFORM + ".y - descriptorBase) / "
                + VOXEL_CARRIER_DESCRIPTOR_TEXELS + ".0));\n"
                + "    if (descriptorCapacity < 0) descriptorCapacity = 0;\n"
                + "    if (descriptorCapacity < carrierCount) carrierCount = descriptorCapacity;\n"
                + "    for (int carrier = 0; carrier < carrierCount; ++carrier) {\n"
                + "        float descriptor = descriptorBase + float(carrier) * "
                + VOXEL_CARRIER_DESCRIPTOR_TEXELS + ".0;\n"
                + "        if (!nautilusValidAtlasIndex(descriptor + "
                + (VOXEL_CARRIER_DESCRIPTOR_TEXELS - 1) + ".0)) return false;\n"
                + "        vec4 d6 = nautilusAtlasTexel(descriptor + 6.0);\n"
                + "        if (d6.x > d6.z || d6.y > d6.w) continue;\n"
                + "        if (gl_FragCoord.x < d6.x || gl_FragCoord.y < d6.y"
                + " || gl_FragCoord.x >= d6.z || gl_FragCoord.y >= d6.w) continue;\n"
                + "        vec4 d0 = nautilusAtlasTexel(descriptor);\n"
                + "        vec4 d1 = nautilusAtlasTexel(descriptor + 1.0);\n"
                + "        vec4 d2 = nautilusAtlasTexel(descriptor + 2.0);\n"
                + "        vec4 d3 = nautilusAtlasTexel(descriptor + 3.0);\n"
                + "        vec4 d4 = nautilusAtlasTexel(descriptor + 4.0);\n"
                + "        vec4 d5 = nautilusAtlasTexel(descriptor + 5.0);\n"
                + "        vec4 localH = mat4(d0, d1, d2, d3)"
                + " * vec4(cameraRelativeBase, 1.0);\n"
                + "        if (abs(localH.w) < 1.0E-8) continue;\n"
                + "        vec3 localPosition = localH.xyz / localH.w;\n"
                + "        vec3 primaryCell = floor(localPosition);\n"
                + "        vec3 cellFraction = localPosition - primaryCell;\n"
                + "        for (int offsetX = -1; offsetX <= 1; ++offsetX) {\n"
                + "            bool useX = offsetX == 0"
                + " || (offsetX < 0 && cellFraction.x < 0.0009765625)"
                + " || (offsetX > 0 && cellFraction.x > 0.9990234375);\n"
                + "            if (!useX) continue;\n"
                + "            for (int offsetY = -1; offsetY <= 1; ++offsetY) {\n"
                + "                bool useY = offsetY == 0"
                + " || (offsetY < 0 && cellFraction.y < 0.0009765625)"
                + " || (offsetY > 0 && cellFraction.y > 0.9990234375);\n"
                + "                if (!useY) continue;\n"
                + "                for (int offsetZ = -1; offsetZ <= 1; ++offsetZ) {\n"
                + "                    bool useZ = offsetZ == 0"
                + " || (offsetZ < 0 && cellFraction.z < 0.0009765625)"
                + " || (offsetZ > 0 && cellFraction.z > 0.9990234375);\n"
                + "                    if (!useZ) continue;\n"
                + "        vec3 cell = primaryCell"
                + " + vec3(float(offsetX), float(offsetY), float(offsetZ));\n"
                + "        vec3 brick = floor(cell / 8.0);\n"
                + "        vec3 inner = cell - brick * 8.0;\n"
                + "        vec3 brickOrigin = vec3(floor(d4.xy + vec2(0.5)), 0.0);\n"
                + "        vec3 brickDims = floor(vec3(d5.x, d5.y, d4.z) + vec3(0.5));\n"
                + "        vec3 relativeBrick = brick - brickOrigin;\n"
                + "        if (any(lessThan(relativeBrick, vec3(0.0)))"
                + " || any(greaterThanEqual(relativeBrick, brickDims)))"
                + " continue;\n"
                + "        int directoryCount = int(floor(d5.z + 0.5));\n"
                + "        int directoryLow = 0;\n"
                + "        int directoryHigh = directoryCount - 1;\n"
                + "        float payloadPlusOne = 0.0;\n"
                + "        for (int searchStep = 0; searchStep < 24; ++searchStep) {\n"
                + "            if (directoryLow > directoryHigh) break;\n"
                + "            int directoryMid = (directoryLow + directoryHigh) / 2;\n"
                + "            float directoryIndex = floor(d4.w + 0.5)"
                + " + float(directoryMid) * 2.0;\n"
                + "            if (!nautilusValidAtlasIndex(directoryIndex + 1.0)) return false;\n"
                + "            vec3 directoryBrick = floor(nautilusAtlasTexel(directoryIndex).xyz"
                + " + vec3(0.5));\n"
                + "            bool sameBrick = all(lessThan(abs(directoryBrick - relativeBrick),"
                + " vec3(0.5)));\n"
                + "            if (sameBrick) {\n"
                + "                payloadPlusOne = floor(nautilusAtlasTexel(directoryIndex + 1.0).r"
                + " + 0.5);\n"
                + "                break;\n"
                + "            }\n"
                + "            bool directoryBefore = directoryBrick.y < relativeBrick.y"
                + " || (directoryBrick.y == relativeBrick.y"
                + " && (directoryBrick.z < relativeBrick.z"
                + " || (directoryBrick.z == relativeBrick.z"
                + " && directoryBrick.x < relativeBrick.x)));\n"
                + "            if (directoryBefore) directoryLow = directoryMid + 1;\n"
                + "            else directoryHigh = directoryMid - 1;\n"
                + "        }\n"
                + "        if (payloadPlusOne < 1.0) continue;\n"
                + "        float voxelIndex = inner.x + 8.0 * (inner.z + 8.0 * inner.y);\n"
                + "        float payloadIndex = payloadPlusOne - 1.0 + voxelIndex;\n"
                + "        if (!nautilusValidAtlasIndex(payloadIndex)) return false;\n"
                + "        float encodedVoxel = nautilusAtlasTexel(payloadIndex).r;\n"
                // Full glass excludes water even when a supported internal junction has no
                // room/exterior event. Its distinct occupancy sentinel is not a fog-event id.
                // Keep -1 opaque/unknown shells fail-open and leave optical-slot decoding intact.
                + "        if (encodedVoxel == " + ShaderVoxelMaskAtlas.EVENTLESS_OPTICAL_VOLUME
                + ") return true;\n"
                + "        if (encodedVoxel <= -1.5) return true;\n"
                + "        float roomPlusOne = floor(encodedVoxel + 0.5);\n"
                + "        if (roomPlusOne < 1.0) continue;\n"
                + "        float roomIndex = floor(d5.w + 0.5) + roomPlusOne - 1.0;\n"
                + "        if (!nautilusValidAtlasIndex(roomIndex)) return false;\n"
                + "        vec4 room = nautilusAtlasTexel(roomIndex);\n"
                + "        if (room.g > 0.5 && renderedWorldY > room.r + 0.0001)"
                + " return true;\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "    return false;\n"
                + "}\n\n";
    }

    private static String depthMaskDeclarations(final String source) {
        return "\n" + FRAGMENT_MARKER + "\n"
                + varyingDeclaration(source, false) + "\n"
                + "uniform vec2 " + SCREEN_SIZE_UNIFORM + ";\n"
                + "uniform vec2 " + VIEWPORT_ORIGIN_UNIFORM + ";\n"
                + "uniform vec2 " + DEPTH_SCALE_UNIFORM + ";\n"
                + "uniform float " + DEPTH_RANGE_SCALE_UNIFORM + ";\n"
                + "uniform float " + DEPTH_RANGE_BIAS_UNIFORM + ";\n"
                + "uniform sampler2D " + CLOSE_SAMPLER_UNIFORM + ";\n"
                + "uniform sampler2D " + FAR_SAMPLER_UNIFORM + ";\n"
                + "uniform float " + ENABLE_UNIFORM + ";\n"
                + "uniform float " + VIRTUAL_WATER_UNIFORM + ";\n"
                + "uniform float " + DEPTH_ACTIVE_UNIFORM + ";\n\n";
    }

    private static String fluidMarkerExpression(final String source) {
        if (SODIUM_LIGHT_AND_DATA.matcher(source).find()) {
            /*
             * This bit is written by Nautilus while Sodium builds a WATER quad. Unlike Iris'
             * pack-specific mc_Entity id, it remains valid when an already-built chunk mesh is
             * rendered by a newly selected shader pack.
             */
            final String stableBit = "((a_LightAndData.z & "
                    + SodiumFluidMaterialMarker.WATER_MATERIAL_BIT + "u) != 0u)";
            if (IRIS_ENTITY_UINT.matcher(source).find()) {
                return "((" + stableBit + " || (" + WATER_BLOCK_ID_UNIFORM + " >= 0.0"
                        + " && abs(float(int(mc_Entity >> 1u) - 1) - "
                        + WATER_BLOCK_ID_UNIFORM + ") < 0.5)) ? 1.0 : 0.0)";
            }
            if (LEGACY_ENTITY_VECTOR.matcher(source).find()) {
                return "((" + stableBit + " || (" + WATER_BLOCK_ID_UNIFORM + " >= 0.0"
                        + " && abs(mc_Entity.x - " + WATER_BLOCK_ID_UNIFORM
                        + ") < 0.5)) ? 1.0 : 0.0)";
            }
            return "(" + stableBit + " ? 1.0 : 0.0)";
        }
        if (IRIS_ENTITY_UINT.matcher(source).find()) {
            /*
             * Iris packs ((blockId + 1) << 1) | renderType into mc_Entity. The low bit denotes
             * every fluid, including lava, so exact ocean clipping must use the decoded water id.
             * An unresolved pack id deliberately produces 0.0 (fail-open).
             */
            return "((" + WATER_BLOCK_ID_UNIFORM + " >= 0.0"
                    + " && abs(float(int(mc_Entity >> 1u) - 1) - "
                    + WATER_BLOCK_ID_UNIFORM + ") < 0.5) ? 1.0 : 0.0)";
        }
        if (LEGACY_ENTITY_VECTOR.matcher(source).find()) {
            return "((" + WATER_BLOCK_ID_UNIFORM + " >= 0.0"
                    + " && abs(mc_Entity.x - " + WATER_BLOCK_ID_UNIFORM + ") < 0.5)"
                    + " ? 1.0 : 0.0)";
        }
        if (!fluidMarkerFallbackLogged) {
            fluidMarkerFallbackLogged = true;
            CreateNautilus.LOGGER.warn(
                    "[occlusion] no mc_Entity/a_LightAndData fluid marker found; exact voxel "
                            + "water clipping is disabled for this shader pack");
        }
        return "0.0";
    }

    /** Clears one-shot diagnostics whenever Iris recreates its water program. */
    public static void onPipelineReset() {
        fluidMarkerFallbackLogged = false;
        positionStrategyFallbackLogged = false;
        vertexInjectPathLogged = false;
        fragmentInjectPathLogged = false;
    }

    private static boolean isIrisWaterProgram(final ResourceLocation shaderName) {
        return shaderName != null
                && "iris".equals(shaderName.getNamespace())
                && "sodium-shader-translucent".equals(shaderName.getPath());
    }

    private static String varyingDeclaration(final String source, final boolean output) {
        final int version = shaderVersion(source);
        // A source without #version defaults to legacy GLSL, so fail toward `varying` rather
        // than emitting the GLSL 1.30-only flat interface qualifier.
        if (version < 130) {
            return "varying float " + FLUID_VARYING + ";";
        }
        return "flat " + (output ? "out " : "in ") + "float " + FLUID_VARYING + ";";
    }

    /** Stable lookup position and rendered height use perspective interpolation with the water. */
    private static String cameraPositionVaryingDeclaration(final String source,
                                                            final boolean output) {
        if (shaderVersion(source) < 130) {
            return "varying vec4 " + VOXEL_BASE_AND_HEIGHT_VARYING + ";";
        }
        return "smooth " + (output ? "out " : "in ") + "vec4 "
                + VOXEL_BASE_AND_HEIGHT_VARYING + ";";
    }

    private static VoxelPositionStrategy voxelPositionStrategy(final String source) {
        if (PHOTON_CAMERA_POSITION.matcher(source).find()) {
            return VoxelPositionStrategy.PHOTON;
        }
        if (COMPLEMENTARY_CAMERA_POSITION.matcher(source).find()
                || COMPLEMENTARY_CLIP_POSITION.matcher(source).find()
                && source.contains("gbufferModelViewInverse")) {
            return VoxelPositionStrategy.COMPLEMENTARY;
        }
        if (BLISS_CAMERA_POSITION.matcher(source).find()) {
            return VoxelPositionStrategy.BLISS;
        }
        logPositionStrategyFallback("unsupported Iris water vertex position layout");
        return VoxelPositionStrategy.UNSUPPORTED;
    }

    private static void logPositionStrategyFallback(final String reason) {
        if (positionStrategyFallbackLogged) {
            return;
        }
        positionStrategyFallbackLogged = true;
        CreateNautilus.LOGGER.warn(
                "[occlusion] {}; exact voxel clipping will fail open for this shader pack",
                reason);
    }

    private static MainSpan firstMain(final String source) {
        final Matcher main = MAIN.matcher(source);
        if (!main.find()) {
            return null;
        }
        final int close = mainClosingBrace(source, main.end() - 1);
        return close < 0 ? null : new MainSpan(main.start(), main.end(), close);
    }

    /**
     * Selects Photon terrain-translucent's active fragment main from its water absorption and
     * two packed refraction writes. More than one matching main is ambiguous and fails open.
     */
    private static MainSpan photonRefractionMain(final String source) {
        final Matcher main = MAIN.matcher(codeOnly(source, 0, source.length()));
        MainSpan candidate = null;
        while (main.find()) {
            final int close = mainClosingBrace(source, main.end() - 1);
            if (close < 0) {
                return null;
            }
            final String body = codeOnly(source, main.end(), close);
            if (!PHOTON_WATER_ABSORPTION.matcher(body).find()
                    || !PHOTON_REFRACTION_XY_WRITE.matcher(body).find()
                    || !PHOTON_REFRACTION_ZW_WRITE.matcher(body).find()) {
                continue;
            }
            if (candidate != null) {
                return null;
            }
            candidate = new MainSpan(main.start(), main.end(), close);
        }
        return candidate;
    }

    /** Replaces comments and quoted text so Photon feature probes only accept executable GLSL. */
    private static String codeOnly(final String source, final int start, final int end) {
        final StringBuilder code = new StringBuilder(source.substring(start, end));
        boolean lineComment = false;
        boolean blockComment = false;
        boolean quoted = false;
        char quote = 0;
        for (int offset = 0; offset < code.length(); offset++) {
            final char value = code.charAt(offset);
            final char next = offset + 1 < code.length() ? code.charAt(offset + 1) : 0;
            if (lineComment) {
                if (value == '\n' || value == '\r') {
                    lineComment = false;
                } else {
                    code.setCharAt(offset, ' ');
                }
                continue;
            }
            if (blockComment) {
                code.setCharAt(offset, ' ');
                if (value == '*' && next == '/') {
                    code.setCharAt(++offset, ' ');
                    blockComment = false;
                }
                continue;
            }
            if (quoted) {
                code.setCharAt(offset, ' ');
                if (value == '\\' && offset + 1 < code.length()) {
                    code.setCharAt(++offset, ' ');
                } else if (value == quote) {
                    quoted = false;
                }
                continue;
            }
            if (value == '/' && next == '/') {
                code.setCharAt(offset, ' ');
                code.setCharAt(++offset, ' ');
                lineComment = true;
            } else if (value == '/' && next == '*') {
                code.setCharAt(offset, ' ');
                code.setCharAt(++offset, ' ');
                blockComment = true;
            } else if (value == '"' || value == '\'') {
                code.setCharAt(offset, ' ');
                quoted = true;
                quote = value;
            }
        }
        return code.toString();
    }

    /** Best-effort active VS main for unknown packs; fragment mains cannot reference gl_Position. */
    private static MainSpan vertexMain(final String source) {
        final Matcher main = MAIN.matcher(source);
        MainSpan first = null;
        while (main.find()) {
            final int close = mainClosingBrace(source, main.end() - 1);
            if (close < 0) {
                return null;
            }
            final MainSpan candidate = new MainSpan(main.start(), main.end(), close);
            if (first == null) {
                first = candidate;
            }
            if (source.substring(main.end(), close).contains("gl_Position")) {
                return candidate;
            }
        }
        return first;
    }

    /** Selects the active vertex main which structurally contains the pack-specific wave anchor. */
    private static MainSpan mainContaining(final String source, final int anchor) {
        if (anchor < 0) {
            return firstMain(source);
        }
        final Matcher main = MAIN.matcher(source);
        while (main.find()) {
            final int close = mainClosingBrace(source, main.end() - 1);
            if (close < 0) {
                return null;
            }
            if (anchor >= main.end() && anchor < close) {
                return new MainSpan(main.start(), main.end(), close);
            }
        }
        return null;
    }

    /** Finds main's structural closing brace while ignoring braces inside comments and strings. */
    private static int mainClosingBrace(final String source, final int openingBrace) {
        int depth = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean quoted = false;
        char quote = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            final char value = source.charAt(index);
            final char next = index + 1 < source.length() ? source.charAt(index + 1) : 0;
            if (lineComment) {
                if (value == '\n' || value == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (quoted) {
                if (value == '\\') {
                    index++;
                } else if (value == quote) {
                    quoted = false;
                }
                continue;
            }
            if (value == '/' && next == '/') {
                lineComment = true;
                index++;
            } else if (value == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (value == '"' || value == '\'') {
                quoted = true;
                quote = value;
            } else if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private enum VoxelPositionStrategy {
        PHOTON("photon-position-scene"),
        COMPLEMENTARY("complementary-post-wave"),
        BLISS("bliss-post-wave"),
        UNSUPPORTED("unsupported");

        private final String label;

        VoxelPositionStrategy(final String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        boolean supported() {
            return this != UNSUPPORTED;
        }

        int anchor(final String source) {
            final Pattern pattern = switch (this) {
                case PHOTON -> PHOTON_CAMERA_POSITION;
                case COMPLEMENTARY -> COMPLEMENTARY_CAMERA_POSITION.matcher(source).find()
                        ? COMPLEMENTARY_CAMERA_POSITION : COMPLEMENTARY_CLIP_POSITION;
                case BLISS -> BLISS_CAMERA_POSITION;
                case UNSUPPORTED -> null;
            };
            if (pattern == null) {
                return -1;
            }
            final Matcher anchor = pattern.matcher(source);
            return anchor.find() ? anchor.start() : -1;
        }

        String capture(final String source, final String fluidExpression) {
            if (!supported()) {
                return "";
            }
            final String body = switch (this) {
                case PHOTON -> "        vec3 nautilusBasePosition = getVertexPosition().xyz;\n"
                        + "        vec3 nautilusPreWavePosition = view_to_scene_space("
                        + "transform(iris_ModelViewMatrix, nautilusBasePosition));\n"
                        + "        vec3 nautilusPostWavePosition = nautilusBasePosition"
                        + " + (position_scene - nautilusPreWavePosition);\n"
                        + "        " + VOXEL_BASE_AND_HEIGHT_VARYING
                        + " = vec4(nautilusBasePosition, nautilusPostWavePosition.y);\n"
                        + "        " + FLUID_VARYING + " = " + fluidExpression + ";\n";
                case COMPLEMENTARY ->
                        "        vec3 nautilusBasePosition = getVertexPosition().xyz;\n"
                        + "        vec4 nautilusPreWaveH = gbufferModelViewInverse"
                        + " * iris_ModelViewMatrix * vec4(nautilusBasePosition, 1.0);\n"
                        + "        if (abs(nautilusPreWaveH.w) > 1.0E-8"
                        + " && abs(position.w) > 1.0E-8) {\n"
                        + "            vec3 nautilusPreWavePosition = nautilusPreWaveH.xyz"
                        + " / nautilusPreWaveH.w;\n"
                        + "            vec3 nautilusPostWavePosition = position.xyz / position.w;\n"
                        + "            vec3 nautilusRenderedPosition = nautilusBasePosition"
                        + " + (nautilusPostWavePosition - nautilusPreWavePosition);\n"
                        + "            " + VOXEL_BASE_AND_HEIGHT_VARYING
                        + " = vec4(nautilusBasePosition, nautilusRenderedPosition.y);\n"
                        + "            " + FLUID_VARYING + " = " + fluidExpression + ";\n"
                        + "        }\n";
                case BLISS -> blissCapture(source, fluidExpression);
                case UNSUPPORTED -> "";
            };
            return "\n    if (" + VOXEL_POSITION_READY_UNIFORM + " > 0.5) {\n"
                    + body + "    }\n";
        }

        /**
         * Iris invokes this injector after shader-pack option processing has begun. Introducing a
         * new preprocessor branch here leaves directives for the later AST transform and makes the
         * program illegal. Resolve the selected Bliss option in Java and emit exactly one ordinary
         * GLSL path instead.
         */
        private static String blissCapture(final String source, final String fluidExpression) {
            final String base = "        vec3 nautilusBasePosition = getVertexPosition().xyz;\n";
            // Do not infer LARGE_WAVE_DISPLACEMENT from a #define: this hook receives Iris'
            // already-selected source and the directive may no longer exist.  The same
            // pre/post transform is valid with waves disabled (the delta is zero) and with waves
            // enabled (the delta is the actual displacement), so emit one unconditional GLSL
            // path whenever the two authoritative matrices are present.
            if (!source.contains("gbufferModelViewInverse")
                    || !source.contains("iris_ModelViewMatrix")) {
                return "        " + FLUID_VARYING + " = " + fluidExpression + ";\n";
            }
            return base
                    + "        vec4 nautilusPreWaveH = gbufferModelViewInverse"
                    + " * iris_ModelViewMatrix * vec4(nautilusBasePosition, 1.0);\n"
                    + "        vec4 nautilusPostWaveH = gbufferModelViewInverse"
                    + " * vec4(position, 1.0);\n"
                    + "        if (abs(nautilusPreWaveH.w) > 1.0E-8"
                    + " && abs(nautilusPostWaveH.w) > 1.0E-8) {\n"
                    + "            vec3 nautilusPreWavePosition = nautilusPreWaveH.xyz"
                    + " / nautilusPreWaveH.w;\n"
                    + "            vec3 nautilusPostWavePosition = nautilusPostWaveH.xyz"
                    + " / nautilusPostWaveH.w;\n"
                    + "            vec3 nautilusRenderedPosition = nautilusBasePosition"
                    + " + (nautilusPostWavePosition - nautilusPreWavePosition);\n"
                    + "            " + VOXEL_BASE_AND_HEIGHT_VARYING
                    + " = vec4(nautilusBasePosition, nautilusRenderedPosition.y);\n"
                    + "        }\n"
                    + "        " + FLUID_VARYING + " = " + fluidExpression + ";\n";
        }
    }

    private record MainSpan(int start, int bodyStart, int close) {
    }

    /** GLSL 1.20 uses texture2D; GLSL 1.30 and newer use the generic texture function. */
    private static String textureFunction(final String source) {
        final int version = shaderVersion(source);
        if (version == 0) {
            return "texture2D";
        }
        return version < 130 ? "texture2D" : "texture";
    }

    private static int shaderVersion(final String source) {
        final Matcher version = VERSION.matcher(source);
        if (!version.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(version.group(1));
        } catch (final NumberFormatException ignored) {
            return 0;
        }
    }
}
