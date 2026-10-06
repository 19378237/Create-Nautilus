package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pack-specific, fail-open adapters for the low-cost glass/water boundary masks.
 *
 * <p>The private renderer publishes one nearest, unambiguous water-medium entry depth plus an
 * exact six-direction stencil view sharing the same D24S8 storage. The adapter reconstructs its
 * distance with Photon's own inverse projection and combines it with the frozen camera medium to
 * produce either a water suffix (air camera) or prefix (water camera). No shader-pack conditional
 * is introduced here: Iris has already selected the active preprocessor branch before this class
 * patches the source.</p>
 */
public final class IrisCompositeShaderSource {
    public static final String ENTRY_DEPTH_SAMPLER = "NautilusWaterEntryDepth";
    public static final String ENTRY_DIRECTION_SAMPLER = "NautilusWaterEntryDirection";
    public static final String ENTRY_ATLAS_SAMPLER = "NautilusWaterVoxelAtlas";
    public static final String ENTRY_ATLAS_SIZE_UNIFORM = "NautilusWaterVoxelAtlasSize";
    public static final String ENTRY_ATLAS_CARRIER_COUNT_UNIFORM =
            "NautilusWaterVoxelCarrierCount";
    public static final String READY_UNIFORM = "NautilusWaterEntryReady";
    public static final String ENTRY_GENERATION_UNIFORM = "NautilusWaterEntryGeneration";
    public static final String ENTRY_SIZE_UNIFORM = "NautilusWaterEntrySize";
    /** Normalized half-open minU/minV/maxU/maxV region containing defined entry depth. */
    public static final String ENTRY_BOUNDS_UNIFORM = "NautilusWaterEntryBounds";
    /** Non-zero only when raw D32 values must be flipped before inverse projection. */
    public static final String DEPTH_REVERSED_UNIFORM =
            "NautilusWaterEntryDepthReversed";
    /** Non-zero when the D32 no-hit clear value is zero instead of one. */
    public static final String CLEAR_REVERSED_UNIFORM =
            "NautilusWaterEntryClearReversed";
    /** Initial medium frozen by the same semantic/raster snapshot as the entry texture. */
    public static final String CAMERA_WATER_UNIFORM = "NautilusWaterEntryCameraWater";
    public static final String ADAPTER_GENERATION_UNIFORM =
            "NautilusWaterAdapterGeneration";
    /** Program-owned generation, uploaded at use time to keep compiled source reload-stable. */
    public static final String EXPECTED_ADAPTER_GENERATION_UNIFORM =
            "NautilusWaterAdapterGenerationExpected";

    /** Compile-only aliases for dormant legacy renderers; the old porthole path stays disabled. */
    @Deprecated
    public static final boolean PORTHOLE_ENABLED = false;
    @Deprecated
    public static final String BOUNDARY_GATE_DEPTH_SAMPLER = ENTRY_DEPTH_SAMPLER;
    @Deprecated
    public static final String WATER_BOUNDARY_DEPTH_SAMPLER = ENTRY_DEPTH_SAMPLER;
    @Deprecated
    public static final String WATER_BOUNDARY_STATUS_SAMPLER = ENTRY_DEPTH_SAMPLER;
    @Deprecated
    public static final String BOUNDARY_SIZE_UNIFORM = ENTRY_SIZE_UNIFORM;

