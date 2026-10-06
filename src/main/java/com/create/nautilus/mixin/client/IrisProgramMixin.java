package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.ApertureMediumMaskRenderer;
import com.create.nautilus.client.render.IrisCompositeShaderSource;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.option.MergedStringOption;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

/** Binds the immutable per-frame Photon entry depth after Iris binds each program. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.Program", remap = false)
public abstract class IrisProgramMixin {
    @Unique
    private static boolean createNautilus$noUnitLogged;
    @Unique
    private static int createNautilus$loggedBindingGeneration;
    @Unique
    private static int createNautilus$loggedBlissFogSampleGeneration;
    @Unique
    private static createNautilus$CompositeBinding createNautilus$activeBinding;
    @Unique
    private int createNautilus$adapterGeneration;
    @Unique
    private boolean createNautilus$linkedAdapterConfirmed;
    @Unique
    private boolean createNautilus$uniformsResolved;
    @Unique
    private boolean createNautilus$hasBoundaryUniforms;
    @Unique
    private int createNautilus$program;
    @Unique
    private int createNautilus$entrySampler = -1;
    @Unique
    private int createNautilus$directionSampler = -1;
    @Unique
    private int createNautilus$atlasSampler = -1;
    @Unique
    private int createNautilus$atlasSizeUniform = -1;
    @Unique
    private int createNautilus$atlasCarrierCountUniform = -1;
    @Unique
    private int createNautilus$readyUniform = -1;
    @Unique
    private int createNautilus$entryGenerationUniform = -1;
    @Unique
    private int createNautilus$sizeUniform = -1;
    @Unique
    private int createNautilus$boundsUniform = -1;
    @Unique
    private int createNautilus$depthReversedUniform = -1;
    @Unique
    private int createNautilus$clearReversedUniform = -1;
    @Unique
    private int createNautilus$cameraWaterUniform = -1;
    @Unique
    private int createNautilus$adapterGenerationUniform = -1;
    @Unique
    private int createNautilus$expectedAdapterGenerationUniform = -1;
    @Unique
    private int[] createNautilus$textureUnits;

    /** Every cached dimension pipeline keeps its generation without changing the GLSL source. */
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void createNautilus$captureAdapterGeneration(final CallbackInfo callback) {
        createNautilus$adapterGeneration =
                IrisCompositeShaderSource.currentBuildGeneration();
    }

    /**
     * Restore the previous pass before Iris configures the next program's samplers.  Doing this at
     * HEAD is important: restoring after {@code Program.use()} would overwrite bindings that Iris
     * has just installed for the new program.
     */
    @Inject(method = "use", at = @At("HEAD"), require = 0)
    private void createNautilus$restoreBeforeProgramUse(final CallbackInfo callback) {
        createNautilus$restoreCompositeBinding();
    }

    @Inject(method = "unbind", at = @At("HEAD"), require = 0)
    private static void createNautilus$restoreOnProgramUnbind(final CallbackInfo callback) {
        createNautilus$restoreCompositeBinding();
    }

    @Inject(method = "use", at = @At("RETURN"), require = 0)
    private void createNautilus$bindWaterBoundaries(final CallbackInfo callback) {
        if (!createNautilus$uniformsResolved) {
            final int currentProgram = GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            if (currentProgram <= 0) return;
            createNautilus$program = currentProgram;
            createNautilus$entrySampler = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.ENTRY_DEPTH_SAMPLER);
            createNautilus$directionSampler = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.ENTRY_DIRECTION_SAMPLER);
            createNautilus$atlasSampler = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.ENTRY_ATLAS_SAMPLER);
            createNautilus$atlasSizeUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.ENTRY_ATLAS_SIZE_UNIFORM);
            createNautilus$atlasCarrierCountUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.ENTRY_ATLAS_CARRIER_COUNT_UNIFORM);
            createNautilus$readyUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.READY_UNIFORM);
            createNautilus$entryGenerationUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.ENTRY_GENERATION_UNIFORM);
            createNautilus$sizeUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.ENTRY_SIZE_UNIFORM);
            createNautilus$boundsUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.ENTRY_BOUNDS_UNIFORM);
            createNautilus$depthReversedUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.DEPTH_REVERSED_UNIFORM);
            createNautilus$clearReversedUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.CLEAR_REVERSED_UNIFORM);
            createNautilus$cameraWaterUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.CAMERA_WATER_UNIFORM);
            createNautilus$adapterGenerationUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.ADAPTER_GENERATION_UNIFORM);
            createNautilus$expectedAdapterGenerationUniform = GL20C.glGetUniformLocation(currentProgram,
                    IrisCompositeShaderSource.EXPECTED_ADAPTER_GENERATION_UNIFORM);
            createNautilus$hasBoundaryUniforms = createNautilus$entrySampler >= 0
                    || createNautilus$directionSampler >= 0
                    || createNautilus$atlasSampler >= 0
                    || createNautilus$atlasSizeUniform >= 0
                    || createNautilus$atlasCarrierCountUniform >= 0
                    || createNautilus$readyUniform >= 0
                    || createNautilus$entryGenerationUniform >= 0
                    || createNautilus$sizeUniform >= 0
                    || createNautilus$boundsUniform >= 0
                    || createNautilus$depthReversedUniform >= 0
                    || createNautilus$clearReversedUniform >= 0
                    || createNautilus$cameraWaterUniform >= 0
                    || createNautilus$adapterGenerationUniform >= 0
                    || createNautilus$expectedAdapterGenerationUniform >= 0;
            createNautilus$uniformsResolved = true;
        }
        if (!createNautilus$hasBoundaryUniforms) {
            return;
        }
        final int program = createNautilus$program;
        final int entrySampler = createNautilus$entrySampler;
        final int directionSampler = createNautilus$directionSampler;
        final int atlasSampler = createNautilus$atlasSampler;
        final int atlasSizeUniform = createNautilus$atlasSizeUniform;
        final int atlasCarrierCountUniform = createNautilus$atlasCarrierCountUniform;
        final int ready = createNautilus$readyUniform;
        final int entryGenerationUniform = createNautilus$entryGenerationUniform;
        final int size = createNautilus$sizeUniform;
        final int boundsUniform = createNautilus$boundsUniform;
        final int depthReversed = createNautilus$depthReversedUniform;
        final int clearReversed = createNautilus$clearReversedUniform;
        final int cameraWater = createNautilus$cameraWaterUniform;
        final int adapterGenerationUniform = createNautilus$adapterGenerationUniform;
        final int expectedAdapterGenerationUniform = createNautilus$expectedAdapterGenerationUniform;

        // Ready is published last, so a partial program or changing frame remains fail-open.
        if (ready >= 0) GL20C.glUniform1i(ready, 0);
        final int adapterGeneration = createNautilus$adapterGeneration;
        // Cached AST/driver output is shared, but this linked Program's ownership is not.
        if (expectedAdapterGenerationUniform >= 0) {
            GL20C.glUniform1i(expectedAdapterGenerationUniform, adapterGeneration);
        }
        if (entrySampler < 0 || directionSampler < 0
                || atlasSampler < 0 || atlasSizeUniform < 0
                || atlasCarrierCountUniform < 0
                || ready < 0 || entryGenerationUniform < 0 || size < 0
                || boundsUniform < 0 || depthReversed < 0 || clearReversed < 0
                || cameraWater < 0 || adapterGenerationUniform < 0
                || expectedAdapterGenerationUniform < 0
                || adapterGeneration <= 0
                || !IrisCompositeShaderSource.adapterReady(adapterGeneration)) {
            return;
        }

        // Pipeline construction only proves that Java saw all source anchors.  LINK_STATUS, the
        // complete uniform set above and CURRENT_PROGRAM together prove that this concrete GL
        // program was actually linked and used before it can enable the boundary resources.
        if (!createNautilus$linkedAdapterConfirmed) {
            createNautilus$linkedAdapterConfirmed = GL20C.glIsProgram(program)
                    && GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_TRUE
                    && GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) == program;
            if (createNautilus$linkedAdapterConfirmed) {
                IrisCompositeShaderSource.confirmLinkedConsumer(adapterGeneration);
            }
        }
        if (!createNautilus$linkedAdapterConfirmed) return;
        // The first real use only completes the source->link handshake. The renderer did no work
        // earlier in this frame; it may publish a coherent D24S8 snapshot from the next frame.
        if (!ApertureMediumMaskRenderer.ready()) return;

        final int generation = ApertureMediumMaskRenderer.generation();
        final int boundaryWidth = ApertureMediumMaskRenderer.width();
        final int boundaryHeight = ApertureMediumMaskRenderer.height();
        final boolean reversed = ApertureMediumMaskRenderer.depthRangeReversed();
        final boolean clearIsReversed = ApertureMediumMaskRenderer.clearDepthReversed();
        final boolean cameraStartsInWater =
                ApertureMediumMaskRenderer.cameraStartsInWater();
        final float[] entryBounds = ApertureMediumMaskRenderer.bounds();
        final int texture = ApertureMediumMaskRenderer.textureId();
        final int directionTexture = ApertureMediumMaskRenderer.directionTextureId();
        final int atlasTexture = ApertureMediumMaskRenderer.atlasTextureId();
        final int atlasWidth = ApertureMediumMaskRenderer.atlasWidth();
        final int atlasHeight = ApertureMediumMaskRenderer.atlasHeight();
        final int atlasCarrierCount = ApertureMediumMaskRenderer.atlasCarrierCount();
        final int rendererAdapterGeneration = ApertureMediumMaskRenderer.adapterGeneration();
        if (generation <= 0 || boundaryWidth <= 0 || boundaryHeight <= 0
                || atlasWidth <= 0 || atlasHeight <= 0 || atlasCarrierCount <= 0
                || rendererAdapterGeneration != adapterGeneration
                || !createNautilus$validBounds(entryBounds)
                || texture <= 0 || directionTexture <= 0 || atlasTexture <= 0) return;
        final float minU = entryBounds[0];
        final float minV = entryBounds[1];
        final float maxU = entryBounds[2];
        final float maxV = entryBounds[3];
        final int[] textures = {texture, directionTexture, atlasTexture};
        if (!createNautilus$validTextureSet(textures)) return;

        int[] units = createNautilus$textureUnits;
        if (units == null) {
            units = createNautilus$findUnreferencedTextureUnits(
                    program, textures.length, entrySampler, directionSampler, atlasSampler);
            if (units != null) createNautilus$textureUnits = units;
        }
        if (units == null) {
            if (!createNautilus$noUnitLogged) {
                createNautilus$noUnitLogged = true;
                CreateNautilus.LOGGER.warn(
                        "[water-entry] no unused fragment texture unit; "
                                + "adapter stays disabled");
            }
            return;
        }

        final createNautilus$CompositeBinding binding =
                createNautilus$bindTextureSet(units, textures);
        if (binding == null) return;
        final float[] currentBounds = ApertureMediumMaskRenderer.bounds();
        if (GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) != program
                || !IrisCompositeShaderSource.adapterReady(adapterGeneration)
                || !ApertureMediumMaskRenderer.ready()
                || ApertureMediumMaskRenderer.generation() != generation
                || ApertureMediumMaskRenderer.width() != boundaryWidth
                || ApertureMediumMaskRenderer.height() != boundaryHeight
                || ApertureMediumMaskRenderer.depthRangeReversed() != reversed
                || ApertureMediumMaskRenderer.clearDepthReversed() != clearIsReversed
                || ApertureMediumMaskRenderer.cameraStartsInWater()
                != cameraStartsInWater
                || ApertureMediumMaskRenderer.textureId() != texture
                || ApertureMediumMaskRenderer.directionTextureId() != directionTexture
                || ApertureMediumMaskRenderer.atlasTextureId() != atlasTexture
                || ApertureMediumMaskRenderer.atlasWidth() != atlasWidth
                || ApertureMediumMaskRenderer.atlasHeight() != atlasHeight
                || ApertureMediumMaskRenderer.atlasCarrierCount() != atlasCarrierCount
                || ApertureMediumMaskRenderer.adapterGeneration()
                != rendererAdapterGeneration
                || !createNautilus$sameBounds(entryBounds, currentBounds)) {
            createNautilus$restoreBindingSnapshot(binding);
            return;
        }

        createNautilus$activeBinding = binding;
        GL20C.glUniform1i(entrySampler, units[0]);
        GL20C.glUniform1i(directionSampler, units[1]);
        GL20C.glUniform1i(atlasSampler, units[2]);
        GL20C.glUniform2f(atlasSizeUniform, atlasWidth, atlasHeight);
        GL20C.glUniform1i(atlasCarrierCountUniform, atlasCarrierCount);
        GL20C.glUniform1i(entryGenerationUniform, generation);
        GL20C.glUniform2f(size, boundaryWidth, boundaryHeight);
        GL20C.glUniform4f(boundsUniform, minU, minV, maxU, maxV);
        GL20C.glUniform1i(depthReversed, reversed ? 1 : 0);
        GL20C.glUniform1i(clearReversed, clearIsReversed ? 1 : 0);
        GL20C.glUniform1i(cameraWater, cameraStartsInWater ? 1 : 0);
        GL20C.glUniform1i(adapterGenerationUniform, rendererAdapterGeneration);
        GL20C.glUniform1i(ready, 1);
        if (createNautilus$loggedBindingGeneration != adapterGeneration) {
            createNautilus$loggedBindingGeneration = adapterGeneration;
            CreateNautilus.LOGGER.info(
                    "[water-entry] bound render generation {} / adapter generation {} "
                            + "at {}x{} to texture units [{}, {}, {}]",
                    generation, adapterGeneration, boundaryWidth, boundaryHeight,
                    units[0], units[1], units[2]);
        }
        createNautilus$logBlissFogSamples(
                program, adapterGeneration, texture, boundaryWidth, boundaryHeight);
    }

    /**
     * One generation-scoped readback distinguishing Bliss' native fog producer from its later
     * background consumer. This runs only after the immutable boundary snapshot has been bound;
     * every missing option, sampler, texture or GL capability leaves rendering untouched.
     */
    @Unique
    private static void createNautilus$logBlissFogSamples(
            final int program, final int adapterGeneration,
            final int entryTexture, final int entryWidth, final int entryHeight) {
        try {
            if (createNautilus$loggedBlissFogSampleGeneration == adapterGeneration
                    || !GL.getCapabilities().OpenGL45) return;
            createNautilus$logBlissFogSamplesUnsafe(
                    program, adapterGeneration, entryTexture, entryWidth, entryHeight);
        } catch (final RuntimeException | LinkageError unavailable) {
            // Optional diagnostic only. A failed synchronous readback never changes Ready or GL data.
        }
    }

    @Unique
    private static void createNautilus$logBlissFogSamplesUnsafe(
            final int program, final int adapterGeneration,
            final int entryTexture, final int entryWidth, final int entryHeight) {
        final float volumetricScale = createNautilus$blissVolumetricScale();
        if (!Float.isFinite(volumetricScale)) return;
        final int frameCounterLocation = GL20C.glGetUniformLocation(program, "frameCounter");
        final int colortex13Location = GL20C.glGetUniformLocation(program, "colortex13");
        if (frameCounterLocation < 0 || colortex13Location < 0) return;

        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int previousPackBuffer = GL11C.glGetInteger(
                GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        final int previousPackAlignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
        final int previousPackRowLength = GL11C.glGetInteger(GL12C.GL_PACK_ROW_LENGTH);
        final int previousPackSkipPixels = GL11C.glGetInteger(GL12C.GL_PACK_SKIP_PIXELS);
        final int previousPackSkipRows = GL11C.glGetInteger(GL12C.GL_PACK_SKIP_ROWS);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 4);
            GL11C.glPixelStorei(GL12C.GL_PACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_ROWS, 0);
            final int depth0Texture = createNautilus$samplerTexture2D(
                    program, "depthtex0", stack);
            final int depth1Texture = createNautilus$samplerTexture2D(
                    program, "depthtex1", stack);
            final int maskTexture = createNautilus$samplerTexture2D(
                    program, "colortex7", stack);
            final int fogTexture = createNautilus$samplerTexture2D(
                    program, "colortex13", stack);
            if (!createNautilus$validReadTexture(entryTexture)
                    || !createNautilus$validReadTexture(depth0Texture)
                    || !createNautilus$validReadTexture(depth1Texture)
                    || !createNautilus$validReadTexture(maskTexture)
                    || !createNautilus$validReadTexture(fogTexture)) return;

            final IntBuffer frameValue = stack.mallocInt(1);
            GL20C.glGetUniformiv(program, frameCounterLocation, frameValue);
            final int frameCounter = frameValue.get(0);
            final IrisCompositeShaderSource.SceneDepthTransform transform =
                    IrisCompositeShaderSource.sceneDepthTransform(
                            adapterGeneration, frameCounter, entryWidth, entryHeight);
            if (transform == null) return;

            final int depth0Width = createNautilus$textureDimension(
                    depth0Texture, GL11C.GL_TEXTURE_WIDTH);
            final int depth0Height = createNautilus$textureDimension(
                    depth0Texture, GL11C.GL_TEXTURE_HEIGHT);
            final int depth1Width = createNautilus$textureDimension(
                    depth1Texture, GL11C.GL_TEXTURE_WIDTH);
            final int depth1Height = createNautilus$textureDimension(
                    depth1Texture, GL11C.GL_TEXTURE_HEIGHT);
            final int maskWidth = createNautilus$textureDimension(
                    maskTexture, GL11C.GL_TEXTURE_WIDTH);
            final int maskHeight = createNautilus$textureDimension(
                    maskTexture, GL11C.GL_TEXTURE_HEIGHT);
            final int fogWidth = createNautilus$textureDimension(
                    fogTexture, GL11C.GL_TEXTURE_WIDTH);
            final int fogHeight = createNautilus$textureDimension(
                    fogTexture, GL11C.GL_TEXTURE_HEIGHT);
            if (entryWidth <= 0 || entryHeight <= 0
                    || depth0Width <= 0 || depth0Height <= 0
                    || depth1Width <= 0 || depth1Height <= 0
                    || maskWidth <= 0 || maskHeight <= 0
                    || fogWidth <= 0 || fogHeight <= 0) return;

            final IntBuffer entryPixel = stack.mallocInt(1);
            final FloatBuffer scalar = stack.mallocFloat(1);
            final FloatBuffer rgba = stack.mallocFloat(4);
            final float[] testU = {0.35F, 0.50F, 0.65F};
            final float testV = 0.35F;
            final float[] entryDepths = new float[testU.length];
            final int[] entryStencils = new int[testU.length];
            final float[] depth0Samples = new float[testU.length];
            final float[] depth1Samples = new float[testU.length];
            final float[] maskAlphas = new float[testU.length];
            final float[] fogSamples = new float[testU.length * 4];
            for (int sample = 0; sample < testU.length; sample++) {
                final float pixelU = testU[sample];
                final float sceneU = pixelU * transform.scale() + transform.offsetU();
                final float sceneV = testV * transform.scale() + transform.offsetV();
                if (!createNautilus$validUv(sceneU, sceneV)) return;

                final int entryX = createNautilus$texel(pixelU, entryWidth);
                final int entryY = createNautilus$texel(testV, entryHeight);
                entryPixel.clear();
                GL45C.glGetTextureSubImage(entryTexture, 0,
                        entryX, entryY, 0, 1, 1, 1,
                        GL30C.GL_DEPTH_STENCIL, GL30C.GL_UNSIGNED_INT_24_8,
                        entryPixel);
                final int packedEntry = entryPixel.get(0);
                entryDepths[sample] = (packedEntry >>> 8) / 16_777_215.0F;
                entryStencils[sample] = packedEntry & 0xFF;

                depth0Samples[sample] = createNautilus$readDepth(
                        depth0Texture, sceneU, sceneV, depth0Width, depth0Height, scalar);
                depth1Samples[sample] = createNautilus$readDepth(
                        depth1Texture, sceneU, sceneV, depth1Width, depth1Height, scalar);
                maskAlphas[sample] = createNautilus$readAlpha(
                        maskTexture, sceneU, sceneV, maskWidth, maskHeight, rgba);
                final float fogU = sceneU * volumetricScale;
                final float fogV = sceneV * volumetricScale;
                if (!createNautilus$validUv(fogU, fogV)) return;
                createNautilus$readRgba(
                        fogTexture, fogU, fogV, fogWidth, fogHeight, rgba);
                for (int component = 0; component < 4; component++) {
                    fogSamples[sample * 4 + component] = rgba.get(component);
                }
            }
            createNautilus$loggedBlissFogSampleGeneration = adapterGeneration;
            for (int sample = 0; sample < testU.length; sample++) {
                CreateNautilus.LOGGER.info(
                        "[bliss-fog-sample] gen={} program={} pixelUv=[{},{}] "
                                + "entryDepth={} stencil={} d0={} d1={} maskAlpha={} "
                                + "fogRgba=[{},{},{},{}]",
                        adapterGeneration, program, testU[sample], testV,
                        entryDepths[sample], entryStencils[sample],
                        depth0Samples[sample], depth1Samples[sample], maskAlphas[sample],
                        fogSamples[sample * 4], fogSamples[sample * 4 + 1],
                        fogSamples[sample * 4 + 2], fogSamples[sample * 4 + 3]);
            }
        } finally {
            try {
                GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, previousPackAlignment);
                GL11C.glPixelStorei(GL12C.GL_PACK_ROW_LENGTH, previousPackRowLength);
                GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_PIXELS, previousPackSkipPixels);
                GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_ROWS, previousPackSkipRows);
            } finally {
                try {
                    GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
                } finally {
                    GL13C.glActiveTexture(previousActive);
                }
            }
        }
    }

    @Unique
    private static float createNautilus$blissVolumetricScale() {
        try {
            final String packName = Iris.getCurrentPackName();
            if (packName == null
                    || !packName.toLowerCase(Locale.ROOT).contains("bliss")) return Float.NaN;
            final ShaderPack pack = Iris.getCurrentPack().orElse(null);
            if (pack == null) return Float.NaN;
            final ShaderPackOptions options = pack.getShaderPackOptions();
            final MergedStringOption merged = options.getOptionSet()
                    .getStringOptions().get("VL_RENDER_RESOLUTION");
            if (merged == null) return Float.NaN;
            final String encoded = options.getOptionValues()
                    .getStringValue("VL_RENDER_RESOLUTION")
                    .orElse(merged.getOption().getDefaultValue());
            if (encoded == null) return Float.NaN;
            final float value = Float.parseFloat(encoded.trim());
            return Float.isFinite(value) && value > 0.0F && value <= 1.0F
                    ? value : Float.NaN;
        } catch (final RuntimeException | LinkageError unavailable) {
            return Float.NaN;
        }
    }

    @Unique
    private static int createNautilus$samplerTexture2D(
            final int program, final String name, final MemoryStack stack) {
        final int location = GL20C.glGetUniformLocation(program, name);
        if (location < 0) return -1;
        final IntBuffer value = stack.mallocInt(1);
        GL20C.glGetUniformiv(program, location, value);
        final int unit = value.get(0);
        final int maximum = GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        if (unit < 0 || unit >= maximum) return -1;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        return GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
    }

    @Unique
    private static boolean createNautilus$validReadTexture(final int texture) {
        return texture > 0 && GL11C.glIsTexture(texture);
    }

    @Unique
    private static int createNautilus$textureDimension(final int texture, final int dimension) {
        return GL45C.glGetTextureLevelParameteri(texture, 0, dimension);
    }

    @Unique
    private static boolean createNautilus$validUv(final float u, final float v) {
        return Float.isFinite(u) && Float.isFinite(v)
                && u >= 0.0F && u < 1.0F && v >= 0.0F && v < 1.0F;
    }

    @Unique
    private static int createNautilus$texel(final float uv, final int size) {
        return Math.clamp((int) Math.floor(uv * size), 0, size - 1);
    }

    @Unique
    private static float createNautilus$readDepth(
            final int texture, final float u, final float v,
            final int width, final int height, final FloatBuffer target) {
        target.clear();
        GL45C.glGetTextureSubImage(texture, 0,
                createNautilus$texel(u, width), createNautilus$texel(v, height), 0,
                1, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, target);
        return target.get(0);
    }

    @Unique
    private static float createNautilus$readAlpha(
            final int texture, final float u, final float v,
            final int width, final int height, final FloatBuffer target) {
        createNautilus$readRgba(texture, u, v, width, height, target);
        return target.get(3);
    }

    @Unique
    private static void createNautilus$readRgba(
            final int texture, final float u, final float v,
            final int width, final int height, final FloatBuffer target) {
        target.clear();
        GL45C.glGetTextureSubImage(texture, 0,
                createNautilus$texel(u, width), createNautilus$texel(v, height), 0,
                1, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, target);
    }

    /**
     * Finds units that no sampler in this linked program references.  Texture objects bound to a
     * unit are global context state and therefore do not prove whether that unit is available to
     * this program.  Scanning every target binding on every use caused hundreds of synchronous
     * driver queries and could also steal a unit whose sampler happened to be bound to name zero.
     * Active uniforms and their assigned integer values are the authoritative relation; resolve it
     * once per Program instance and cache the result.
     */
    @Unique
    private static int[] createNautilus$findUnreferencedTextureUnits(
            final int program, final int count,
            final int entrySampler, final int directionSampler,
            final int atlasSampler) {
        final int fragmentUnits = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
        final int combinedUnits = GL11C.glGetInteger(
                GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        final int maximum = Math.min(fragmentUnits, combinedUnits);
        if (program <= 0 || count <= 0 || maximum < count) return null;

        final boolean[] referenced = new boolean[maximum];
        final int activeUniforms = GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_UNIFORMS);
        for (int uniformIndex = 0; uniformIndex < activeUniforms; uniformIndex++) {
            final int type = GL31C.glGetActiveUniformsi(
                    program, uniformIndex, GL31C.GL_UNIFORM_TYPE);
            if (!createNautilus$isSamplerType(type)) continue;
            final int size = Math.max(1, GL31C.glGetActiveUniformsi(
                    program, uniformIndex, GL31C.GL_UNIFORM_SIZE));
            final String activeName = GL31C.glGetActiveUniformName(program, uniformIndex);
            if (activeName == null || activeName.isEmpty()) return null;
            final String baseName = activeName.endsWith("[0]")
                    ? activeName.substring(0, activeName.length() - 3) : activeName;
            for (int element = 0; element < size; element++) {
                final String name = size == 1 ? activeName : baseName + '[' + element + ']';
                final int location = GL20C.glGetUniformLocation(program, name);
                if (location < 0) return null;
                if (location == entrySampler || location == directionSampler
                        || location == atlasSampler) continue;
                final int[] value = new int[1];
                GL20C.glGetUniformiv(program, location, value);
                if (value[0] < 0 || value[0] >= maximum) return null;
                referenced[value[0]] = true;
            }
        }

        final int[] result = new int[count];
        int found = 0;
        for (int unit = maximum - 1; unit >= 0; unit--) {
            if (referenced[unit]) continue;
            result[found++] = unit;
            if (found == count) return result;
        }
        return null;
    }

    @Unique
    private static boolean createNautilus$validBounds(final float[] bounds) {
        return bounds != null && bounds.length == 4
                && Float.isFinite(bounds[0]) && Float.isFinite(bounds[1])
                && Float.isFinite(bounds[2]) && Float.isFinite(bounds[3])
                && bounds[0] >= 0.0F && bounds[1] >= 0.0F
                && bounds[2] <= 1.0F && bounds[3] <= 1.0F
                && bounds[2] > bounds[0] && bounds[3] > bounds[1];
    }

    @Unique
    private static boolean createNautilus$sameBounds(final float[] first,
                                                      final float[] second) {
        if (!createNautilus$validBounds(first)
                || !createNautilus$validBounds(second)) return false;
        for (int index = 0; index < 4; index++) {
            if (Float.floatToIntBits(first[index])
                    != Float.floatToIntBits(second[index])) return false;
        }
        return true;
    }

    @Unique
    private static boolean createNautilus$validTextureSet(final int[] textures) {
        if (textures.length != 3) return false;
        for (int index = 0; index < textures.length; index++) {
            if (textures[index] <= 0) return false;
            for (int previous = 0; previous < index; previous++) {
                if (textures[index] == textures[previous]) return false;
            }
        }
        return true;
    }

    @Unique
    private static createNautilus$CompositeBinding createNautilus$bindTextureSet(
            final int[] units, final int[] textures) {
        if (units.length != textures.length) return null;
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int[] previousTextures = new int[units.length];
        final int[] previousSamplers = new int[units.length];
        final boolean samplerObjects = GL.getCapabilities().OpenGL33;
        try {
            for (int index = 0; index < units.length; index++) {
                final int unit = units[index];
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                previousTextures[index] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                previousSamplers[index] = samplerObjects
                        ? GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, unit) : -1;
            }
            // Do not call clone() on an array from a mixin method. Mixin 0.8.7 attempts to
            // resolve the bytecode owner "[I" as a normal ClassInfo and crashes while applying
            // this mixin. An explicit copy has identical ownership semantics without emitting
            // INVOKEVIRTUAL [I.clone().
            final int[] bindingUnits = new int[units.length];
            for (int index = 0; index < units.length; index++) {
                bindingUnits[index] = units[index];
            }
            final createNautilus$CompositeBinding binding =
                    new createNautilus$CompositeBinding(
                            bindingUnits, previousTextures, previousSamplers);
            for (int index = 0; index < units.length; index++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + units[index]);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[index]);
                if (samplerObjects) GL33C.glBindSampler(units[index], 0);
            }
            return binding;
        } finally {
            GL13C.glActiveTexture(previousActive);
        }
    }

    @Unique
    private static boolean createNautilus$isSamplerType(final int type) {
        return switch (type) {
            case GL20C.GL_SAMPLER_1D, GL20C.GL_SAMPLER_2D, GL20C.GL_SAMPLER_3D,
                    GL20C.GL_SAMPLER_CUBE, GL20C.GL_SAMPLER_1D_SHADOW,
                    GL20C.GL_SAMPLER_2D_SHADOW,
                    GL30C.GL_SAMPLER_1D_ARRAY, GL30C.GL_SAMPLER_2D_ARRAY,
                    GL30C.GL_SAMPLER_1D_ARRAY_SHADOW, GL30C.GL_SAMPLER_2D_ARRAY_SHADOW,
                    GL30C.GL_SAMPLER_CUBE_SHADOW,
                    GL30C.GL_INT_SAMPLER_1D, GL30C.GL_INT_SAMPLER_2D,
                    GL30C.GL_INT_SAMPLER_3D, GL30C.GL_INT_SAMPLER_CUBE,
                    GL30C.GL_INT_SAMPLER_1D_ARRAY, GL30C.GL_INT_SAMPLER_2D_ARRAY,
                    GL30C.GL_UNSIGNED_INT_SAMPLER_1D, GL30C.GL_UNSIGNED_INT_SAMPLER_2D,
                    GL30C.GL_UNSIGNED_INT_SAMPLER_3D, GL30C.GL_UNSIGNED_INT_SAMPLER_CUBE,
                    GL30C.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY,
                    GL30C.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY,
                    GL31C.GL_SAMPLER_2D_RECT, GL31C.GL_SAMPLER_2D_RECT_SHADOW,
                    GL31C.GL_SAMPLER_BUFFER, GL31C.GL_INT_SAMPLER_2D_RECT,
                    GL31C.GL_INT_SAMPLER_BUFFER, GL31C.GL_UNSIGNED_INT_SAMPLER_2D_RECT,
                    GL31C.GL_UNSIGNED_INT_SAMPLER_BUFFER,
                    GL32C.GL_SAMPLER_2D_MULTISAMPLE,
                    GL32C.GL_INT_SAMPLER_2D_MULTISAMPLE,
                    GL32C.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE,
                    GL32C.GL_SAMPLER_2D_MULTISAMPLE_ARRAY,
                    GL32C.GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,
                    GL32C.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,
                    GL40C.GL_SAMPLER_CUBE_MAP_ARRAY,
                    GL40C.GL_SAMPLER_CUBE_MAP_ARRAY_SHADOW,
                    GL40C.GL_INT_SAMPLER_CUBE_MAP_ARRAY,
                    GL40C.GL_UNSIGNED_INT_SAMPLER_CUBE_MAP_ARRAY -> true;
            default -> false;
        };
    }

    @Unique
    private static void createNautilus$restoreCompositeBinding() {
        final createNautilus$CompositeBinding binding = createNautilus$activeBinding;
        createNautilus$activeBinding = null;
        if (binding == null) return;
        createNautilus$restoreBindingSnapshot(binding);
    }

    @Unique
    private static void createNautilus$restoreBindingSnapshot(
            final createNautilus$CompositeBinding binding) {
        final int currentActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int[] units = binding.units();
        final int[] textures = binding.previousTextures();
        final int[] samplers = binding.previousSamplers();
        for (int index = 0; index < units.length; index++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + units[index]);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[index]);
            if (samplers[index] >= 0) {
                GL33C.glBindSampler(units[index], samplers[index]);
            }
        }
        GL13C.glActiveTexture(currentActive);
    }

    @Unique
    private record createNautilus$CompositeBinding(int[] units,
                                                    int[] previousTextures,
                                                    int[] previousSamplers) {
    }
}