    private static final String MARKER = "// CREATE_NAUTILUS_WATER_BOUNDARY";
    /** Survives Iris AST printing even when line comments are discarded. */
    private static final String CODE_MARKER = EXPECTED_ADAPTER_GENERATION_UNIFORM;
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern PHOTON_SWITCH = Pattern.compile(
            "\\bswitch\\s*\\(\\s*isEyeInWater\\s*\\)\\s*\\{");
    private static final Pattern PHOTON_VL_COMPOSITE = Pattern.compile(
            "\\bfragment_color\\s*=\\s*fragment_color\\s*\\*\\s*fog_transmittance"
                    + "\\s*\\+\\s*fog_scattering\\s*;");
    private static final Pattern PHOTON_TAAU_JITTER = Pattern.compile(
            "\\bjitter_offset\\s*=\\s*taa_offset\\s*\\*\\s*rcp\\s*"
                    + "\\(\\s*taau_render_scale\\s*\\)\\s*;");
    private static final Pattern PHOTON_TAA_JITTER = Pattern.compile(
            "\\bjitter_offset\\s*=\\s*taa_offset\\s*\\*\\s*0\\.66\\s*;");
    /** Bliss keeps the same semantic declaration with or without Distant Horizons. */
    private static final Pattern BLISS_VIEW_POS0 = Pattern.compile(
            "(?m)^\\s*vec3\\s+viewPos0\\s*=\\s*toScreenSpace(?:_DH)?\\s*"
                    + "\\([^;]*\\)\\s*;");
    private static final Pattern BLISS_TAA_OFFSET_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:(?:flat|smooth|noperspective|centroid|sample)\\s+)*"
                    + "(?:varying|in)\\s+(?:(?:highp|mediump|lowp)\\s+)?"
                    + "vec2\\s+TAA_Offset\\s*;");
    /** Survives Bliss preprocessing only when its TAA branch was actually selected. */
    private static final Pattern BLISS_TAA_ACTIVE_MARKER = Pattern.compile(
            "\\bvec2\\s+coord\\s*=\\s*gl_FragCoord\\.xy\\s*\\+\\s*"
                    + "\\(\\s*frameCounter\\s*%\\s*40000\\s*\\)\\s*\\*\\s*2\\.0");
    private static final Pattern BLISS_OVERWORLD_FOG_SIGNATURE = Pattern.compile(
            "\\bvec4\\s+GetVolumetricFog\\s*\\([^)]*"
                    + "\\binout\\s+float\\s+\\w+\\s*\\)", Pattern.DOTALL);
    private static final Pattern COMPLEMENTARY_TAA_ACTIVE = Pattern.compile(
            "\\bdither\\s*=\\s*fract\\s*\\(\\s*dither\\s*\\+\\s*goldenRatio\\b");
    /** Iris' pack preprocessor has already run before this adapter is called. */
    private static final Pattern CONDITIONAL_PREPROCESSOR_DIRECTIVE = Pattern.compile(
            // Do not let indentation consume line breaks: preprocessed packs retain thousands
            // of blank lines, and retrying a cross-line whitespace match at each line is quadratic.
            "(?m)^[\\t\\x0B\\f ]*#[\\t\\x0B\\f ]*(?:if|ifdef|ifndef|elif|else|endif)\\b");

    /** Shader-pack option could not be read for this exact Iris compilation generation. */
    public static final int UNKNOWN_SHADER_OPTION = Integer.MIN_VALUE;

    private static int shaderReloadGeneration;
    private static int activeAdapterGeneration;
    private static boolean buildingShaderReload;
    private static final EnumSet<AdapterPack> BUILD_PACKS =
            EnumSet.noneOf(AdapterPack.class);
    private static final EnumSet<AdapterStage> BUILD_CANDIDATES =
            EnumSet.noneOf(AdapterStage.class);
    private static final EnumSet<AdapterStage> BUILD_PATCHED =
            EnumSet.noneOf(AdapterStage.class);
    private static final Set<String> BUILD_PROGRAMS = new LinkedHashSet<>();
    private static final Set<String> BUILD_FAILURES = new LinkedHashSet<>();
    private static final LinkedHashSet<Integer> READY_ADAPTER_GENERATIONS =
            new LinkedHashSet<>();
    /** Generations whose complete boundary uniform set linked and whose program was actually used. */
    private static final LinkedHashSet<Integer> LINKED_CONSUMER_GENERATIONS =
            new LinkedHashSet<>();
    /**
     * Shader options belong to the pipeline generation that Iris compiled them for.  Iris can
     * keep dimension pipelines alive concurrently, so the currently compiling pack options are
     * never a safe source for a cached program that is rendered later.
     */
    private static final Map<Integer, ShaderOptionSnapshot> OPTIONS_BY_GENERATION =
            new LinkedHashMap<>();
    private static final Map<Integer, AdapterPack> PACKS_BY_GENERATION = new LinkedHashMap<>();
    private static ShaderOptionSnapshot buildOptions = ShaderOptionSnapshot.UNKNOWN;

    private IrisCompositeShaderSource() {
    }

    /** Starts one atomic Iris pipeline compilation generation. */
    public static synchronized void beginShaderReload() {
        beginShaderReload(UNKNOWN_SHADER_OPTION, UNKNOWN_SHADER_OPTION);
    }

    /**
     * Starts a generation with the option values already resolved by Iris for the current pack.
     * Unknown or out-of-range values never inherit the previous generation.
     */
    public static synchronized void beginShaderReload(final int blissDofQuality,
                                                       final int complementaryTaaJitter) {
        beginShaderReload(blissDofQuality, complementaryTaaJitter,
                UNKNOWN_SHADER_OPTION, UNKNOWN_SHADER_OPTION,
                UNKNOWN_SHADER_OPTION, Float.NaN);
    }

    /** Production overload with the temporal branches resolved from Iris' current option set. */
    public static synchronized void beginShaderReload(final int blissDofQuality,
                                                       final int complementaryTaaJitter,
                                                       final int temporalAa,
                                                       final int photonTaau,
                                                       final int complementaryTaaMode,
                                                       final float photonTaauRenderScale) {
        beginShaderReload(blissDofQuality, complementaryTaaJitter, temporalAa, photonTaau,
                complementaryTaaMode, photonTaauRenderScale, Float.NaN);
    }

    public static synchronized void beginShaderReload(final int blissDofQuality,
                                                       final int complementaryTaaJitter,
                                                       final int temporalAa,
                                                       final int photonTaau,
                                                       final int complementaryTaaMode,
                                                       final float photonTaauRenderScale,
                                                       final float blissRenderScale) {
        shaderReloadGeneration++;
        if (shaderReloadGeneration <= 0) {
            shaderReloadGeneration = 1;
            READY_ADAPTER_GENERATIONS.clear();
            LINKED_CONSUMER_GENERATIONS.clear();
            OPTIONS_BY_GENERATION.clear();
            PACKS_BY_GENERATION.clear();
        }
        activeAdapterGeneration = 0;
        buildingShaderReload = true;
        BUILD_PACKS.clear();
        BUILD_CANDIDATES.clear();
        BUILD_PATCHED.clear();
        BUILD_PROGRAMS.clear();
        BUILD_FAILURES.clear();
        buildOptions = new ShaderOptionSnapshot(
                sanitizeBlissDof(blissDofQuality),
                sanitizeComplementaryJitter(complementaryTaaJitter),
                sanitizeBooleanOption(temporalAa),
                sanitizeBooleanOption(photonTaau),
                sanitizeBooleanOption(complementaryTaaMode),
                sanitizePhotonRenderScale(photonTaauRenderScale),
                Float.isFinite(blissRenderScale) && blissRenderScale >= 0.1F
                        && blissRenderScale <= 1.0F ? blissRenderScale : Float.NaN);
        OPTIONS_BY_GENERATION.put(shaderReloadGeneration, buildOptions);
    }

    private static int sanitizeBlissDof(final int value) {
        return value >= -1 && value <= 5 ? value : UNKNOWN_SHADER_OPTION;
    }

    private static int sanitizeComplementaryJitter(final int value) {
        return value >= 0 && value <= 3 ? value : UNKNOWN_SHADER_OPTION;
    }

    private static int sanitizeBooleanOption(final int value) {
        return value == 0 || value == 1 ? value : UNKNOWN_SHADER_OPTION;
    }

    private static float sanitizePhotonRenderScale(final float value) {
        return Float.isFinite(value) && value >= 0.25F && value <= 1.0F
                ? value : Float.NaN;
    }

    /** Exact mapping from the unjittered private mask pixel to Photon's opaque depth pixel. */
    public static synchronized SceneDepthTransform photonSceneDepthTransform(
            final int frameCounter, final int viewWidth) {
        return photonSceneDepthTransform(activeAdapterGeneration, frameCounter, viewWidth);
    }

    /**
     * Generation-aware mapping used by cached Iris pipelines.  Never consults the options of a
     * newer pipeline that happens to be compiling, and therefore fails open when the exact
     * generation or its temporal branch is unavailable.
     */
    public static synchronized SceneDepthTransform photonSceneDepthTransform(
            final int generation, final int frameCounter, final int viewWidth) {
        final ShaderOptionSnapshot options = OPTIONS_BY_GENERATION.get(generation);
        if (!linkedConsumerReady(generation) || options == null
                || viewWidth <= 0 || frameCounter < 0
                || options.temporalAa() == UNKNOWN_SHADER_OPTION
                || options.photonTaau() == UNKNOWN_SHADER_OPTION) return null;
        if (options.temporalAa() == 0) {
            return new SceneDepthTransform(1.0F, 0.0F, 0.0F);
        }
        final double jitterX = (fractional(1.3247179572D * frameCounter + 0.5D)
                * 2.0D - 1.0D) / viewWidth;
        final double jitterY = (fractional(1.7548776662D * frameCounter + 0.5D)
                * 2.0D - 1.0D) / viewWidth;
        if (options.photonTaau() == 1) {
            final float scale = options.photonTaauRenderScale();
            if (!Float.isFinite(scale)) return null;
            return new SceneDepthTransform(scale,
                    (float) (0.5D * jitterX), (float) (0.5D * jitterY));
        }
        return new SceneDepthTransform(1.0F,
                (float) (0.33D * jitterX), (float) (0.33D * jitterY));
    }

    /** Maps the private fog mask into the exact active pack's opaque depth coordinates. */
    public static synchronized SceneDepthTransform sceneDepthTransform(
            final int generation, final int frameCounter, final int viewWidth, final int viewHeight) {
        if (!linkedConsumerReady(generation) || frameCounter < 0
                || viewWidth <= 0 || viewHeight <= 0) return null;
        final ShaderOptionSnapshot options = OPTIONS_BY_GENERATION.get(generation);
        final AdapterPack pack = PACKS_BY_GENERATION.get(generation);
        if (options == null || pack == null) return null;
        if (pack == AdapterPack.PHOTON) {
            return photonSceneDepthTransform(generation, frameCounter, viewWidth);
        }
        final int frame = frameCounter % 8;
        if (pack == AdapterPack.BLISS) {
            if (!options.blissAlignmentSafe() || !Float.isFinite(options.blissRenderScale())
                    || options.temporalAa() == UNKNOWN_SHADER_OPTION) return null;
            if (options.temporalAa() == 0) {
                return new SceneDepthTransform(options.blissRenderScale(), 0.0F, 0.0F);
            }
            final float jitterX = switch (frame) {
                case 0 -> 1; case 1 -> -1; case 2 -> 5; case 3 -> -3;
                case 4 -> -5; case 5 -> -7; case 6 -> 3; default -> 7;
            } / 8.0F;
            final float jitterY = switch (frame) {
                case 0 -> -3; case 1 -> 3; case 2 -> 1; case 3 -> -5;
                case 4 -> 5; case 5 -> -1; case 6 -> 7; default -> 7;
            } / 8.0F;
            return new SceneDepthTransform(options.blissRenderScale(),
                    0.5F * jitterX / viewWidth, 0.5F * jitterY / viewHeight);
        }
        if (options.complementaryTaaMode() == UNKNOWN_SHADER_OPTION) return null;
        if (options.complementaryTaaMode() == 0 || options.complementaryTaaJitter() == 0) {
            return new SceneDepthTransform(1.0F, 0.0F, 0.0F);
        }
        final float jitterScale = switch (options.complementaryTaaJitter()) {
            case 1 -> 0.125F; case 2 -> 0.33F; case 3 -> 1.0F; default -> Float.NaN;
        };
        if (!Float.isFinite(jitterScale)) return null;
        final float jitterX = switch (frame) {
            case 0 -> 0.125F; case 1 -> -0.125F; case 2 -> 0.625F; case 3 -> 0.375F;
            case 4 -> -0.625F; case 5 -> -0.875F; case 6 -> 0.375F; default -> 0.875F;
        };
        final float jitterY = switch (frame) {
            case 0 -> -0.375F; case 1 -> 0.375F; case 2 -> 0.125F; case 3 -> -0.625F;
            case 4 -> 0.625F; case 5 -> -0.125F; case 6 -> -0.875F; default -> 0.875F;
        };
        return new SceneDepthTransform(1.0F, 0.5F * jitterScale * jitterX / viewWidth,
                0.5F * jitterScale * jitterY / viewHeight);
    }

    /** Complementary fogs its opaque background in deferred1, before glass is drawn. */
    public static synchronized boolean requiresEarlyFog(final int generation) {
        return adapterReady(generation) && PACKS_BY_GENERATION.get(generation) == AdapterPack.COMPLEMENTARY;
    }

    private static double fractional(final double value) {
        return value - Math.floor(value);
    }

    public record SceneDepthTransform(float scale, float offsetU, float offsetV) {
        public SceneDepthTransform {
            if (!Float.isFinite(scale) || scale <= 0.0F
                    || !Float.isFinite(offsetU) || !Float.isFinite(offsetV)) {
                throw new IllegalArgumentException("invalid shader-pack depth transform");
            }
        }
    }

    /** Publishes only a complete stage set for this compilation generation. */
    public static synchronized void finishShaderReload() {
        buildingShaderReload = false;
        activeAdapterGeneration = 0;
        if (BUILD_PACKS.isEmpty()) return;

        if (BUILD_PACKS.size() != 1) {
            CreateNautilus.LOGGER.warn(
                    "[water-boundary] conflicting shader-pack fingerprints in Iris reload "
                            + "generation {}; adapter remains disabled: {}",
                    shaderReloadGeneration, BUILD_PACKS);
            return;
        }

        final AdapterPack pack = BUILD_PACKS.iterator().next();
        final EnumSet<AdapterStage> required = requiredStages(pack);
        if (required.isEmpty()) {
            CreateNautilus.LOGGER.warn(
                    "[water-boundary] recognized {} in Iris reload generation {} but no "
                            + "active adapter stage was found; adapter remains disabled "
                            + "(programs={})",
                    pack.displayName, shaderReloadGeneration, BUILD_PROGRAMS);
            return;
        }
        final EnumSet<AdapterStage> missing = EnumSet.copyOf(required);
        missing.removeAll(BUILD_PATCHED);
        final EnumSet<AdapterStage> consumers = EnumSet.copyOf(BUILD_PATCHED);
        if (consumers.isEmpty()) {
            CreateNautilus.LOGGER.warn(
                    "[water-boundary] recognized {} in Iris reload generation {} but "
                            + "patched zero boundary-consuming stages; adapter remains disabled "
                            + "(candidates={}, programs={}, failures={})",
                    pack.displayName, shaderReloadGeneration, BUILD_CANDIDATES,
                    BUILD_PROGRAMS, BUILD_FAILURES);
            return;
        }
        if (!missing.isEmpty()) {
            CreateNautilus.LOGGER.warn(
                    "[water-boundary] {} adapter generation {} is incomplete: missing {}, "
                            + "patched {}, failures {}; adapter remains disabled",
                    pack.displayName, shaderReloadGeneration, missing,
                    BUILD_PATCHED, BUILD_FAILURES);
            return;
        }

        activeAdapterGeneration = shaderReloadGeneration;
        PACKS_BY_GENERATION.put(activeAdapterGeneration, pack);
        READY_ADAPTER_GENERATIONS.add(activeAdapterGeneration);
        CreateNautilus.LOGGER.info(
                "[water-boundary] {} adapter generation {} published stages: {}",
                pack.displayName, activeAdapterGeneration, consumers);
    }

    public static synchronized boolean adapterReady() {
        return activeAdapterGeneration > 0;
    }

    /** True when this exact cached Iris pipeline generation published at least one stage. */
    public static synchronized boolean adapterReady(final int generation) {
        return generation > 0 && READY_ADAPTER_GENERATIONS.contains(generation);
    }

    /** Records the first concrete linked/use of a complete boundary-consuming Photon program. */
    public static synchronized void confirmLinkedConsumer(final int generation) {
        if (adapterReady(generation)) LINKED_CONSUMER_GENERATIONS.add(generation);
    }

    /** Source patching alone is insufficient; dormant/unlinked stages must perform no mask work. */
    public static synchronized boolean linkedConsumerReady(final int generation) {
        return generation > 0 && LINKED_CONSUMER_GENERATIONS.contains(generation);
    }

    public static synchronized int adapterGeneration() {
        return activeAdapterGeneration;
    }

    /** Captured by each Iris Program while its owning pipeline is still under construction. */
    public static synchronized int currentBuildGeneration() {
        return buildingShaderReload ? shaderReloadGeneration : 0;
    }

    public static boolean isCandidateProgram(final String name) {
        if (name == null) return false;
        final String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("composite") || lower.startsWith("deferred1");
    }

    public static String patchFragment(final String source, final String name) {
        if (source == null || source.contains(MARKER) || source.contains(CODE_MARKER)
                || !isCandidateProgram(name)) {
            return source;
        }

        final String executable = executableGlsl(source);
        final PatchMatch match = detectPatch(executable, name);
        if (match == null) {
            recordPackFingerprint(executable, name);
            return source;
        }
        recordCandidate(match, name);
        // Every low-cost boundary adapter reconstructs D32 samples with the exact projection
        // owned by this program.  A similarly named pack variant without that uniform cannot be
        // made correct, so leave only that stage native instead of risking a link failure.
        if (!executable.contains("gbufferProjectionInverse")
                || !executable.contains("gbufferModelViewInverse")) {
            recordPatchFailure(match, name);
            return source;
        }

        final String patched = switch (match.kind) {
            case PHOTON_VOLUMETRIC_FRONT -> patchPhotonVolumetricFront(source);
            case PHOTON_VOLUMETRIC_BLEND -> patchPhotonVolumetricBlend(source);
            case PHOTON_ANALYTIC -> patchPhotonAnalytic(source);
            case BLISS_BEHIND_TRANSLUCENT -> patchBlissBehindTranslucent(source);
            case BLISS_BACKGROUND_BLEND -> patchBlissBackgroundBlend(source);
            case COMPLEMENTARY_DEFERRED -> patchComplementaryDeferred(source);
            case COMPLEMENTARY_TONE -> patchComplementaryTone(source);
        };
        /*
         * The string handed to CompositeRenderer is the source immediately before Iris'
         * TransformPatcher.  Depending on the pack and the active world branch it can still
         * contain conditional lines which Iris itself is about to consume.  Checking for any
         * directive here therefore rejects perfectly valid patches (Photon's preprocessed
         * c0/c1 sources are one concrete example).  What is unsafe is adding a new conditional
         * after Iris has selected the branch.  Compare the directive count before/after instead
         * and reject only a patch that actually introduces one.
         */
        if (!patched.equals(source)
                && conditionalDirectiveCount(patched)
                > conditionalDirectiveCount(source)) {
            recordPatchFailure(match, name);
            CreateNautilus.LOGGER.warn(
                    "[water-boundary] rejected {} patch for {} because it reintroduced "
                            + "a conditional preprocessor directive after Iris preprocessing; "
                            + "this stage remains native",
                    match.kind, safeProgramName(name));
            return source;
        }
        if (!patched.equals(source) && patched.contains(MARKER)) {
            recordPatched(match.stage);
        } else {
            recordPatchFailure(match, name);
        }
        return patched;
    }

    private static PatchMatch detectPatch(final String executable, final String name) {
        final String program = name.toLowerCase(Locale.ROOT).replaceFirst("\\.fsh$", "");
        if (program.equals("composite") && executable.contains("waterVolumetrics_test(")
                && executable.contains("depthtex0") && executable.contains("depthtex1")
                && executable.contains("colortex2") && executable.contains("colortex7")
                && executable.contains("viewPos0") && executable.contains("viewPos1")
                && executable.contains("fogFinal")
                && BLISS_OVERWORLD_FOG_SIGNATURE.matcher(executable).find()) {
            return new PatchMatch(AdapterPack.BLISS, AdapterStage.BLISS_BEHIND_TRANSLUCENT,
                    PatchKind.BLISS_BEHIND_TRANSLUCENT);
        }
        if (program.equals("composite2")
                && executable.contains("BilateralUpscale_VLFOG(colortex13,")
                && executable.contains("if(translucentMasks > 0.0){")
                && executable.contains("vlBehingTranslucents")
                && executable.contains("vec3 viewPos") && executable.contains("depthtex1")) {
            return new PatchMatch(AdapterPack.BLISS, AdapterStage.BLISS_BACKGROUND_BLEND,
                    PatchKind.BLISS_BACKGROUND_BLEND);
        }
        if (program.equals("deferred1") && executable.contains("void DoFog(inout vec4 color")
                && executable.contains("GetWaterFog(") && executable.contains("DoWaterFog(")
                && executable.contains("depthtex0") && executable.contains("texCoord")) {
            return new PatchMatch(AdapterPack.COMPLEMENTARY, AdapterStage.COMPLEMENTARY_DEFERRED,
                    PatchKind.COMPLEMENTARY_DEFERRED);
        }
        if (program.equals("composite1") && executable.contains("float lViewPos1")
                && executable.contains("depthtex0") && executable.contains("depthtex1")
                && executable.contains("vec3 underwaterMult = vec3(0.80, 0.87, 0.97);")
                && executable.contains("volumetricEffect.rgb *= pow2(underwaterMult * 0.55);")) {
            return new PatchMatch(AdapterPack.COMPLEMENTARY, AdapterStage.COMPLEMENTARY_TONE,
                    PatchKind.COMPLEMENTARY_TONE);
        }
        // The target effect is Photon's native volumetric water path, including shafts and
        // caustics. Keep 0.4.296's aperture data, but feed its interval to c0's raymarch.
        if (executable.contains("raymarch_water_fog(")
                && executable.contains("raymarch_air_fog(")
                && executable.contains("vec3 world_start_pos")
                && executable.contains("vec3 world_end_pos")
                && executable.contains("vec3 world_back_pos")
                && executable.contains("depthtex0")
                && executable.contains("depthtex1")
                && PHOTON_SWITCH.matcher(executable).find()) {
            return new PatchMatch(AdapterPack.PHOTON,
                    AdapterStage.PHOTON_VOLUMETRIC_FRONT,
                    PatchKind.PHOTON_VOLUMETRIC_FRONT);
        }
        // In the VL branch c1 owns the translucent layer and the opaque background behind it.
        // It must be patched together with c0 so fog never composites over that layer afterward.
        if (executable.contains("vec3 back_position_world")
                && executable.contains("bool is_translucent")
                && executable.contains("mat2x3 analytic_fog = air_fog_analytic(")
                && executable.contains("fragment_color = blend_layers_with_fog(")
                && PHOTON_VL_COMPOSITE.matcher(executable).find()) {
            return new PatchMatch(AdapterPack.PHOTON,
                    AdapterStage.PHOTON_VOLUMETRIC_BLEND,
                    PatchKind.PHOTON_VOLUMETRIC_BLEND);
        }
        // The helper definition is included even when Photon compiles its VL path.  Requiring
        // the actual analytic call prevents one shader-pack option from being mistaken for two
        // simultaneously active adapters.
        if (executable.contains("mat2x3 analytic_fog = water_fog_simple(")
                && executable.contains("front_position_world")
                && executable.contains("view_distance")) {
            return new PatchMatch(AdapterPack.PHOTON, AdapterStage.PHOTON_ANALYTIC,
                    PatchKind.PHOTON_ANALYTIC);
        }
        return null;
    }

    private static synchronized void recordPackFingerprint(final String executable,
                                                           final String name) {
        if (!buildingShaderReload) return;
        AdapterPack pack = null;
        if (executable.contains("raymarch_water_fog(")
                || executable.contains("water_fog_simple(")) {
            pack = AdapterPack.PHOTON;
        }
        if (pack != null) {
            BUILD_PACKS.add(pack);
            BUILD_PROGRAMS.add(safeProgramName(name));
        }
    }

    private static synchronized void recordCandidate(final PatchMatch match,
                                                     final String name) {
        if (!buildingShaderReload) return;
        BUILD_PACKS.add(match.pack);
        BUILD_CANDIDATES.add(match.stage);
        BUILD_PROGRAMS.add(safeProgramName(name));
    }

    private static synchronized void recordPatched(final AdapterStage stage) {
        if (buildingShaderReload) BUILD_PATCHED.add(stage);
    }

    private static synchronized void recordPatchFailure(final PatchMatch match,
                                                        final String name) {
        if (!buildingShaderReload) return;
        BUILD_FAILURES.add(safeProgramName(name) + ':' + match.kind);
    }

    private static String safeProgramName(final String name) {
        return name == null ? "<unnamed>" : name;
    }

    private static int conditionalDirectiveCount(final String source) {
        if (source == null || source.isEmpty()) return 0;
        final Matcher matcher = CONDITIONAL_PREPROCESSOR_DIRECTIVE.matcher(source);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static EnumSet<AdapterStage> requiredStages(final AdapterPack pack) {
        return switch (pack) {
            case PHOTON -> photonRequiredStages();
            case BLISS -> EnumSet.of(AdapterStage.BLISS_BEHIND_TRANSLUCENT,
                    AdapterStage.BLISS_BACKGROUND_BLEND);
            case COMPLEMENTARY -> EnumSet.of(AdapterStage.COMPLEMENTARY_DEFERRED,
                    AdapterStage.COMPLEMENTARY_TONE);
        };
    }

    private static EnumSet<AdapterStage> photonRequiredStages() {
        if (BUILD_CANDIDATES.contains(AdapterStage.PHOTON_VOLUMETRIC_FRONT)
                || BUILD_CANDIDATES.contains(AdapterStage.PHOTON_VOLUMETRIC_BLEND)) {
            return EnumSet.of(AdapterStage.PHOTON_VOLUMETRIC_FRONT,
                    AdapterStage.PHOTON_VOLUMETRIC_BLEND);
        }
        if (BUILD_CANDIDATES.contains(AdapterStage.PHOTON_ANALYTIC)) {
            return EnumSet.of(AdapterStage.PHOTON_ANALYTIC);
        }
        return EnumSet.noneOf(AdapterStage.class);
    }

    private enum AdapterPack {
        PHOTON("Photon"), BLISS("Bliss"), COMPLEMENTARY("Complementary");

        private final String displayName;

        AdapterPack(final String displayName) {
            this.displayName = displayName;
        }
    }

    private enum AdapterStage {
        PHOTON_VOLUMETRIC_FRONT,
        PHOTON_VOLUMETRIC_BLEND,
        PHOTON_ANALYTIC,
        BLISS_BEHIND_TRANSLUCENT,
        BLISS_BACKGROUND_BLEND,
        COMPLEMENTARY_DEFERRED,
        COMPLEMENTARY_TONE
    }

    private enum PatchKind {
        PHOTON_VOLUMETRIC_FRONT,
        PHOTON_VOLUMETRIC_BLEND,
        PHOTON_ANALYTIC,
        BLISS_BEHIND_TRANSLUCENT,
        BLISS_BACKGROUND_BLEND,
        COMPLEMENTARY_DEFERRED,
        COMPLEMENTARY_TONE
    }

    private record PatchMatch(AdapterPack pack, AdapterStage stage, PatchKind kind) {
    }

    private record ShaderOptionSnapshot(int blissDofQuality,
                                        int complementaryTaaJitter,
                                        int temporalAa,
                                        int photonTaau,
                                        int complementaryTaaMode,
                                        float photonTaauRenderScale,
                                        float blissRenderScale) {
        private static final ShaderOptionSnapshot UNKNOWN = new ShaderOptionSnapshot(
                UNKNOWN_SHADER_OPTION, UNKNOWN_SHADER_OPTION,
                UNKNOWN_SHADER_OPTION, UNKNOWN_SHADER_OPTION,
                UNKNOWN_SHADER_OPTION, Float.NaN, Float.NaN);

        private boolean blissAlignmentSafe() {
            return blissDofQuality >= -1 && blissDofQuality <= 4;
        }
    }

    private static String patchBlissBehindTranslucent(final String source) {
        // Bliss owns the medium behind the nearest translucent layer in this pass. The untouched
        // later volumetric pass still owns camera-to-glass air, and the pack blends the glass only
        // after this result has already fogged the opaque background. Split only that back range,
        // using the pack's complete air/cloud prefix and its native water raymarch for each part.
        final String executable = executableGlsl(source);
        if (!buildOptions.blissAlignmentSafe()
                || !Float.isFinite(buildOptions.blissRenderScale())
                || !BLISS_OVERWORLD_FOG_SIGNATURE.matcher(executable).find()
                || !executable.contains("renderClouds(")
                || !executable.contains("waterVolumetrics_test(")
                || !executable.contains("texture2D(colortex2, tc)")
                || !executable.contains("texture2D(colortex7,tc)")
                || !executable.contains("depthtex0")
                || !executable.contains("depthtex1")) {
            return source;
        }
        final Matcher sourceViewMatcher = BLISS_VIEW_POS0.matcher(executable);
        if (!sourceViewMatcher.find()) return source;
        final boolean dhProjection = sourceViewMatcher.group().contains("toScreenSpace_DH");
        final boolean dhDepthTextures = dhProjection
                && executable.contains("uniform sampler2D dhDepthTex;")
                && executable.contains("uniform sampler2D dhDepthTex1;");
        final String resolvedViewPositions;
        if (dhProjection) {
            final String resolvedDhDepths = dhDepthTextures ? """
                    float nautilusDhDepth0 = texture2D(
                            dhDepthTex, nautilusResolvedSceneUv).r;
                    float nautilusDhDepth1 = texture2D(
                            dhDepthTex1, nautilusResolvedSceneUv).r;
                    """ : """
                    float nautilusDhDepth0 = 0.0;
                    float nautilusDhDepth1 = 0.0;
                    """;
            resolvedViewPositions = resolvedDhDepths + """
                    nautilusResolvedViewPos0 = toScreenSpace_DH(
                            nautilusResolvedMaskUv, nautilusDepth0,
                            nautilusDhDepth0);
                    nautilusResolvedViewPos1 = toScreenSpace_DH(
                            nautilusResolvedMaskUv, nautilusDepth1,
                            nautilusDhDepth1);
                    """;
        } else {
            resolvedViewPositions = """
                    nautilusResolvedViewPos0 = toScreenSpace(vec3(
                            nautilusResolvedMaskUv, nautilusDepth0));
                    nautilusResolvedViewPos1 = toScreenSpace(vec3(
                            nautilusResolvedMaskUv, nautilusDepth1));
                    """;
        }
        final String uvHelper = blissUvHelper(source);
        if (uvHelper == null) return source;
        String patched = insertBeforeMain(source,
                commonHelper() + uvHelper + blissAlignmentHelper(source)
                        + blissEntryFootprintHelper() + blissAirPrefixHelper());
        if (patched == source) return source;

        final String outerAnchor = "if(texture2D(colortex2, tc).a > 0.0 || iswater){";
        final int outer = patched.indexOf(outerAnchor);
        if (outer < 0) return source;
        final String outerReplacement = """
                vec2 nautilusMaskUv = nautilus_bliss_mask_uv(tc);
                vec4 nautilusSegments = vec4(-2.0);
                float nautilusGate = -1.0;
                float nautilusStatus = 1.0;
                vec2 nautilusResolvedMaskUv = nautilusMaskUv;
                bool nautilusFootprintResolved =
                        nautilus_bliss_decode_entry_footprint(
                                nautilusMaskUv, isEyeInWater,
                                nautilusSegments, nautilusGate,
                                nautilusStatus, nautilusResolvedMaskUv);
                bool nautilusNativeCandidate =
                        texture2D(colortex2, tc).a > 0.0 || iswater;
                vec3 nautilusResolvedViewPos0 = vec3(0.0);
                vec3 nautilusResolvedViewPos1 = vec3(0.0);
                float nautilusSceneDistance = -1.0;
                float nautilusRangeStart = -1.0;
                float nautilusWorldRayY = 0.0;
                bool nautilusRayValid = false;
                if (nautilusFootprintResolved) {
                    vec2 nautilusResolvedSceneUv = nautilus_bliss_scene_uv(
                            nautilusResolvedMaskUv);
                    ivec2 nautilusDepthSize0 = textureSize(depthtex0, 0);
                    ivec2 nautilusDepthSize1 = textureSize(depthtex1, 0);
                    if (all(greaterThan(nautilusDepthSize0, ivec2(0)))
                            && all(greaterThan(nautilusDepthSize1, ivec2(0)))
                            && all(greaterThanEqual(
                                    nautilusResolvedSceneUv, vec2(0.0)))
                            && all(lessThan(
                                    nautilusResolvedSceneUv, vec2(1.0)))) {
                        ivec2 nautilusDepthTexel0 = clamp(ivec2(floor(
                                nautilusResolvedSceneUv
                                        * vec2(nautilusDepthSize0))),
                                ivec2(0), nautilusDepthSize0 - ivec2(1));
                        ivec2 nautilusDepthTexel1 = clamp(ivec2(floor(
                                nautilusResolvedSceneUv
                                        * vec2(nautilusDepthSize1))),
                                ivec2(0), nautilusDepthSize1 - ivec2(1));
                        float nautilusDepth0 = texelFetch(
                                depthtex0, nautilusDepthTexel0, 0).r;
                        float nautilusDepth1 = texelFetch(
                                depthtex1, nautilusDepthTexel1, 0).r;
                """ + resolvedViewPositions + """
                        if (all(equal(nautilusResolvedViewPos0,
                                nautilusResolvedViewPos0))
                                && all(equal(nautilusResolvedViewPos1,
                                nautilusResolvedViewPos1))) {
                            float nautilusDistance0 = length(
                                    nautilusResolvedViewPos0);
                            float nautilusDistance1 = length(
                                    nautilusResolvedViewPos1);
                            nautilusSceneDistance = max(
                                    nautilusDistance0, nautilusDistance1);
                            // Cutout glass leaves no native translucent depth: depth0 and depth1
                            // can both be the seabed. The optical entry owns the front boundary.
                            nautilusRangeStart = nautilusGate;
                            vec3 nautilusSceneView = nautilusDistance1
                                    >= nautilusDistance0
                                    ? nautilusResolvedViewPos1
                                    : nautilusResolvedViewPos0;
                            vec3 nautilusWorldRay =
                                    mat3(gbufferModelViewInverse)
                                    * nautilusSceneView;
                            float nautilusWorldLength = length(
                                    nautilusWorldRay);
                            if (nautilusSceneDistance == nautilusSceneDistance
                                    && nautilusRangeStart
                                    == nautilusRangeStart
                                    && nautilusSceneDistance
                                    > nautilusRangeStart + 1.0E-4
                                    && nautilusWorldLength > 1.0E-5) {
                                nautilusWorldRayY =
                                        nautilusWorldRay.y
                                        / nautilusWorldLength;
                                nautilusRayValid = true;
                            }
                        }
                    }
                }
                float nautilusBackWaterLength = nautilusRayValid
                        ? nautilus_water_length_range(
                                nautilusSegments, nautilusRangeStart,
                                nautilusSceneDistance) : 0.0;
                bool nautilusOverride = isEyeInWater == 0 && !iswater
                        && nautilus_bliss_alignment_valid()
                        && nautilusFootprintResolved
                        && nautilus_segments_active(
                                nautilusSegments, nautilusGate,
                                nautilusStatus, nautilusSceneDistance)
                        && nautilus_open_end_direction_safe(
                                nautilusSegments, nautilusWorldRayY)
                        && nautilusBackWaterLength > 1.0E-4;
                if(nautilusNativeCandidate || nautilusOverride){
                """;
        patched = patched.substring(0, outer) + outerReplacement
                + patched.substring(outer + outerAnchor.length());

        final String airAnchor = "if(!iswater){";
        final int air = patched.indexOf(airAnchor, outer + outerReplacement.length());
        if (air < 0) return source;
        patched = patched.substring(0, air)
                + "if(!iswater && !nautilusOverride){"
                + patched.substring(air + airAnchor.length());

        final String callAnchor =
                "if(iswater) underwaterVlFog = waterVolumetrics_test(";
        final int call = patched.indexOf(callAnchor, air);
        if (call < 0) return source;
        final int statementEnd = patched.indexOf(';', call);
        if (statementEnd < 0) return source;
        final String nativeStatement = patched.substring(call, statementEnd + 1);
        final int nativeCallStart = nativeStatement.indexOf("waterVolumetrics_test(");
        if (nativeCallStart < 0) return source;
        final String nativeCall = nativeStatement.substring(nativeCallStart);
        final String replacement = """
                if (nautilusOverride) {
                    float nautilusDistance0 = length(
                            nautilusResolvedViewPos0);
                    float nautilusDistance1 = length(
                            nautilusResolvedViewPos1);
                    vec3 nautilusSceneEnd = nautilusDistance1
                            >= nautilusDistance0
                            ? nautilusResolvedViewPos1
                            : nautilusResolvedViewPos0;
                    vec3 nautilusRayDirection = nautilusSceneEnd
                            / max(nautilusSceneDistance, 1.0E-5);
                    float nautilusCursor = nautilusRangeStart;
                    underwaterVlFog = vec4(0.0, 0.0, 0.0, 1.0);

                    vec2 nautilusFirst = nautilus_interval_range(
                            nautilusSegments.rg, nautilusRangeStart,
                            nautilusSceneDistance);
                    if (nautilusFirst.x >= 0.0
                            && nautilusFirst.y > nautilusFirst.x + 1.0E-4) {
                        float nautilusFirstStart = max(
                                nautilusFirst.x, nautilusCursor);
                        nautilus_bliss_compose_air(underwaterVlFog,
                                nautilusCursor, nautilusFirstStart,
                                nautilusRayDirection, noise_1, noise_2,
                                directLightColor, indirectLightColor,
                                indirectLightColor_dynamic);
                        if (nautilusFirst.y
                                > nautilusFirstStart + 1.0E-4) {
                            float nautilusWaterLength =
                                    nautilusFirst.y - nautilusFirstStart;
                            float nautilusVdiff = nautilusWaterLength * 2.0;
                            float nautilusEyeDepth = nautilusVdiff
                                    * abs(nautilusWorldRayY);
                            vec4 nautilusWaterPart = waterVolumetrics_test(
                                    nautilusRayDirection * nautilusFirstStart,
                                    nautilusRayDirection * nautilusFirst.y,
                                    nautilusEyeDepth,
                                    nautilusEyeDepth
                                            / max(abs(WsunVec.y), 1.0E-4),
                                    nautilusVdiff, noise_1, totEpsilon,
                                    scatterCoef, indirectLightColor_dynamic,
                                    directLightColor * (1.0-caveDetection),
                                    dot(nautilusRayDirection,
                                            normalize(sunVec * lightCol.a)));
                            underwaterVlFog.rgb += underwaterVlFog.a
                                    * nautilusWaterPart.rgb;
                            underwaterVlFog.a *= nautilusWaterPart.a;
                            nautilusCursor = nautilusFirst.y;
                        }
                    }

                    vec2 nautilusSecond = nautilus_interval_range(
                            nautilusSegments.ba, nautilusRangeStart,
                            nautilusSceneDistance);
                    if (nautilusSecond.x >= 0.0
                            && nautilusSecond.y > nautilusSecond.x + 1.0E-4) {
                        float nautilusSecondStart = max(
                                nautilusSecond.x, nautilusCursor);
                        nautilus_bliss_compose_air(underwaterVlFog,
                                nautilusCursor, nautilusSecondStart,
                                nautilusRayDirection, noise_1, noise_2,
                                directLightColor, indirectLightColor,
                                indirectLightColor_dynamic);
                        if (nautilusSecond.y
                                > nautilusSecondStart + 1.0E-4) {
                            float nautilusWaterLength =
                                    nautilusSecond.y - nautilusSecondStart;
                            float nautilusVdiff = nautilusWaterLength * 2.0;
                            float nautilusEyeDepth = nautilusVdiff
                                    * abs(nautilusWorldRayY);
                            vec4 nautilusWaterPart = waterVolumetrics_test(
                                    nautilusRayDirection * nautilusSecondStart,
                                    nautilusRayDirection * nautilusSecond.y,
                                    nautilusEyeDepth,
                                    nautilusEyeDepth
                                            / max(abs(WsunVec.y), 1.0E-4),
                                    nautilusVdiff, noise_1, totEpsilon,
                                    scatterCoef, indirectLightColor_dynamic,
                                    directLightColor * (1.0-caveDetection),
                                    dot(nautilusRayDirection,
                                            normalize(sunVec * lightCol.a)));
                            underwaterVlFog.rgb += underwaterVlFog.a
                                    * nautilusWaterPart.rgb;
                            underwaterVlFog.a *= nautilusWaterPart.a;
                            nautilusCursor = nautilusSecond.y;
                        }
                    }
                    nautilus_bliss_compose_air(underwaterVlFog,
                            nautilusCursor, nautilusSceneDistance,
                            nautilusRayDirection, noise_1, noise_2,
                            directLightColor, indirectLightColor,
                            indirectLightColor_dynamic);
                } else if (iswater) {
                    underwaterVlFog = """ + nativeCall + "\n                }";
        return patched.substring(0, call) + replacement
                + patched.substring(statementEnd + 1);
    }

    /** Consumes behind-glass fog even when a cutout window has no colortex7 translucent marker. */
    private static String patchBlissBackgroundBlend(final String source) {
        if (!buildOptions.blissAlignmentSafe() || !Float.isFinite(buildOptions.blissRenderScale())) {
            return source;
        }
        final String uvHelper = blissUvHelper(source);
        if (uvHelper == null) return source;
        final String anchor = "if(translucentMasks > 0.0){";
        final String waterLighting = "if ((isEyeInWater == 0 && isWater) || (isEyeInWater == 1 && !isWater)){";
        final String waterDistance = "float Vdiff = distance(viewPos, viewPos0)*mix(";
        if (!source.contains(anchor) || !source.contains("BilateralUpscale_VLFOG(colortex13,")
                || !source.contains(waterLighting) || !source.contains(waterDistance)) return source;
        final Matcher main = MAIN.matcher(executableGlsl(source));
        if (!main.find()) return source;
        final Matcher scene = Pattern.compile("\\bvec3\\s+viewPos\\s*=[^;]*;")
                .matcher(executableGlsl(source));
        if (!scene.find(main.end())) return source;
        final String replacement = """
                
                vec2 nautilusMaskUv = nautilus_bliss_mask_uv(texcoord);
                vec2 nautilusResolvedMaskUv = nautilusMaskUv;
                vec4 nautilusSegments = vec4(-2.0);
                float nautilusGate = -1.0;
                float nautilusStatus = 1.0;
                bool nautilusFootprintResolved = nautilus_bliss_decode_entry_footprint(
                        nautilusMaskUv, isEyeInWater, nautilusSegments,
                        nautilusGate, nautilusStatus, nautilusResolvedMaskUv);
                float nautilusSceneDistance = length(viewPos);
                float nautilusBackWaterLength = nautilus_water_length_range(
                        nautilusSegments, nautilusGate, nautilusSceneDistance);
                bool nautilusBackgroundOverride = isEyeInWater == 0 && !isWater && !hand
                        && nautilusFootprintResolved
                        && nautilus_bliss_alignment_valid()
                        && nautilus_segments_active(nautilusSegments, nautilusGate,
                                nautilusStatus, nautilusSceneDistance)
                        && nautilusSceneDistance > nautilusGate + 1.0E-4
                        && nautilusBackWaterLength > 1.0E-4;
                """;
        String patched = source.substring(0, scene.end()) + replacement + source.substring(scene.end());
        // Bliss splits native underwater rendering between background illumination/absorption
        // here and scattering in colortex13. Enable both for a semantic window before lighting
        // is evaluated, rather than grading the already blended glass in composite4.
        patched = patched.replace(waterLighting,
                "if ((isEyeInWater == 0 && isWater) || (isEyeInWater == 1 && !isWater)"
                        + " || nautilusBackgroundOverride){");
        patched = patched.replace(waterDistance,
                "float Vdiff = (nautilusBackgroundOverride ? nautilusBackWaterLength"
                        + " : distance(viewPos, viewPos0))*mix(");
        patched = patched.replace(anchor,
                "if(translucentMasks > 0.0 || nautilusBackgroundOverride){");
        return insertBeforeMain(patched, commonHelper() + uvHelper
                + blissAlignmentHelper(source) + blissEntryFootprintHelper());
    }

    private static String patchPhotonVolumetricFront(final String source) {
        final String executable = executableGlsl(source);
        if (!photonUvOptionsResolved() || !photonUvSourceMatchesOptions(source)
                || !executable.contains("vec3 world_end_pos")
                || !executable.contains("vec3 world_back_pos")
                || !executable.contains("depthtex0")
                || !executable.contains("depthtex1")
                || !PHOTON_SWITCH.matcher(executable).find()) {
            return source;
        }
        String patched = insertBeforeMain(source,
                commonHelper() + photonUvHelper(source)
                        + photonVolumetricEntryFootprintHelper());
        if (patched == source) return source;

        // executableGlsl preserves every character index, so the match can safely index the raw
        // source without ever selecting a commented-out Photon switch.
        final Matcher waterSwitch = PHOTON_SWITCH.matcher(executableGlsl(patched));
        if (!waterSwitch.find()) return source;
        final String replacement = """
                vec2 nautilusMaskUv = nautilus_photon_mask_uv(uv);
                vec4 nautilusSegments = vec4(-2.0);
                float nautilusGate = -1.0;
                float nautilusStatus = 1.0;
                vec2 nautilusResolvedMaskUv = nautilusMaskUv;
                bool nautilusFootprintResolved = nautilus_photon_decode_entry_footprint(
                        nautilusMaskUv, isEyeInWater,
                        nautilusSegments, nautilusGate,
                        nautilusStatus, nautilusResolvedMaskUv);
                int nautilusEyeInWater = isEyeInWater;
                vec3 nautilusCameraWorld = world_start_pos;
                vec3 nautilusFrontRay = world_end_pos - nautilusCameraWorld;
                if (nautilusFootprintResolved) {
                    vec2 nautilusSceneUv = nautilus_photon_scene_uv(
                            nautilusResolvedMaskUv);
                    ivec2 nautilusDepthSize = textureSize(depthtex0, 0);
                    if (all(greaterThan(nautilusDepthSize, ivec2(0)))
                            && all(greaterThanEqual(nautilusSceneUv, vec2(0.0)))
                            && all(lessThan(nautilusSceneUv, vec2(1.0)))) {
                        ivec2 nautilusDepthTexel = clamp(ivec2(floor(
                                nautilusSceneUv * vec2(nautilusDepthSize))),
                                ivec2(0), nautilusDepthSize - ivec2(1));
                        float nautilusFrontDepth = texelFetch(
                                depthtex0, nautilusDepthTexel, 0).r;
                        vec4 nautilusFrontH = gbufferProjectionInverse * vec4(
                                nautilusSceneUv * 2.0 - 1.0,
                                nautilusFrontDepth * 2.0 - 1.0, 1.0);
                        if (nautilusFrontH.w == nautilusFrontH.w
                                && abs(nautilusFrontH.w) > 1.0E-7) {
                            vec3 nautilusResolvedFrontRay =
                                    mat3(gbufferModelViewInverse)
                                    * (nautilusFrontH.xyz / nautilusFrontH.w);
                            if (all(equal(nautilusResolvedFrontRay,
                                    nautilusResolvedFrontRay))) {
                                nautilusFrontRay = nautilusResolvedFrontRay;
                            }
                        }
                    }
                }
                float nautilusSceneDistance = length(nautilusFrontRay);
                vec3 nautilusDirectionWorld = nautilusFrontRay
                        / max(nautilusSceneDistance, 1.0E-5);
                bool nautilusOverride = isEyeInWater == 0
                        && nautilus_segments_active(
                                nautilusSegments, nautilusGate,
                                nautilusStatus, nautilusSceneDistance)
                        && nautilus_open_end_direction_safe(
                                nautilusSegments, nautilusDirectionWorld.y);
                if (nautilusOverride) {
                    vec2 nautilusWaterInterval = nautilus_interval(
                            nautilusSegments.rg, nautilusSceneDistance);
                    if (nautilusWaterInterval.x >= 0.0
                            && nautilusWaterInterval.y
                            > nautilusWaterInterval.x + 1.0E-4) {
                        world_start_pos = nautilusCameraWorld
                                + nautilusDirectionWorld * nautilusWaterInterval.x;
                        world_end_pos = nautilusCameraWorld
                                + nautilusDirectionWorld * nautilusWaterInterval.y;
                        nautilusEyeInWater = 1;
                    }
                }
                switch (nautilusEyeInWater) {
                """;
        return patched.substring(0, waterSwitch.start()) + replacement
                + patched.substring(waterSwitch.end());
    }

    /**
     * Photon VL stores camera-to-front fog in c0.  c1 owns the only correct place to apply the
     * medium behind a translucent layer: first fog the opaque background, then blend glass, then
     * let Photon's untouched c0 result fog the combined image from the camera to that glass.
     */
    private static String patchPhotonVolumetricBlend(final String source) {
        final String executable = executableGlsl(source);
        if (!photonUvOptionsResolved() || !photonUvSourceMatchesOptions(source)
                || !executable.contains("vec3 back_position_world")
                || !executable.contains("gbufferModelViewInverse[3].xyz")
                || !executable.contains("mat2x3 analytic_fog = air_fog_analytic(")
                || !executable.contains("fragment_color = blend_layers_with_fog(")
                || !PHOTON_VL_COMPOSITE.matcher(executable).find()) {
            return source;
        }
        final Matcher main = MAIN.matcher(executable);
        if (!main.find()) return source;
        final String blendAnchor = "fragment_color = blend_layers_with_fog(";
        final int blendCall = executable.indexOf(blendAnchor, main.end());
        if (blendCall < 0) return source;
        final int blendEnd = executable.indexOf(';', blendCall);
        if (blendEnd < 0) return source;
        final String nativeBlend = source.substring(blendCall, blendEnd + 1);
        final String replacement = """
                vec2 nautilusMaskUv = nautilus_photon_mask_uv(uv);
                vec2 nautilusBoundaryUv = nautilus_boundary_uv(nautilusMaskUv);
                vec4 nautilusSegments = vec4(-2.0);
                float nautilusGate = -1.0;
                float nautilusStatus = 1.0;
                if (nautilus_entry_candidate(nautilusMaskUv)) {
                    float nautilusRawEntryDepth = texture(
                            NautilusWaterEntryDepth,
                            nautilusBoundaryUv).r;
                    uint nautilusRawEntryDirection = texture(
                            NautilusWaterEntryDirection,
                            nautilusBoundaryUv).r;
                    nautilus_decode_entry(
                            nautilusRawEntryDepth, nautilusRawEntryDirection,
                            nautilusMaskUv,
                            isEyeInWater, nautilusSegments,
                            nautilusGate, nautilusStatus);
                }
                vec3 nautilusCameraWorld =
                        gbufferModelViewInverse[3].xyz + cameraPosition;
                vec3 nautilusBackRay =
                        back_position_world - nautilusCameraWorld;
                float nautilusSceneDistance = length(nautilusBackRay);
                vec3 nautilusDirectionWorld = nautilusBackRay
                        / max(nautilusSceneDistance, 1.0E-5);
                float nautilusFrontDistance =
                        clamp(view_distance, 0.0, nautilusSceneDistance);
                bool nautilusOverride = is_translucent && !back_is_hand
                        && isEyeInWater == 0
                        && nautilus_segments_active(
                                nautilusSegments, nautilusGate,
                                nautilusStatus, nautilusSceneDistance)
                        && nautilus_open_end_direction_safe(
                                nautilusSegments, nautilusDirectionWorld.y);
                if (nautilusOverride) {
                    vec3 nautilusBackFogTransmittance;
                    float nautilusWaterBeforeBack = nautilus_water_length(
                            nautilusSegments, nautilusFrontDistance);
                    nautilus_photon_apply_analytic_medium(
                            fragment_color, nautilusBackFogTransmittance,
                            nautilusSegments,
                            nautilusFrontDistance, nautilusSceneDistance,
                            nautilusWaterBeforeBack,
                            nautilusCameraWorld, nautilusDirectionWorld, is_sky);
                    // Glass is deliberately blended after the behind-glass water interval.
                    fragment_color = fragment_color
                            * (1.0 - translucent_color.a)
                            + translucent_color.rgb;
                } else {
                """ + nativeBlend + "\n                }";
        final String rewritten = source.substring(0, blendCall) + replacement
                + source.substring(blendEnd + 1);
        return insertBeforeMain(rewritten, commonHelper() + photonUvHelper(source)
                + photonAnalyticOrderedHelper(source));
    }

    private static String patchPhotonAnalytic(final String source) {
        final String executable = executableGlsl(source);
        if (!photonUvOptionsResolved() || !photonUvSourceMatchesOptions(source)
                || !executable.contains("vec3 back_position_world")
                || !executable.contains("gbufferModelViewInverse[3].xyz")
                || !executable.contains("mat2x3 analytic_fog = water_fog_simple(")
                || !executable.contains("air_fog_analytic(")
                || !executable.contains("fragment_color = blend_layers_with_fog(")) {
            return source;
        }
        final String anchor = "if (isEyeInWater == 1) {";
        final int waterCall = executable.indexOf(
                "mat2x3 analytic_fog = water_fog_simple(");
        final int condition = waterCall < 0 ? -1 : executable.lastIndexOf(anchor, waterCall);
        if (condition < 0) return source;
        final Matcher main = MAIN.matcher(executable);
        if (!main.find() || condition < main.end()) return source;
        final int openBrace = condition + anchor.lastIndexOf('{');
        final int blockEnd = findMatchingBrace(source, openBrace);
        if (blockEnd < 0 || waterCall > blockEnd) return source;

        final String blendAnchor = "fragment_color = blend_layers_with_fog(";
        final int blendCall = executable.indexOf(blendAnchor, main.end());
        if (blendCall < 0 || blendCall > condition) return source;
        final int blendEnd = executable.indexOf(';', blendCall);
        if (blendEnd < 0 || blendEnd > condition) return source;

        final String local = """
                vec2 nautilusMaskUv = nautilus_photon_mask_uv(uv);
                vec2 nautilusBoundaryUv = nautilus_boundary_uv(nautilusMaskUv);
                vec4 nautilusSegments = vec4(-2.0);
                float nautilusGate = -1.0;
                float nautilusStatus = 1.0;
                if (nautilus_entry_candidate(nautilusMaskUv)) {
                    float nautilusRawEntryDepth = texture(
                            NautilusWaterEntryDepth,
                            nautilusBoundaryUv).r;
                    uint nautilusRawEntryDirection = texture(
                            NautilusWaterEntryDirection,
                            nautilusBoundaryUv).r;
                    nautilus_decode_entry(
                            nautilusRawEntryDepth, nautilusRawEntryDirection,
                            nautilusMaskUv,
                            isEyeInWater, nautilusSegments,
                            nautilusGate, nautilusStatus);
                }
                vec3 nautilusCameraWorld =
                        gbufferModelViewInverse[3].xyz + cameraPosition;
                vec3 nautilusBackRay =
                        back_position_world - nautilusCameraWorld;
                float nautilusSceneDistance = length(nautilusBackRay);
                vec3 nautilusDirectionWorld = nautilusBackRay
                        / max(nautilusSceneDistance, 1.0E-5);
                float nautilusFrontDistance =
                        clamp(view_distance, 0.0, nautilusSceneDistance);
                bool nautilusOverride = isEyeInWater == 0
                        && nautilus_segments_active(
                                nautilusSegments, nautilusGate,
                                nautilusStatus, nautilusSceneDistance)
                        && nautilus_open_end_direction_safe(
                                nautilusSegments, nautilusDirectionWorld.y);
                vec3 nautilusBackFogTransmittance = vec3(1.0);
                """;
        final String bloomyUpdate = source.contains("bloomy_fog")
                && source.contains("luminance_weights_rec2020")
                ? """
                    bloomy_fog = clamp01(dot(nautilusFrontFogTransmittance,
                            vec3(luminance_weights_rec2020)));
                """ : "";
        final String ordered = """
                if (nautilusOverride) {
                    vec3 nautilusFrontFogTransmittance;
                    nautilus_photon_apply_analytic_medium(
                            fragment_color, nautilusFrontFogTransmittance,
                            nautilusSegments, 0.0, nautilusFrontDistance,
                            0.0, nautilusCameraWorld,
                            nautilusDirectionWorld,
                            is_sky && nautilusFrontDistance
                                    >= nautilusSceneDistance - 1.0E-4);
                """ + bloomyUpdate + """
                } else """ + " " + source.substring(condition, blockEnd + 1);

        String rewritten = source.substring(0, condition) + ordered
                + source.substring(blockEnd + 1);
        final String nativeBlend = source.substring(blendCall, blendEnd + 1);
        final String blendReplacement = local + """
                if (nautilusOverride) {
                    if (!back_is_hand) {
                        float nautilusWaterBeforeBack =
                                nautilus_water_length(
                                        nautilusSegments,
                                        nautilusFrontDistance);
                        nautilus_photon_apply_analytic_medium(
                                fragment_color,
                                nautilusBackFogTransmittance,
                                nautilusSegments,
                                nautilusFrontDistance,
                                nautilusSceneDistance,
                                nautilusWaterBeforeBack,
                                nautilusCameraWorld,
                                nautilusDirectionWorld, is_sky);
                    }
                    fragment_color = fragment_color
                            * (1.0 - translucent_color.a)
                            + translucent_color.rgb;
                } else {
                """ + nativeBlend + "\n                }";
        rewritten = rewritten.substring(0, blendCall) + blendReplacement
                + rewritten.substring(blendEnd + 1);
        return insertBeforeMain(rewritten, commonHelper() + photonUvHelper(source)
                + photonAnalyticOrderedHelper(source));
    }

    private static String patchComplementaryDeferred(final String source) {
        final String uvHelper = complementaryUvHelper(source);
        if (uvHelper == null) return source;
        final String fogAnchor = "void DoFog(inout vec4 color";
        final int fog = source.indexOf(fogAnchor);
        if (fog < 0) return source;
        final int openBrace = source.indexOf('{', fog);
        if (openBrace < 0) return source;
        final int blockEnd = findMatchingBrace(source, openBrace);
        if (blockEnd < 0) return source;
        final String nativeBody = source.substring(openBrace + 1, blockEnd);
        if (!nativeBody.contains(
                "if (isEyeInWater == 1) DoWaterFog(color, lViewPos);")) {
            return source;
        }

        final String replacement = """
                vec2 nautilusMaskUv = nautilus_complementary_mask_uv(texCoord);
                vec2 nautilusBoundaryUv = nautilus_boundary_uv(nautilusMaskUv);
                vec4 nautilusSegments = vec4(-2.0);
                float nautilusGate = -1.0;
                float nautilusStatus = 1.0;
                // Reflection rays do not share this screen pixel's entry event. Keep their
                // native pack fog and avoid touching the private samplers altogether.
                if (!isReflection && nautilus_entry_candidate(nautilusMaskUv)) {
                    float nautilusRawEntryDepth = texture(
                            NautilusWaterEntryDepth,
                            nautilusBoundaryUv).r;
                    uint nautilusRawEntryDirection = texture(
                            NautilusWaterEntryDirection,
                            nautilusBoundaryUv).r;
                    nautilus_decode_entry(
                            nautilusRawEntryDepth,
                            nautilusRawEntryDirection,
                            nautilusMaskUv, isEyeInWater,
                            nautilusSegments, nautilusGate,
                            nautilusStatus);
                }
                bool nautilusOverride = !isReflection
                        && (isEyeInWater == 0 || isEyeInWater == 1)
                        && nautilus_segments_active(
                                nautilusSegments, nautilusGate,
                                nautilusStatus, lViewPos)
                        && nautilus_open_end_direction_safe(
                                nautilusSegments, VdotU);
                if (nautilusOverride) {
                    vec4 nautilusFogScattering = vec4(0.0);
                    vec4 nautilusFogTransmittance = vec4(1.0);
                    float nautilusCursor = 0.0;
                    float nautilusCumulativeWater = 0.0;

                    vec2 nautilusFirst = nautilus_interval(
                            nautilusSegments.rg, lViewPos);
                    if (nautilusFirst.y > nautilusFirst.x + 1.0E-4) {
                        if (nautilusFirst.x > nautilusCursor + 1.0E-4) {
                            nautilus_complementary_compose_air(
                                    nautilusFogScattering,
                                    nautilusFogTransmittance, skyFade,
                                    nautilusCursor, nautilusFirst.x,
                                    lViewPos, playerPos, VdotU, VdotS, dither);
                        }
                        nautilus_complementary_compose_water(
                                nautilusFogScattering,
                                nautilusFogTransmittance,
                                nautilusCumulativeWater,
                                nautilusFirst.y - nautilusFirst.x);
                        nautilusCursor = nautilusFirst.y;
                    }

                    vec2 nautilusSecond = nautilus_interval(
                            nautilusSegments.ba, lViewPos);
                    float nautilusSecondStart =
                            max(nautilusSecond.x, nautilusCursor);
                    if (nautilusSecondStart > nautilusCursor + 1.0E-4) {
                        nautilus_complementary_compose_air(
                                nautilusFogScattering,
                                nautilusFogTransmittance, skyFade,
                                nautilusCursor, nautilusSecondStart,
                                lViewPos, playerPos, VdotU, VdotS, dither);
                    }
                    if (nautilusSecond.y > nautilusSecondStart + 1.0E-4) {
                        nautilus_complementary_compose_water(
                                nautilusFogScattering,
                                nautilusFogTransmittance,
                                nautilusCumulativeWater,
                                nautilusSecond.y - nautilusSecondStart);
                        nautilusCursor = nautilusSecond.y;
                    }
                    if (nautilusCursor < lViewPos - 1.0E-4) {
                        nautilus_complementary_compose_air(
                                nautilusFogScattering,
                                nautilusFogTransmittance, skyFade,
                                nautilusCursor, lViewPos,
                                lViewPos, playerPos, VdotU, VdotS, dither);
                    }

                    color = color * nautilusFogTransmittance
                            + nautilusFogScattering;
                    float nautilusBehindWaterLength =
                            nautilus_water_length_range(
                                    nautilusSegments,
                                    min(nautilusGate, lViewPos), lViewPos);
                    if (nautilusBehindWaterLength > 1.0E-4) {
                        float nautilusBehindTone = clamp(
                                GetWaterFog(nautilusBehindWaterLength),
                                0.0, 1.0);
                        color.rgb *= mix(vec3(1.0),
                                vec3(0.80, 0.87, 0.97) * 0.85,
                                nautilusBehindTone);
                    }
                    if (blindness > 0.00001) DoBlindnessFog(color, lViewPos);
                    if (darknessFactor > 0.00001) DoDarknessFog(color, lViewPos);
                } else {
                """ + nativeBody + "\n                }\n";
        String rewritten = source.substring(0, openBrace + 1) + replacement
                + source.substring(blockEnd);
        rewritten = rewritten.substring(0, fog) + commonHelper()
                + uvHelper + complementaryOrderedHelper(source)
                + rewritten.substring(fog);
        return rewritten;
    }

    private static String patchComplementaryTone(final String source) {
        final String uvHelper = complementaryUvHelper(source);
        if (uvHelper == null) return source;
        if (!source.contains("float lViewPos1")) return source;
        final String anchor = "if (isEyeInWater == 1) {";
        final String targetLine = "vec3 underwaterMult = vec3(0.80, 0.87, 0.97);";
        final int target = source.indexOf(targetLine);
        final int condition = target < 0 ? -1 : source.lastIndexOf(anchor, target);
        if (condition < 0) return source;
        final Matcher main = MAIN.matcher(source);
        if (!main.find() || condition < main.end()) return source;
        final int openBrace = condition + anchor.lastIndexOf('{');
        final int blockEnd = findMatchingBrace(source, openBrace);
        if (blockEnd < 0 || target > blockEnd) return source;
        String block = source.substring(condition, blockEnd + 1);
        if (!block.contains(targetLine)
                || !block.contains("volumetricEffect.rgb *= pow2(underwaterMult * 0.55);")) {
            return source;
        }
        final String local = """
                vec2 nautilusMaskUv = nautilus_complementary_mask_uv(texCoord);
                vec2 nautilusBoundaryUv = nautilus_boundary_uv(nautilusMaskUv);
                vec4 nautilusSegments = vec4(-2.0);
                float nautilusGate = -1.0;
                float nautilusStatus = 1.0;
                if (nautilus_entry_candidate(nautilusMaskUv)) {
                    float nautilusRawEntryDepth = texture(
                            NautilusWaterEntryDepth,
                            nautilusBoundaryUv).r;
                    uint nautilusRawEntryDirection = texture(
                            NautilusWaterEntryDirection,
                            nautilusBoundaryUv).r;
                    nautilus_decode_entry(
                            nautilusRawEntryDepth,
                            nautilusRawEntryDirection,
                            nautilusMaskUv, isEyeInWater,
                            nautilusSegments, nautilusGate,
                            nautilusStatus);
                }
                float nautilusSceneDistance = max(lViewPos, lViewPos1);
                float nautilusWorldRayY = normalize(
                        mat3(gbufferModelViewInverse) * viewPos.xyz).y;
                bool nautilusOverride = (isEyeInWater == 0 || isEyeInWater == 1)
                        && nautilus_segments_active(
                                nautilusSegments, nautilusGate,
                                nautilusStatus, nautilusSceneDistance)
                        && nautilus_open_end_direction_safe(
                                nautilusSegments, nautilusWorldRayY);
                float nautilusToneTransmittance = 1.0;
                if (nautilusOverride) {
                    // composite1 runs after translucent blending.  Only water between the
                    // camera and the nearest optical gate may grade the glass itself; water
                    // behind the gate was graded in deferred1 before that glass was drawn.
                    float nautilusToneWaterLength =
                            nautilus_water_length_range(
                                    nautilusSegments, 0.0,
                                    min(nautilusGate,
                                            nautilusSceneDistance));
                    if (nautilusToneWaterLength > 1.0E-4) {
                        nautilusToneTransmittance = 1.0 - clamp(
                                GetWaterFog(nautilusToneWaterLength),
                                0.0, 1.0);
                    }
                }
                float nautilusUnderwaterFactor = nautilusOverride
                        ? 1.0 - nautilusToneTransmittance : 1.0;
                if (isEyeInWater == 1 || nautilusOverride) {
                """;
        block = local + block.substring(anchor.length());
        block = block.replace("if (z0 == 1.0) color.rgb = waterFogColor;",
                "if (z0 == 1.0 && !nautilusOverride) color.rgb = waterFogColor;");
        block = block.replace("color.rgb *= underwaterMult * 0.85;",
                "color.rgb *= mix(vec3(1.0), underwaterMult * 0.85,"
                        + " nautilusUnderwaterFactor);");
        block = block.replace("volumetricEffect.rgb *= pow2(underwaterMult * 0.55);",
                "volumetricEffect.rgb *= mix(vec3(1.0),"
                        + " pow2(underwaterMult * 0.55), nautilusUnderwaterFactor);");
        block = block.replace("lightFog *= underwaterMult;",
                "lightFog *= mix(vec3(1.0), underwaterMult, nautilusUnderwaterFactor);");
        final String rewritten = source.substring(0, condition) + block
                + source.substring(blockEnd + 1);
        return insertBeforeMain(rewritten, commonHelper() + uvHelper);
    }

    private static String commonHelper() {
        return "\n" + MARKER + "\n"
                + "uniform sampler2D " + ENTRY_DEPTH_SAMPLER + ";\n"
                + "uniform usampler2D " + ENTRY_DIRECTION_SAMPLER + ";\n"
                + "uniform sampler2D " + ENTRY_ATLAS_SAMPLER + ";\n"
                + "uniform vec2 " + ENTRY_ATLAS_SIZE_UNIFORM + ";\n"
                + "uniform int " + ENTRY_ATLAS_CARRIER_COUNT_UNIFORM + ";\n"
                + "uniform int " + READY_UNIFORM + ";\n"
                + "uniform int " + ENTRY_GENERATION_UNIFORM + ";\n"
                + "uniform vec2 " + ENTRY_SIZE_UNIFORM + ";\n"
                + "uniform vec4 " + ENTRY_BOUNDS_UNIFORM + ";\n"
                + "uniform int " + DEPTH_REVERSED_UNIFORM + ";\n"
                + "uniform int " + CLEAR_REVERSED_UNIFORM + ";\n"
                + "uniform int " + CAMERA_WATER_UNIFORM + ";\n"
                + "uniform int " + ADAPTER_GENERATION_UNIFORM + ";\n"
                // A changing compile-time generation defeats Iris' source-keyed AST cache and
                // the driver shader cache. Each Program uploads its own expected generation;
                // the actual generation still comes from the immutable boundary snapshot.
                + "uniform int " + EXPECTED_ADAPTER_GENERATION_UNIFORM + ";\n"
                + "bool nautilus_boundary_resources_ready() {\n"
                + "    return " + READY_UNIFORM + " != 0"
                + " && " + ENTRY_GENERATION_UNIFORM + " > 0"
                + " && " + EXPECTED_ADAPTER_GENERATION_UNIFORM + " > 0"
                + " && " + ADAPTER_GENERATION_UNIFORM
                + " == NautilusWaterAdapterGenerationExpected"
                + " && all(equal(" + ENTRY_SIZE_UNIFORM + ", "
                + ENTRY_SIZE_UNIFORM + "))"
                + " && all(greaterThanEqual(" + ENTRY_SIZE_UNIFORM
                + ", vec2(1.0)))"
                + " && all(equal(" + ENTRY_ATLAS_SIZE_UNIFORM + ", "
                + ENTRY_ATLAS_SIZE_UNIFORM + "))"
                + " && all(greaterThanEqual(" + ENTRY_ATLAS_SIZE_UNIFORM
                + ", vec2(1.0)))"
                + " && " + ENTRY_ATLAS_CARRIER_COUNT_UNIFORM + " > 0"
                + " && all(equal(" + ENTRY_BOUNDS_UNIFORM + ", "
                + ENTRY_BOUNDS_UNIFORM + "))"
                + " && all(greaterThanEqual(" + ENTRY_BOUNDS_UNIFORM
                + ".xy, vec2(0.0)))"
                + " && all(lessThanEqual(" + ENTRY_BOUNDS_UNIFORM
                + ".zw, vec2(1.0)))"
                + " && all(greaterThan(" + ENTRY_BOUNDS_UNIFORM
                + ".zw, " + ENTRY_BOUNDS_UNIFORM + ".xy));\n}\n"
                + "bool nautilus_boundary_uv_valid(vec2 value) {\n"
                + "    return all(equal(value, value))"
                + " && all(greaterThanEqual(value, vec2(0.0)))"
                + " && all(lessThan(value, vec2(1.0)))"
                + " && all(greaterThanEqual(" + ENTRY_SIZE_UNIFORM
                + ", vec2(1.0)));\n}\n"
                + "vec2 nautilus_boundary_uv(vec2 value) {\n"
                + "    vec2 safeSize = max(" + ENTRY_SIZE_UNIFORM
                + ", vec2(1.0));\n"
                + "    vec2 halfTexel = 0.5 / safeSize;\n"
                + "    return clamp(value, halfTexel, vec2(1.0) - halfTexel);\n}\n"
                + "bool nautilus_entry_candidate(vec2 uv) {\n"
                + "    if (!nautilus_boundary_resources_ready()"
                + " || !nautilus_boundary_uv_valid(uv)) return false;\n"
                + "    return all(greaterThanEqual(uv, "
                + ENTRY_BOUNDS_UNIFORM + ".xy))"
                + " && all(lessThan(uv, " + ENTRY_BOUNDS_UNIFORM
                + ".zw));\n}\n"
                + "bool nautilus_boundary_depth_hit(float value) {\n"
                + "    if (value != value || value < 0.0 || value > 1.0)"
                + " return false;\n"
                + "    bool clearValue = " + CLEAR_REVERSED_UNIFORM
                + " != 0 ? value <= 1.0E-7 : value >= 0.9999999;\n"
                + "    return !clearValue;\n}\n"
                + "float nautilus_boundary_distance(float value, vec2 uv) {\n"
                + "    if (!nautilus_boundary_depth_hit(value)"
                + " || !nautilus_boundary_uv_valid(uv)) return -1.0;\n"
                + "    float canonicalDepth = " + DEPTH_REVERSED_UNIFORM
                + " != 0 ? 1.0 - value : value;\n"
                + "    vec4 viewPosition = gbufferProjectionInverse"
                + " * vec4(uv * 2.0 - 1.0,"
                + " canonicalDepth * 2.0 - 1.0, 1.0);\n"
                + "    if (viewPosition.w != viewPosition.w"
                + " || abs(viewPosition.w) < 1.0E-7) return -1.0;\n"
                + "    float distanceAlongRay = length("
                + "viewPosition.xyz / viewPosition.w);\n"
                + "    return distanceAlongRay == distanceAlongRay"
                + " && distanceAlongRay >= 0.0"
                + " && distanceAlongRay < 65000.0"
                + " ? distanceAlongRay : -1.0;\n}\n"
                + opticalAtlasHelper()
                + "void nautilus_decode_entry(float entryDepth, uint entryDirection, vec2 uv,"
                + " int eyeInWater,"
                + " out vec4 segments, out float gate, out float status) {\n"
                + "    segments = vec4(-2.0);\n"
                + "    gate = -1.0;\n"
                + "    status = 1.0;\n"
                + "    int cameraWater = " + CAMERA_WATER_UNIFORM + ";\n"
                + "    if (!nautilus_boundary_resources_ready()"
                + " || (eyeInWater != 0 && eyeInWater != 1)"
                + " || (cameraWater != 0 && cameraWater != 1)"
                + " || eyeInWater != cameraWater) return;\n"
                + "    float eventDistance ="
                + " nautilus_boundary_distance(entryDepth, uv);\n"
                + "    if (eventDistance < 0.0) return;\n"
                + "    if (!nautilus_resolve_optical_event(entryDepth, entryDirection, uv, cameraWater,"
                + " eventDistance, segments)) return;\n"
                + "    gate = eventDistance;\n"
                + "    status = 0.0;\n}\n"
                + "bool nautilus_interval_encoding_valid(vec2 value) {\n"
                + "    if (any(notEqual(value, value))) return false;\n"
                + "    if (value.x < 0.0) return value.x >= -1.5;\n"
                + "    return value.x < 65000.0 && value.y >= value.x"
                + " && value.y <= 65504.5;\n}\n"
                + "bool nautilus_segments_conflict(vec4 value) {\n"
                + "    bool firstPresent = value.r >= 0.0;\n"
                + "    bool secondPresent = value.b >= 0.0;\n"
                + "    return any(lessThan(value, vec4(-1.5)))"
                + " || !nautilus_interval_encoding_valid(value.rg)"
                + " || !nautilus_interval_encoding_valid(value.ba)"
                + " || (secondPresent && !firstPresent)"
                + " || (firstPresent && secondPresent"
                + " && value.b < value.g - 1.0E-3);\n}\n"
                + "bool nautilus_status_valid(float value) {\n"
                + "    return value >= 0.0 && value < 0.25;\n}\n"
                + "bool nautilus_gate_valid(float value) {\n"
                + "    return value >= 0.0 && value < 65000.0"
                + " && value == value;\n}\n"
                + "bool nautilus_pixel_valid(vec4 segments, float gate,"
                + " float status) {\n"
                + "    return " + READY_UNIFORM + " != 0"
                + " && " + ENTRY_GENERATION_UNIFORM + " > 0"
                + " && " + EXPECTED_ADAPTER_GENERATION_UNIFORM + " > 0"
                + " && " + ADAPTER_GENERATION_UNIFORM
                + " == NautilusWaterAdapterGenerationExpected"
                + " && !nautilus_segments_conflict(segments)"
                + " && nautilus_gate_valid(gate)"
                + " && nautilus_status_valid(status);\n}\n"
                + "vec2 nautilus_interval(vec2 value, float sceneDistance) {\n"
                + "    if (value.x < 0.0) return vec2(-1.0);\n"
                + "    float endDistance = value.y > 65000.0"
                + " ? sceneDistance : min(value.y, sceneDistance);\n"
                + "    return vec2(clamp(value.x, 0.0, sceneDistance),"
                + " max(0.0, endDistance));\n}\n"
                + "vec2 nautilus_interval_range(vec2 value, float rangeStart,"
                + " float rangeEnd) {\n"
                + "    vec2 interval = nautilus_interval(value, rangeEnd);\n"
                + "    if (interval.x < 0.0) return vec2(-1.0);\n"
                + "    return vec2(max(interval.x, rangeStart),"
                + " min(interval.y, rangeEnd));\n}\n"
                + "float nautilus_water_length(vec4 value, float sceneDistance) {\n"
                + "    if (" + READY_UNIFORM + " == 0"
                + " || nautilus_segments_conflict(value)) return 0.0;\n"
                + "    vec2 first = nautilus_interval(value.rg, sceneDistance);\n"
                + "    vec2 second = nautilus_interval(value.ba, sceneDistance);\n"
                + "    return max(0.0, first.y - first.x)"
                + " + max(0.0, second.y - second.x);\n}\n"
                + "float nautilus_water_length_range(vec4 value, float rangeStart,"
                + " float rangeEnd) {\n"
                + "    if (rangeEnd <= rangeStart || nautilus_segments_conflict(value))"
                + " return 0.0;\n"
                + "    vec2 first = nautilus_interval_range(value.rg, rangeStart, rangeEnd);\n"
                + "    vec2 second = nautilus_interval_range(value.ba, rangeStart, rangeEnd);\n"
                + "    return max(0.0, first.y - first.x)"
                + " + max(0.0, second.y - second.x);\n}\n"
                + "bool nautilus_open_end_direction_safe(vec4 value, float worldRayY) {\n"
                + "    // The visible scene endpoint bounds this low-cost suffix. Reject only an\n"
                + "    // undefined ray; a sign-only Y test made valid cabin pixels switch off\n"
                + "    // whenever the view crossed the horizontal plane.\n"
                + "    if (worldRayY != worldRayY) return false;\n"
                + "    return true;\n}\n"
                + "bool nautilus_segments_reach_gate(vec4 value, float gate,"
                + " float sceneDistance) {\n"
                + "    bool firstContains = value.r >= 0.0"
                + " && value.r <= gate + 1.0E-3"
                + " && value.g >= gate - 1.0E-3;\n"
                + "    bool secondContains = value.b >= 0.0"
                + " && value.b <= gate + 1.0E-3"
                + " && value.a >= gate - 1.0E-3;\n"
                + "    return gate <= sceneDistance + 1.0E-3"
                + " && (firstContains || secondContains);\n}\n"
                + "bool nautilus_segments_active(vec4 value, float gate,"
                + " float status, float sceneDistance) {\n"
                + "    return nautilus_pixel_valid(value, gate, status)"
                + " && sceneDistance >= 0.0 && sceneDistance < 65000.0"
                + " && gate <= sceneDistance + 1.0E-3"
                + " && nautilus_segments_reach_gate(value, gate, sceneDistance)"
                + " && nautilus_water_length(value, sceneDistance) > 1.0E-4;\n}\n";
    }

    /** Exact carrier/event lookup shared by Photon's VL and analytic consumers. */
    private static String opticalAtlasHelper() {
        return """
                bool nautilus_atlas_index_valid(float value) {
                    return value >= 0.0 && value < NautilusWaterVoxelAtlasSize.x
                            * NautilusWaterVoxelAtlasSize.y && value == value;
                }
                vec4 nautilus_atlas_texel(float linearIndex) {
                    float x = mod(linearIndex, NautilusWaterVoxelAtlasSize.x);
                    float y = floor(linearIndex / NautilusWaterVoxelAtlasSize.x);
                    return texture(NautilusWaterVoxelAtlas,
                            (vec2(x, y) + vec2(0.5)) / NautilusWaterVoxelAtlasSize);
                }
                float nautilus_atlas_voxel(vec3 cell, vec4 d4, vec4 d5,
                        out bool lookupValid) {
                    lookupValid = true;
                    vec3 brick = floor(cell / 8.0);
                    vec3 inner = cell - brick * 8.0;
                    vec3 brickOrigin = vec3(floor(d4.xy + vec2(0.5)), 0.0);
                    vec3 brickDims = floor(vec3(d5.x, d5.y, d4.z) + vec3(0.5));
                    vec3 relativeBrick = brick - brickOrigin;
                    if (any(lessThan(relativeBrick, vec3(0.0)))
                            || any(greaterThanEqual(relativeBrick, brickDims))) return 0.0;
                    int low = 0;
                    int high = int(floor(d5.z + 0.5)) - 1;
                    float payloadPlusOne = 0.0;
                    for (int step = 0; step < 24; ++step) {
                        if (low > high) break;
                        int middle = (low + high) / 2;
                        float directory = floor(d4.w + 0.5) + float(middle) * 2.0;
                        if (!nautilus_atlas_index_valid(directory + 1.0)) {
                            lookupValid = false;
                            return 0.0;
                        }
                        vec3 directoryBrick = floor(
                                nautilus_atlas_texel(directory).xyz + vec3(0.5));
                        if (all(lessThan(abs(directoryBrick - relativeBrick), vec3(0.5)))) {
                            payloadPlusOne = floor(
                                    nautilus_atlas_texel(directory + 1.0).r + 0.5);
                            break;
                        }
                        bool before = directoryBrick.y < relativeBrick.y
                                || (directoryBrick.y == relativeBrick.y
                                && (directoryBrick.z < relativeBrick.z
                                || (directoryBrick.z == relativeBrick.z
                                && directoryBrick.x < relativeBrick.x)));
                        if (before) low = middle + 1;
                        else high = middle - 1;
                    }
                    if (payloadPlusOne < 1.0) return 0.0;
                    float voxelIndex = inner.x + 8.0 * (inner.z + 8.0 * inner.y);
                    float payload = payloadPlusOne - 1.0 + voxelIndex;
                    if (!nautilus_atlas_index_valid(payload)) {
                        lookupValid = false;
                        return 0.0;
                    }
                    return nautilus_atlas_texel(payload).r;
                }
                int nautilus_direction_slot(float encoded, int directionCode) {
                    if (encoded > -1.5 || directionCode < 1 || directionCode > 6) return -1;
                    int packedValue = int(floor(-encoded - 1.5));
                    int firstSlot = packedValue / 64;
                    int directionBits = packedValue - firstSlot * 64;
                    if (firstSlot < 0 || directionBits < 1 || directionBits > 63) return -1;
                    int remainingBits = directionBits;
                    int offset = 0;
                    for (int code = 1; code <= 6; ++code) {
                        int present = remainingBits - (remainingBits / 2) * 2;
                        if (code == directionCode) {
                            return present != 0 ? firstSlot + offset : -1;
                        }
                        offset += present;
                        remainingBits /= 2;
                    }
                    return -1;
                }
                bool nautilus_resolve_optical_event(float entryDepth, uint entryDirection,
                        vec2 uv,
                        int cameraWater, float eventDistance, out vec4 segments) {
                    segments = vec4(-2.0);
                    if (!nautilus_boundary_depth_hit(entryDepth)
                            || !nautilus_boundary_uv_valid(uv)) return false;
                    int directionCode = int(entryDirection);
                    if (directionCode < 1 || directionCode > 6) return false;
                    int expectedAxis = (directionCode - 1) / 2;
                    float expectedOutward = ((directionCode - 1) - expectedAxis * 2) != 0
                            ? 1.0 : -1.0;
                    vec4 header = nautilus_atlas_texel(0.0);
                    if (abs(header.z - NautilusWaterVoxelAtlasSize.x) > 0.5
                            || abs(header.w - NautilusWaterVoxelAtlasSize.y) > 0.5) return false;
                    int carrierCount = min(NautilusWaterVoxelCarrierCount,
                            int(floor(header.x + 0.5)));
                    float descriptorBase = floor(header.y + 0.5);
                    int descriptorCapacity = int(floor((NautilusWaterVoxelAtlasSize.x
                            * NautilusWaterVoxelAtlasSize.y - descriptorBase) / 8.0));
                    carrierCount = min(carrierCount, descriptorCapacity);
                    if (carrierCount <= 0) return false;

                    float canonicalDepth = NautilusWaterEntryDepthReversed != 0
                            ? 1.0 - entryDepth : entryDepth;
                    vec4 viewH = gbufferProjectionInverse
                            * vec4(uv * 2.0 - 1.0, canonicalDepth * 2.0 - 1.0, 1.0);
                    if (viewH.w != viewH.w || abs(viewH.w) < 1.0E-7) return false;
                    vec3 viewHit = viewH.xyz / viewH.w;
                    // A direction starts at the rendered eye, but the voxel atlas expects a
                    // point relative to cameraPosition. View bob/hurt moves that eye by the
                    // inverse view translation; omitting it makes glass-cell lookup flicker.
                    vec3 worldRay = mat3(gbufferModelViewInverse) * viewHit;
                    vec3 worldHit = worldRay + gbufferModelViewInverse[3].xyz;
                    float worldLength = length(worldRay);
                    if (worldLength < 1.0E-6 || abs(worldLength - eventDistance) > 0.05)
                        return false;
                    vec3 worldDirection = worldRay / worldLength;
                    bool found = false;
                    vec4 selectedNegative = vec4(0.0);
                    vec4 selectedPositive = vec4(0.0);
                    vec4 selectedMetadata = vec4(0.0);
                    vec3 selectedLocalHit = vec3(0.0);
                    vec3 selectedLocalDirection = vec3(0.0);

                    for (int carrier = 0; carrier < carrierCount; ++carrier) {
                        float descriptor = descriptorBase + float(carrier) * 8.0;
                        if (!nautilus_atlas_index_valid(descriptor + 7.0)) return false;
                        vec4 d6 = nautilus_atlas_texel(descriptor + 6.0);
                        if (any(notEqual(d6, d6))) return false;
                        vec2 entryPixel = uv * NautilusWaterEntrySize;
                        if (entryPixel.x < d6.x || entryPixel.y < d6.y
                                || entryPixel.x >= d6.z || entryPixel.y >= d6.w) continue;
                        vec4 d0 = nautilus_atlas_texel(descriptor);
                        vec4 d1 = nautilus_atlas_texel(descriptor + 1.0);
                        vec4 d2 = nautilus_atlas_texel(descriptor + 2.0);
                        vec4 d3 = nautilus_atlas_texel(descriptor + 3.0);
                        vec4 d4 = nautilus_atlas_texel(descriptor + 4.0);
                        vec4 d5 = nautilus_atlas_texel(descriptor + 5.0);
                        vec4 d7 = nautilus_atlas_texel(descriptor + 7.0);
                        mat4 inverseCarrier = mat4(d0, d1, d2, d3);
                        vec4 localH = inverseCarrier * vec4(worldHit, 1.0);
                        vec4 localDirectionH = inverseCarrier * vec4(worldDirection, 0.0);
                        if (abs(localH.w) < 1.0E-8
                                || any(notEqual(localH, localH))
                                || any(notEqual(localDirectionH, localDirectionH))) continue;
                        vec3 localHit = localH.xyz / localH.w;
                        vec3 localDirection = localDirectionH.xyz;
                        vec3 semanticHit = localHit;
                        vec3 primary = floor(semanticHit);
                        vec3 fraction = semanticHit - primary;
                        // The entry texture is D24S8, so reconstructing a real block face can
                        // move the hit farther than 1/1024 block across a cell boundary. Prefer
                        // an exact primary optical voxel. If it does not carry this direction,
                        // recover only from the finite 3x3x3 corner/edge neighbourhood, and only
                        // when the hit lies on every crossed cell boundary. This admits diagonal
                        // glass at corners without allowing a tangent cell to steal a slot from
                        // an unrelated pixel strip.
                        const float cellTolerance = 0.05;
                        bool lookupValid;
                        float primaryEncoded = nautilus_atlas_voxel(
                                primary, d4, d5, lookupValid);
                        if (!lookupValid) return false;
                        int selectedSlot = -1;
                        int selectedAxis = -1;
                        int eventCount = int(floor(d7.y + 0.5));
                        float eventTableBase = floor(d7.x + 0.5);
                        if (primaryEncoded <= -1.5) {
                            int directionSlot = nautilus_direction_slot(
                                    primaryEncoded, directionCode);
                            if (directionSlot >= eventCount) return false;
                            // A voxel may carry this signed direction while the reconstructed
                            // point lies on a different side of that voxel (notably at a
                            // corner, where floor() can select the tangential cell).  Do not
                            // let such a primary cell steal the event: its expected face must
                            // coincide with the actual hit coordinate along the normal axis.
                            float primaryFace = expectedAxis == 0
                                    ? (expectedOutward > 0.0 ? primary.x + 1.0 : primary.x)
                                    : expectedAxis == 1
                                    ? (expectedOutward > 0.0 ? primary.y + 1.0 : primary.y)
                                    : (expectedOutward > 0.0 ? primary.z + 1.0 : primary.z);
                            float primaryHitAxis = expectedAxis == 0 ? semanticHit.x
                                    : expectedAxis == 1 ? semanticHit.y : semanticHit.z;
                            bool primaryFaceMatches = abs(primaryHitAxis - primaryFace)
                                    <= cellTolerance;
                            if (directionSlot >= 0 && primaryFaceMatches) {
                                selectedSlot = directionSlot;
                                selectedAxis = expectedAxis;
                            }
                        }
                        if (selectedSlot < 0) {
                            float expectedFaceFraction = expectedAxis == 0 ? fraction.x
                                    : expectedAxis == 1 ? fraction.y : fraction.z;
                            bool expectedFaceNear = expectedFaceFraction < cellTolerance
                                    || expectedFaceFraction > 1.0 - cellTolerance;
                            if (!expectedFaceNear) continue;
                            // Prefer the half-open voxel selected by floor() and move only
                            // across the normal boundary first.  Tangential/diagonal recovery
                            // is a second pass for D24 rounding at a corner; otherwise two
                            // coplanar neighbours would look ambiguous and leave a hairline
                            // fail-open seam across an otherwise continuous glass sheet.
                            for (int recoveryPass = 0; recoveryPass < 2; ++recoveryPass) {
                                if (recoveryPass > 0 && selectedSlot >= 0) break;
                                for (int ox = -1; ox <= 1; ++ox) {
                                    for (int oy = -1; oy <= 1; ++oy) {
                                        for (int oz = -1; oz <= 1; ++oz) {
                                        if (ox == 0 && oy == 0 && oz == 0) continue;
                                        bool tangentOffset = (expectedAxis != 0 && ox != 0)
                                                || (expectedAxis != 1 && oy != 0)
                                                || (expectedAxis != 2 && oz != 0);
                                        if (recoveryPass == 0 && tangentOffset) continue;
                                        bool boundaryCompatible = true;
                                        if (ox < 0) boundaryCompatible = boundaryCompatible
                                                && fraction.x < cellTolerance;
                                        if (ox > 0) boundaryCompatible = boundaryCompatible
                                                && fraction.x > 1.0 - cellTolerance;
                                        if (oy < 0) boundaryCompatible = boundaryCompatible
                                                && fraction.y < cellTolerance;
                                        if (oy > 0) boundaryCompatible = boundaryCompatible
                                                && fraction.y > 1.0 - cellTolerance;
                                        if (oz < 0) boundaryCompatible = boundaryCompatible
                                                && fraction.z < cellTolerance;
                                        if (oz > 0) boundaryCompatible = boundaryCompatible
                                                && fraction.z > 1.0 - cellTolerance;
                                        if (!boundaryCompatible) continue;
                                        vec3 offset = vec3(float(ox), float(oy), float(oz));
                                        vec3 candidateCell = primary + offset;
                                        float candidateFace = expectedAxis == 0
                                                ? (expectedOutward > 0.0
                                                ? candidateCell.x + 1.0 : candidateCell.x)
                                                : expectedAxis == 1
                                                ? (expectedOutward > 0.0
                                                ? candidateCell.y + 1.0 : candidateCell.y)
                                                : (expectedOutward > 0.0
                                                ? candidateCell.z + 1.0 : candidateCell.z);
                                        float candidateHitAxis = expectedAxis == 0
                                                ? semanticHit.x
                                                : expectedAxis == 1 ? semanticHit.y
                                                : semanticHit.z;
                                        // The 3x3x3 recovery is intentionally limited to cells
                                        // whose signed face is the one actually intersected.
                                        // Boundary proximity alone is insufficient at a corner:
                                        // an adjacent cell can be tangent to the ray yet expose a
                                        // parallel face one whole block away.
                                        if (abs(candidateHitAxis - candidateFace)
                                                > cellTolerance) continue;
                                        float encoded = nautilus_atlas_voxel(
                                                candidateCell, d4, d5, lookupValid);
                                        if (!lookupValid) return false;
                                        if (encoded > -1.5) continue;
                                        int slot = nautilus_direction_slot(encoded, directionCode);
                                        if (slot < 0) continue;
                                        if (slot >= eventCount) return false;
                                        float candidateEventBase = eventTableBase
                                                + float(slot) * 3.0;
                                        if (!nautilus_atlas_index_valid(candidateEventBase + 2.0))
                                            return false;
                                        vec4 candidateMetadata = nautilus_atlas_texel(
                                                candidateEventBase + 2.0);
                                        if (any(notEqual(candidateMetadata, candidateMetadata))
                                                || abs(candidateMetadata.z - float(slot)) > 0.25
                                                || abs(candidateMetadata.w - d7.z) > 0.25)
                                            return false;
                                        if (candidateMetadata.y < 0.5) continue;
                                        int candidateMetadataAxis = int(floor(
                                                candidateMetadata.x + 0.5));
                                        if (candidateMetadataAxis != expectedAxis) continue;
                                        float candidateOutwardCode = candidateMetadata.y > 2.5
                                                ? candidateMetadata.y - 2.0 : candidateMetadata.y;
                                        float candidateOutward = candidateOutwardCode > 1.5
                                                ? 1.0 : -1.0;
                                        if (abs(candidateOutward - expectedOutward) > 0.25)
                                            continue;
                                        if (selectedSlot >= 0 && selectedSlot != slot) return false;
                                        selectedSlot = slot;
                                        selectedAxis = expectedAxis;
                                        }
                                    }
                                }
                            }
                        }
                        if (selectedSlot < 0 || selectedAxis < 0) continue;
                        if (selectedSlot >= eventCount || found) return false;
                        float eventBase = eventTableBase + float(selectedSlot) * 3.0;
                        if (!nautilus_atlas_index_valid(eventBase + 2.0)) return false;
                        selectedNegative = nautilus_atlas_texel(eventBase);
                        selectedPositive = nautilus_atlas_texel(eventBase + 1.0);
                        selectedMetadata = nautilus_atlas_texel(eventBase + 2.0);
                        if (abs(selectedMetadata.z - float(selectedSlot)) > 0.25)
                            return false;
                        if (abs(selectedMetadata.w - d7.z) > 0.25)
                            return false;
                        int selectedMetadataAxis = int(floor(selectedMetadata.x + 0.5));
                        if (selectedMetadataAxis != selectedAxis) return false;
                        selectedLocalHit = semanticHit;
                        selectedLocalDirection = localDirection;
                        found = true;
                    }
                    if (!found || selectedMetadata.y < 0.5
                            || any(notEqual(selectedNegative, selectedNegative))
                            || any(notEqual(selectedPositive, selectedPositive))
                            || any(notEqual(selectedMetadata, selectedMetadata))) return false;
                    int axis = int(floor(selectedMetadata.x + 0.5));
                    bool floodedExteriorShell = selectedMetadata.y > 2.5;
                    float outwardCode = floodedExteriorShell
                            ? selectedMetadata.y - 2.0 : selectedMetadata.y;
                    float metadataOutward = outwardCode > 1.5 ? 1.0 : -1.0;
                    if (axis != expectedAxis
                            || abs(metadataOutward - expectedOutward) > 0.25) return false;
                    float axisDirection = axis == 0 ? selectedLocalDirection.x
                            : axis == 1 ? selectedLocalDirection.y
                            : selectedLocalDirection.z;
                    float localDirectionLength = length(selectedLocalDirection);
                    if (abs(axisDirection) < 1.0E-7 || localDirectionLength < 1.0E-7)
                        return false;
                    vec3 localStep = selectedLocalDirection / localDirectionLength
                            * 0.0009765625;
                    if (floodedExteriorShell) {
                        // The rendered virtual-water shell normally owns room-side absorption.
                        // The real-water outside path is the one exception: its virtual outside
                        // submission is deliberately skipped, so one override rebuilds the single
                        // continuous prefix through the room plane instead of layering two fogs.
                        vec4 exteriorPlane = metadataOutward > 0.0
                                ? selectedPositive : selectedNegative;
                        vec4 roomPlane = metadataOutward > 0.0
                                ? selectedNegative : selectedPositive;
                        bool rayOutward = axisDirection * metadataOutward > 1.0E-7;
                        bool rayInward = axisDirection * metadataOutward < -1.0E-7;
                        if (!rayOutward && !rayInward) return false;
                        vec3 exteriorStep = rayOutward ? localStep : -localStep;
                        float exteriorField = dot(exteriorPlane.xyz,
                                selectedLocalHit + exteriorStep) + exteriorPlane.w;
                        if (exteriorField > -0.0001) return false;

                        float exteriorFieldAtGate = dot(exteriorPlane.xyz,
                                selectedLocalHit) + exteriorPlane.w;
                        float exteriorDerivative = dot(exteriorPlane.xyz,
                                selectedLocalDirection);
                        if (exteriorDerivative != exteriorDerivative) return false;
                        if (rayOutward) {
                            // Interior -> exterior: append only the ocean suffix. Any room water
                            // before the glass was already handled by the virtual-water material.
                            if (cameraWater != 0) return false;
                            float endDistance = 65504.0;
                            if (exteriorDerivative > 1.0E-7) {
                                float crossing = -exteriorFieldAtGate / exteriorDerivative;
                                if (crossing != crossing
                                        || crossing <= 0.0009765625) return false;
                                endDistance = eventDistance + crossing;
                                if (endDistance != endDistance
                                        || endDistance <= eventDistance) return false;
                            }
                            segments = vec4(eventDistance, endDistance, -1.0, 65504.0);
                            return true;
                        }

                        if (cameraWater != 0) {
                            // Exterior water -> room: native water is continuous through the
                            // glass only while the room-side plane remains wet. Stop at the room
                            // surface when this ray later exits it; otherwise keep the native
                            // prefix through scene depth.
                            float endDistance = eventDistance;
                            float roomField = dot(roomPlane.xyz,
                                    selectedLocalHit + localStep) + roomPlane.w;
                            if (roomField <= -0.0001) {
                                endDistance = 65504.0;
                                float roomFieldAtGate = dot(roomPlane.xyz,
                                        selectedLocalHit) + roomPlane.w;
                                float roomDerivative = dot(roomPlane.xyz,
                                        selectedLocalDirection);
                                if (roomDerivative != roomDerivative) return false;
                                if (roomDerivative > 1.0E-7) {
                                    float crossing = -roomFieldAtGate / roomDerivative;
                                    if (crossing != crossing
                                            || crossing <= 0.0009765625) return false;
                                    endDistance = eventDistance + crossing;
                                    if (endDistance != endDistance
                                            || endDistance <= eventDistance) return false;
                                }
                            }
                            segments = vec4(0.0, endDistance, -1.0, 65504.0);
                            return true;
                        }

                        // Exterior air -> room: recover only the portion of the parent ocean
                        // between its surface and the glass. The flooded room after the gate is
                        // deliberately omitted because its rendered material owns that fog.
                        if (exteriorDerivative >= -1.0E-7) return false;
                        float crossing = -exteriorFieldAtGate / exteriorDerivative;
                        if (crossing != crossing
                                || crossing >= -0.0009765625) return false;
                        float startDistance = eventDistance + crossing;
                        if (startDistance != startDistance || startDistance < 0.0
                                || startDistance >= eventDistance - 0.0009765625) return false;
                        segments = vec4(startDistance, eventDistance, -1.0, 65504.0);
                        return true;
                    }
                    vec4 beforePlane = axisDirection > 0.0
                            ? selectedNegative : selectedPositive;
                    vec4 afterPlane = axisDirection > 0.0
                            ? selectedPositive : selectedNegative;
                    float beforeField = dot(beforePlane.xyz,
                            selectedLocalHit - localStep) + beforePlane.w;
                    float afterField = dot(afterPlane.xyz,
                            selectedLocalHit + localStep) + afterPlane.w;
                    bool beforeWater = beforeField <= -0.0001;
                    bool afterWater = afterField <= -0.0001;
                    if (beforeWater == afterWater || beforeWater != (cameraWater != 0))
                        return false;
                    if (cameraWater != 0) {
                        segments = vec4(0.0, eventDistance, -1.0, 65504.0);
                        return true;
                    }
                    float endDistance = 65504.0;
                    float fieldAtGate = dot(afterPlane.xyz, selectedLocalHit) + afterPlane.w;
                    float fieldDerivative = dot(afterPlane.xyz, selectedLocalDirection);
                    if (fieldDerivative > 1.0E-7) {
                        float crossing = -fieldAtGate / fieldDerivative;
                        if (crossing <= 0.0009765625) return false;
                        endDistance = eventDistance + crossing;
                    }
                    segments = vec4(eventDistance, endDistance, -1.0, 65504.0);
                    return true;
                }
                """;
    }

    private static String photonUvHelper(final String source) {
        if (buildOptions.temporalAa() == 0) {
            return """
                    vec2 nautilus_photon_mask_uv(vec2 coord) {
                        return coord;
                    }
                    vec2 nautilus_photon_scene_uv(vec2 coord) {
                        return coord;
                    }
                    """;
        }
        if (buildOptions.temporalAa() == 1 && buildOptions.photonTaau() == 1) {
            return """
                    vec2 nautilus_photon_mask_uv(vec2 coord) {
                        return (coord - 0.5 * taa_offset) / taau_render_scale;
                    }
                    vec2 nautilus_photon_scene_uv(vec2 coord) {
                        return coord * taau_render_scale + 0.5 * taa_offset;
                    }
                    """;
        }
        if (buildOptions.temporalAa() == 1 && buildOptions.photonTaau() == 0) {
            return """
                    vec2 nautilus_photon_mask_uv(vec2 coord) {
                        return coord - 0.33 * taa_offset;
                    }
                    vec2 nautilus_photon_scene_uv(vec2 coord) {
                        return coord + 0.33 * taa_offset;
                    }
                    """;
        }
        return """
                vec2 nautilus_photon_mask_uv(vec2 coord) {
                    return coord;
                }
                vec2 nautilus_photon_scene_uv(vec2 coord) {
                    return coord;
                }
                """;
    }

    /**
     * Photon renders c0 volumetric fog at a configurable fraction of the scene resolution. At
     * the default 0.5 scale one fog fragment covers a 2x2 footprint in the full-resolution private
     * entry mask. Sampling one nearest depth at the footprint centre lands exactly on a texel
     * boundary; Photon's sub-pixel TAA offset then alternates the selected texel every frame. A
     * stable footprint resolve removes both the visible square grid and the on/off temporal
     * flicker. The representative is selected in one fixed order and its UV is returned so the
     * native water raymarch uses the same ray that produced the finite water interval.
     */
    private static String photonVolumetricEntryFootprintHelper() {
        return """
                bool nautilus_photon_decode_entry_footprint(
                        vec2 maskUv, int eyeInWater,
                        out vec4 segments, out float gate, out float status,
                        out vec2 resolvedMaskUv) {
                    segments = vec4(-2.0);
                    gate = -1.0;
                    status = 1.0;
                    resolvedMaskUv = maskUv;
                    if (!nautilus_boundary_resources_ready()
                            || !nautilus_boundary_uv_valid(maskUv)) return false;

                    ivec2 entrySize = ivec2(max(
                            NautilusWaterEntrySize, vec2(1.0)));
                    vec2 coveredTexels = clamp(ceil(
                            fwidth(maskUv * vec2(entrySize)) - vec2(1.0E-4)),
                            vec2(1.0), vec2(2.0));
                    ivec2 span = ivec2(coveredTexels);
                    ivec2 base = ivec2(floor(maskUv * vec2(entrySize)
                            - 0.5 * vec2(span - ivec2(1))));
                    bool found = false;

                    // Prefer the same upper-right representative as Photon's full-resolution
                    // view_texel mapping, then fall back in a fixed order at an aperture edge.
                    // Competing by gate made the winning ray change with sub-pixel depth noise.
                    for (int y = 1; y >= 0; --y) {
                        for (int x = 1; x >= 0; --x) {
                            if (found) continue;
                            if (x >= span.x || y >= span.y) continue;
                            ivec2 entryTexel = base + ivec2(x, y);
                            if (any(lessThan(entryTexel, ivec2(0)))
                                    || any(greaterThanEqual(entryTexel, entrySize))) continue;
                            vec2 sampleUv = (vec2(entryTexel) + vec2(0.5))
                                    / vec2(entrySize);
                            if (!nautilus_entry_candidate(sampleUv)) continue;

                            vec4 candidateSegments;
                            float candidateGate;
                            float candidateStatus;
                            float rawEntryDepth = texelFetch(
                                    NautilusWaterEntryDepth, entryTexel, 0).r;
                            uint rawEntryDirection = texelFetch(
                                    NautilusWaterEntryDirection, entryTexel, 0).r;
                            nautilus_decode_entry(rawEntryDepth, rawEntryDirection,
                                    sampleUv, eyeInWater,
                                    candidateSegments, candidateGate, candidateStatus);
                            if (candidateStatus != 0.0 || candidateGate < 0.0
                                    || nautilus_segments_conflict(candidateSegments)) continue;
                            found = true;
                            segments = candidateSegments;
                            gate = candidateGate;
                            status = candidateStatus;
                            resolvedMaskUv = sampleUv;
                        }
                    }
                    return found;
                }
                """;
    }

    /** A known-enabled Photon TAA mode is ambiguous until Iris resolves TAA versus TAAU. */
    private static boolean photonUvOptionsResolved() {
        if ((buildOptions.temporalAa() != 0 && buildOptions.temporalAa() != 1)
                || (buildOptions.photonTaau() != 0
                && buildOptions.photonTaau() != 1)) return false;
        if (buildOptions.temporalAa() == 0) return true;
        if (buildOptions.photonTaau() == 0) return true;
        return buildOptions.photonTaau() == 1
                && Float.isFinite(buildOptions.photonTaauRenderScale());
    }

    /** The option snapshot and the already-selected Photon source branch must agree exactly. */
    private static boolean photonUvSourceMatchesOptions(final String source) {
        final String executable = executableGlsl(source);
        if (buildOptions.temporalAa() == 0) {
            return !PHOTON_TAAU_JITTER.matcher(executable).find()
                    && !PHOTON_TAA_JITTER.matcher(executable).find();
        }
        if (buildOptions.temporalAa() != 1) return false;
        if (buildOptions.photonTaau() == 1) {
            return PHOTON_TAAU_JITTER.matcher(executable).find();
        }
        return buildOptions.photonTaau() == 0
                && PHOTON_TAA_JITTER.matcher(executable).find();
    }

    private static String photonAnalyticOrderedHelper(final String source) {
        final String airBody = executableGlsl(source).contains("air_fog_analytic(") ? """
                    mat2x3 nautilusAirPart = air_fog_analytic(
                            rayOrigin + rayDirection * startDistance,
                            rayOrigin + rayDirection * endDistance,
                            sky, eye_skylight, 1.0);
                    accumulatedScattering += accumulatedTransmittance
                            * nautilusAirPart[0];
                    accumulatedTransmittance *= nautilusAirPart[1];
                """ : "";
        return """
                void nautilus_photon_compose_analytic_air(
                        inout vec3 accumulatedScattering,
                        inout vec3 accumulatedTransmittance,
                        float startDistance, float endDistance,
                        vec3 rayOrigin, vec3 rayDirection, bool sky) {
                    if (endDistance <= startDistance + 1.0E-4) return;
                """ + airBody + """
                }

                void nautilus_photon_compose_analytic_water(
                        inout vec3 accumulatedScattering,
                        inout vec3 accumulatedTransmittance,
                        inout float cumulativeWaterDistance,
                        float waterDistance, float LoV) {
                    if (waterDistance <= 1.0E-4) return;
                    mat2x3 nautilusWaterAfter = water_fog_simple(
                            light_color, ambient_color,
                            water_absorption_coeff,
                            vec2(0.0, eye_skylight),
                            cumulativeWaterDistance + waterDistance,
                            LoV, 15.0 * eye_skylight);
                    vec3 nautilusWaterScattering =
                            nautilusWaterAfter[0];
                    vec3 nautilusWaterTransmittance =
                            nautilusWaterAfter[1];
                    if (cumulativeWaterDistance > 1.0E-4) {
                        mat2x3 nautilusWaterBefore = water_fog_simple(
                                light_color, ambient_color,
                                water_absorption_coeff,
                                vec2(0.0, eye_skylight),
                                cumulativeWaterDistance, LoV,
                                15.0 * eye_skylight);
                        vec3 nautilusSafeBefore = max(
                                nautilusWaterBefore[1], vec3(1.0E-5));
                        nautilusWaterScattering = max(vec3(0.0),
                                (nautilusWaterAfter[0]
                                        - nautilusWaterBefore[0])
                                        / nautilusSafeBefore);
                        nautilusWaterTransmittance = clamp(
                                nautilusWaterAfter[1] / nautilusSafeBefore,
                                vec3(0.0), vec3(1.0));
                    }
                    accumulatedScattering += accumulatedTransmittance
                            * nautilusWaterScattering;
                    accumulatedTransmittance *=
                            nautilusWaterTransmittance;
                    cumulativeWaterDistance += waterDistance;
                }

                void nautilus_photon_apply_analytic_medium(
                        inout vec3 color, out vec3 rangeTransmittance,
                        vec4 segments, float rangeStart, float rangeEnd,
                        float cumulativeWaterDistance,
                        vec3 rayOrigin, vec3 rayDirection, bool skyAtEnd) {
                    vec3 nautilusScattering = vec3(0.0);
                    vec3 nautilusTransmittance = vec3(1.0);
                    float nautilusCursor = rangeStart;
                    float nautilusLoV = dot(rayDirection, light_dir);

                    vec2 nautilusFirst = nautilus_interval_range(
                            segments.rg, rangeStart, rangeEnd);
                    if (nautilusFirst.y > nautilusFirst.x + 1.0E-4) {
                        float nautilusFirstStart =
                                max(nautilusFirst.x, nautilusCursor);
                        nautilus_photon_compose_analytic_air(
                                nautilusScattering, nautilusTransmittance,
                                nautilusCursor, nautilusFirstStart,
                                rayOrigin, rayDirection, false);
                        if (nautilusFirst.y
                                > nautilusFirstStart + 1.0E-4) {
                            nautilus_photon_compose_analytic_water(
                                    nautilusScattering,
                                    nautilusTransmittance,
                                    cumulativeWaterDistance,
                                    nautilusFirst.y - nautilusFirstStart,
                                    nautilusLoV);
                            nautilusCursor = nautilusFirst.y;
                        }
                    }

                    vec2 nautilusSecond = nautilus_interval_range(
                            segments.ba, rangeStart, rangeEnd);
                    float nautilusSecondStart =
                            max(nautilusSecond.x, nautilusCursor);
                    if (nautilusSecondStart > nautilusCursor + 1.0E-4) {
                        nautilus_photon_compose_analytic_air(
                                nautilusScattering, nautilusTransmittance,
                                nautilusCursor, nautilusSecondStart,
                                rayOrigin, rayDirection, false);
                    }
                    if (nautilusSecond.y
                            > nautilusSecondStart + 1.0E-4) {
                        nautilus_photon_compose_analytic_water(
                                nautilusScattering,
                                nautilusTransmittance,
                                cumulativeWaterDistance,
                                nautilusSecond.y - nautilusSecondStart,
                                nautilusLoV);
                        nautilusCursor = nautilusSecond.y;
                    }

                    if (nautilusCursor < rangeEnd - 1.0E-4) {
                        nautilus_photon_compose_analytic_air(
                                nautilusScattering, nautilusTransmittance,
                                nautilusCursor, rangeEnd,
                                rayOrigin, rayDirection,
                                skyAtEnd);
                    }
                    color = color * nautilusTransmittance
                            + nautilusScattering;
                    rangeTransmittance = nautilusTransmittance;
                }
                """;
    }

    private static String blissUvHelper(final String source) {
        // Iris' JcppProcessor keeps comments. Bliss deliberately leaves a commented-out
        // `flat varying vec2 TAA_Offset;` in fogBehindTranslucent_pass, so an identifier-only
        // test would emit a reference to a varying that does not exist in the compiled stage.
        final String executable = executableGlsl(source);
        final boolean taaActive = BLISS_TAA_ACTIVE_MARKER.matcher(executable).find();
        final boolean hasTaaOffset = BLISS_TAA_OFFSET_DECLARATION.matcher(executable).find();
        if ((buildOptions.temporalAa() != 0 && buildOptions.temporalAa() != 1)
                || !hasTaaOffset && taaActive != (buildOptions.temporalAa() == 1)) return null;
        final String renderScale =
                "vec2(" + Float.toString(buildOptions.blissRenderScale()) + ")";
        if (hasTaaOffset) {
            return """
                    vec2 nautilus_bliss_mask_uv(vec2 coord) {
                        return (coord - vec2(TAA_Offset) * texelSize * 0.5)
                                / RENDER_SCALE;
                    }
                    vec2 nautilus_bliss_scene_uv(vec2 coord) {
                        return coord * RENDER_SCALE
                                + vec2(TAA_Offset) * texelSize * 0.5;
                    }
                    """.replace("RENDER_SCALE", renderScale);
        }
        if (taaActive
                && executable.contains("uniform int frameCounter")
                && executable.contains("uniform vec2 texelSize")) {
            // fogBehindTranslucent intentionally comments out TAA_Offset even though every
            // gbuffer vertex is jittered.  Its surviving R2_dither branch is a reliable
            // post-preprocess activation marker; reconstruct the locked eight-sample offset
            // from frameCounter without introducing any new preprocessor directive.
            return """
                    vec2 nautilus_bliss_frame_offset() {
                        int index = frameCounter % 8;
                        if (index == 0) return vec2( 1.0, -3.0) / 8.0;
                        if (index == 1) return vec2(-1.0,  3.0) / 8.0;
                        if (index == 2) return vec2( 5.0,  1.0) / 8.0;
                        if (index == 3) return vec2(-3.0, -5.0) / 8.0;
                        if (index == 4) return vec2(-5.0,  5.0) / 8.0;
                        if (index == 5) return vec2(-7.0, -1.0) / 8.0;
                        if (index == 6) return vec2( 3.0,  7.0) / 8.0;
                        return vec2(7.0, 7.0) / 8.0;
                    }
                    vec2 nautilus_bliss_mask_uv(vec2 coord) {
                        return (coord - nautilus_bliss_frame_offset()
                                * texelSize * 0.5) / RENDER_SCALE;
                    }
                    vec2 nautilus_bliss_scene_uv(vec2 coord) {
                        return coord * RENDER_SCALE
                                + nautilus_bliss_frame_offset()
                                * texelSize * 0.5;
                    }
                    """.replace("RENDER_SCALE", renderScale);
        }
        return """
                vec2 nautilus_bliss_mask_uv(vec2 coord) {
                    return coord / RENDER_SCALE;
                }
                vec2 nautilus_bliss_scene_uv(vec2 coord) {
                    return coord * RENDER_SCALE;
                }
                """.replace("RENDER_SCALE", renderScale);
    }

    private static String blissAlignmentHelper(final String source) {
        final String safe = buildOptions.blissAlignmentSafe() ? "true" : "false";
        return "bool nautilus_bliss_alignment_valid() {\n"
                + "    return " + safe + ";\n}\n";
    }

    /** Stable representative for Bliss' configurable low-resolution volumetric viewport. */
    private static String blissEntryFootprintHelper() {
        return """
                bool nautilus_bliss_decode_entry_footprint(
                        vec2 maskUv, int eyeInWater,
                        out vec4 segments, out float gate, out float status,
                        out vec2 resolvedMaskUv) {
                    segments = vec4(-2.0);
                    gate = -1.0;
                    status = 1.0;
                    resolvedMaskUv = maskUv;
                    if (!nautilus_boundary_resources_ready()
                            || !nautilus_boundary_uv_valid(maskUv)) return false;

                    ivec2 entrySize = ivec2(max(
                            NautilusWaterEntrySize, vec2(1.0)));
                    vec2 coveredTexels = max(ceil(
                            fwidth(maskUv * vec2(entrySize)) - vec2(1.0E-4)),
                            vec2(1.0));
                    if (any(greaterThan(coveredTexels, vec2(4.0)))) return false;
                    ivec2 span = ivec2(coveredTexels);
                    ivec2 base = ivec2(floor(maskUv * vec2(entrySize)
                            - 0.5 * vec2(span - ivec2(1))));
                    bool found = false;

                    // Bliss shades the same reduced-resolution footprint every frame.  A fixed
                    // upper-right-first order keeps its representative stable at glass edges.
                    for (int y = 3; y >= 0; --y) {
                        for (int x = 3; x >= 0; --x) {
                            if (found) continue;
                            if (x >= span.x || y >= span.y) continue;
                            ivec2 entryTexel = base + ivec2(x, y);
                            if (any(lessThan(entryTexel, ivec2(0)))
                                    || any(greaterThanEqual(entryTexel, entrySize))) continue;
                            vec2 sampleUv = (vec2(entryTexel) + vec2(0.5))
                                    / vec2(entrySize);
                            if (!nautilus_entry_candidate(sampleUv)) continue;

                            vec4 candidateSegments;
                            float candidateGate;
                            float candidateStatus;
                            float rawEntryDepth = texelFetch(
                                    NautilusWaterEntryDepth, entryTexel, 0).r;
                            uint rawEntryDirection = texelFetch(
                                    NautilusWaterEntryDirection, entryTexel, 0).r;
                            nautilus_decode_entry(rawEntryDepth, rawEntryDirection,
                                    sampleUv, eyeInWater,
                                    candidateSegments, candidateGate, candidateStatus);
                            if (candidateStatus != 0.0 || candidateGate < 0.0
                                    || nautilus_segments_conflict(
                                    candidateSegments)) continue;
                            // Do not let a coplanar opaque frame steal the reduced-resolution
                            // representative from a clear cutout pixel in the same footprint.
                            // Sky/DH endpoints retain the caller's native reconstruction below.
                            vec2 sceneUv = nautilus_bliss_scene_uv(sampleUv);
                            if (!nautilus_boundary_uv_valid(sceneUv)) continue;
                            ivec2 sceneSize = textureSize(depthtex1, 0);
                            ivec2 sceneTexel = clamp(ivec2(floor(sceneUv * vec2(sceneSize))),
                                    ivec2(0), sceneSize - ivec2(1));
                            float sceneDepth = texelFetch(depthtex1, sceneTexel, 0).r;
                            if (sceneDepth < 0.9999999) {
                                vec4 sceneH = gbufferProjectionInverse
                                        * vec4(sampleUv * 2.0 - 1.0, sceneDepth * 2.0 - 1.0, 1.0);
                                if (abs(sceneH.w) < 1.0E-7) continue;
                                float sceneDistance = length(sceneH.xyz / sceneH.w);
                                if (sceneDistance <= candidateGate + 1.0E-4
                                        || nautilus_water_length_range(candidateSegments,
                                                candidateGate, sceneDistance) <= 1.0E-4) continue;
                            }
                            found = true;
                            segments = candidateSegments;
                            gate = candidateGate;
                            status = candidateStatus;
                            resolvedMaskUv = sampleUv;
                        }
                    }
                    return found;
                }
                """;
    }

    /** Bliss' native Overworld air and cloud result from the camera to one ray distance. */
    private static String blissAirPrefixHelper() {
        return """
                void nautilus_bliss_air_prefix(out vec4 fogPrefix,
                        float distanceAlongRay, vec3 rayDirection,
                        float noise1, float noise2, vec3 directColor,
                        vec3 indirectColor, vec3 dynamicIndirectColor) {
                    fogPrefix = vec4(0.0, 0.0, 0.0, 1.0);
                    if (distanceAlongRay <= 1.0E-4) return;
                    vec3 nautilusCloudDepth = vec3(0.0);
                    vec4 nautilusClouds = renderClouds(
                            rayDirection * distanceAlongRay,
                            vec2(noise1, noise2), directColor,
                            indirectColor, nautilusCloudDepth);
                    float nautilusAtmosphereAlpha = 1.0;
                    vec4 nautilusAtmosphere = GetVolumetricFog(
                            rayDirection * distanceAlongRay,
                            vec2(noise1, noise2), directColor,
                            indirectColor, dynamicIndirectColor,
                            nautilusAtmosphereAlpha);
                    nautilusClouds.a *= nautilusAtmosphereAlpha;
                    fogPrefix = vec4(
                            nautilusClouds.rgb * nautilusAtmosphere.a
                                    * nautilusAtmosphereAlpha
                                    + nautilusAtmosphere.rgb,
                            nautilusAtmosphere.a * nautilusClouds.a);
                }

                void nautilus_bliss_compose_air(inout vec4 accumulatedFog,
                        float startDistance, float endDistance,
                        vec3 rayDirection, float noise1, float noise2,
                        vec3 directColor, vec3 indirectColor,
                        vec3 dynamicIndirectColor) {
                    if (endDistance <= startDistance + 1.0E-4) return;
                    vec4 nautilusStartPrefix;
                    vec4 nautilusEndPrefix;
                    nautilus_bliss_air_prefix(nautilusStartPrefix,
                            startDistance, rayDirection, noise1, noise2,
                            directColor, indirectColor, dynamicIndirectColor);
                    nautilus_bliss_air_prefix(nautilusEndPrefix,
                            endDistance, rayDirection, noise1, noise2,
                            directColor, indirectColor, dynamicIndirectColor);
                    float nautilusSafeStart =
                            max(nautilusStartPrefix.a, 1.0E-5);
                    vec3 nautilusPartScattering = max(vec3(0.0),
                            (nautilusEndPrefix.rgb
                                    - nautilusStartPrefix.rgb)
                                    / nautilusSafeStart);
                    float nautilusPartTransmittance = clamp(
                            nautilusEndPrefix.a / nautilusSafeStart,
                            0.0, 1.0);
                    accumulatedFog.rgb += accumulatedFog.a
                            * nautilusPartScattering;
                    accumulatedFog.a *= nautilusPartTransmittance;
                }
                """;
    }

    private static String complementaryUvHelper(final String source) {
        // TAA_MODE alone is insufficient: Complementary's potato profiles leave that option at
        // one while DETAIL_QUALITY removes the derived TAA define. The preprocessed stage is the
        // authority, while the CPU snapshot must agree so its SceneDepth transform is the exact
        // inverse of this lookup. Any disagreement leaves the whole stage native.
        final boolean taaActive = COMPLEMENTARY_TAA_ACTIVE
                .matcher(executableGlsl(source)).find();
        final int taaMode = buildOptions.complementaryTaaMode();
        if ((taaMode != 0 && taaMode != 1)
                || taaActive != (taaMode == 1)) return null;
        if (!taaActive) {
            return """
                    vec2 nautilus_complementary_mask_uv(vec2 coord) {
                        return coord;
                    }
                    """;
        }
        final int jitterMode = buildOptions.complementaryTaaJitter();
        if (jitterMode == UNKNOWN_SHADER_OPTION) return null;
        if (jitterMode == 0) {
            return """
                    vec2 nautilus_complementary_mask_uv(vec2 coord) {
                        return coord;
                    }
                    """;
        }
        final String jitterScale = switch (jitterMode) {
            case 1 -> "0.125";
            case 2 -> "0.33";
            case 3 -> "1.0";
            default -> null;
        };
        if (jitterScale == null) return null;
        return """
                vec2 nautilus_complementary_mask_uv(vec2 coord) {
                    vec2 jitter;
                    int frame = int(framemod8);
                    if (frame == 0) jitter = vec2( 0.125, -0.375);
                    else if (frame == 1) jitter = vec2(-0.125,  0.375);
                    else if (frame == 2) jitter = vec2( 0.625,  0.125);
                    else if (frame == 3) jitter = vec2( 0.375, -0.625);
                    else if (frame == 4) jitter = vec2(-0.625,  0.625);
                    else if (frame == 5) jitter = vec2(-0.875, -0.125);
                    else if (frame == 6) jitter = vec2( 0.375, -0.875);
                    else jitter = vec2(0.875, 0.875);
                    jitter *= %s;
                    return coord - 0.5 * jitter / vec2(viewWidth, viewHeight);
                }
                """.formatted(jitterScale);
    }

    private static String complementaryOrderedHelper(final String source) {
        final String executable = executableGlsl(source);
        final String caveFog = executable.contains("DoCaveFog(") ? """
                    DoCaveFog(nautilusZero, prefixDistance);
                    DoCaveFog(nautilusOne, prefixDistance);
                """ : "";
        final String atmosphericFog = executable.contains("DoAtmosphericFog(") ? """
                    DoAtmosphericFog(nautilusZero, nautilusPlayerPos,
                            prefixDistance, VdotS);
                    DoAtmosphericFog(nautilusOne, nautilusPlayerPos,
                            prefixDistance, VdotS);
                """ : "";
        final String borderFog = executable.contains("DoBorderFog(") ? """
                    float nautilusBorderDistance = max(
                            length(nautilusPlayerPos.xz),
                            abs(nautilusPlayerPos.y));
                    DoBorderFog(nautilusZero, nautilusZeroSky,
                            nautilusBorderDistance, VdotU, VdotS, dither);
                    DoBorderFog(nautilusOne, nautilusOneSky,
                            nautilusBorderDistance, VdotU, VdotS, dither);
                """ : "";
        return """
                void nautilus_complementary_air_prefix(
                        out vec4 prefixScattering,
                        out vec4 prefixTransmittance,
                        out float prefixSkyFade,
                        float prefixDistance, float fullDistance,
                        vec3 fullPlayerPos, float VdotU, float VdotS,
                        float dither) {
                    float nautilusRatio = prefixDistance
                            / max(fullDistance, 1.0E-5);
                    vec3 nautilusPlayerPos = fullPlayerPos * nautilusRatio;
                    vec4 nautilusZero = vec4(0.0);
                    vec4 nautilusOne = vec4(1.0);
                    float nautilusZeroSky = 0.0;
                    float nautilusOneSky = 0.0;
                """ + caveFog + atmosphericFog + borderFog + """
                    prefixScattering = nautilusZero;
                    prefixTransmittance = max(vec4(0.0),
                            nautilusOne - nautilusZero);
                    prefixSkyFade = max(nautilusZeroSky, nautilusOneSky);
                }

                void nautilus_complementary_compose_air(
                        inout vec4 accumulatedScattering,
                        inout vec4 accumulatedTransmittance,
                        inout float accumulatedSkyFade,
                        float startDistance, float endDistance,
                        float fullDistance, vec3 fullPlayerPos,
                        float VdotU, float VdotS, float dither) {
                    if (endDistance <= startDistance + 1.0E-4) return;
                    vec4 nautilusStartScattering;
                    vec4 nautilusStartTransmittance;
                    float nautilusStartSky;
                    vec4 nautilusEndScattering;
                    vec4 nautilusEndTransmittance;
                    float nautilusEndSky;
                    nautilus_complementary_air_prefix(
                            nautilusStartScattering,
                            nautilusStartTransmittance,
                            nautilusStartSky, startDistance, fullDistance,
                            fullPlayerPos, VdotU, VdotS, dither);
                    nautilus_complementary_air_prefix(
                            nautilusEndScattering,
                            nautilusEndTransmittance,
                            nautilusEndSky, endDistance, fullDistance,
                            fullPlayerPos, VdotU, VdotS, dither);
                    vec4 nautilusSafeStart = max(
                            nautilusStartTransmittance, vec4(1.0E-5));
                    vec4 nautilusPartScattering = max(vec4(0.0),
                            (nautilusEndScattering
                                    - nautilusStartScattering)
                                    / nautilusSafeStart);
                    vec4 nautilusPartTransmittance = clamp(
                            nautilusEndTransmittance / nautilusSafeStart,
                            vec4(0.0), vec4(1.0));
                    accumulatedScattering += accumulatedTransmittance
                            * nautilusPartScattering;
                    accumulatedTransmittance *=
                            nautilusPartTransmittance;
                    accumulatedSkyFade = max(accumulatedSkyFade,
                            max(nautilusStartSky, nautilusEndSky));
                }

                void nautilus_complementary_compose_water(
                        inout vec4 accumulatedScattering,
                        inout vec4 accumulatedTransmittance,
                        inout float cumulativeWaterDistance,
                        float waterDistance) {
                    if (waterDistance <= 1.0E-4) return;
                    float nautilusBeforeFog =
                            GetWaterFog(cumulativeWaterDistance);
                    float nautilusAfterFog = GetWaterFog(
                            cumulativeWaterDistance + waterDistance);
                    float nautilusPartTransmittance = clamp(
                            (1.0 - nautilusAfterFog)
                                    / max(1.0 - nautilusBeforeFog, 1.0E-5),
                            0.0, 1.0);
                    vec4 nautilusPartScattering =
                            vec4(waterFogColor, 0.0)
                                    * (1.0 - nautilusPartTransmittance);
                    accumulatedScattering += accumulatedTransmittance
                            * nautilusPartScattering;
                    accumulatedTransmittance *=
                            nautilusPartTransmittance;
                    cumulativeWaterDistance += waterDistance;
                }
                """;
    }

    /**
     * Replaces comments with spaces while preserving every character index and line break.
     * Iris deliberately keeps shader-pack comments after preprocessing, so semantic probes must
     * never treat a commented declaration or sample expression as executable GLSL.
     */
    private static String executableGlsl(final String source) {
        final char[] code = source.toCharArray();
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < code.length; index++) {
            final char current = code[index];
            final char next = index + 1 < code.length ? code[index + 1] : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                } else {
                    code[index] = ' ';
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    code[index] = ' ';
                    code[index + 1] = ' ';
                    index++;
                    blockComment = false;
                } else if (current != '\n' && current != '\r') {
                    code[index] = ' ';
                }
                continue;
            }
            if (current == '/' && next == '/') {
                code[index] = ' ';
                code[index + 1] = ' ';
                index++;
                lineComment = true;
            } else if (current == '/' && next == '*') {
                code[index] = ' ';
                code[index + 1] = ' ';
                index++;
                blockComment = true;
            }
        }
        return new String(code);
    }

    private static String insertBeforeMain(final String source, final String declaration) {
        final Matcher main = MAIN.matcher(executableGlsl(source));
        if (!main.find()) return source;
        return source.substring(0, main.start()) + declaration + source.substring(main.start());
    }

    private static int findMatchingBrace(final String source, final int openBrace) {
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            final char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length()) {
                final char next = source.charAt(index + 1);
                if (next == '/') {
                    index += 2;
                    while (index < source.length() && source.charAt(index) != '\n') index++;
                    continue;
                }
                if (next == '*') {
                    index += 2;
                    while (index + 1 < source.length()
                            && !(source.charAt(index) == '*'
                            && source.charAt(index + 1) == '/')) index++;
                    index++;
                    continue;
                }
            }
            if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return index;
        }
        return -1;
    }
}
