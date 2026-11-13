package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;

/** Optional measurements only: never supplies rendering state or waits for the GPU. */
public final class ClientFrameDiagnostics {
    public static final boolean ENABLED = Boolean.getBoolean("create_nautilus.clientFrameDiagnostics");
    private static final int WINDOW_SIZE = 600;
    private static final long REPORT_NANOS = 5_000_000_000L;
    // No state, management beans, query objects, or GL capability checks on the disabled path.
    private static final State STATE = ENABLED ? new State() : null;

    public enum Stage {
        FRAME_END_BEFORE_PRESENT, LEVEL_RENDER, PRESENT, FPS_LIMIT_WAIT,
        PHOTON_FRONT, PHOTON_BLEND, APERTURE_MASK, FLOODED_WATER, RAW_DEPTH, VOXEL_ATLAS,
        GL_BUFFER_DATA, GL_MAP_BUFFER, GL_UNMAP_BUFFER, GL_TEXTURE_UPLOAD, RAW_DEPTH_PACK,
        FLOODED_GEOMETRY, CONTINUOUS_GEOMETRY, APERTURE_SEMANTICS, ATLAS_PREFIX_ENCODE, SCREEN_BOUNDS
    }

    /** The stage follows the linked Program, including Iris' cached dimension pipelines. */
    public interface PhotonProgram {
        Stage createNautilus$getFrameDiagnosticStage();
        void createNautilus$setFrameDiagnosticStage(Stage stage);
    }

    private ClientFrameDiagnostics() { }

    public static long startWall() { return ENABLED ? System.nanoTime() : 0L; }

    public static void recordWall(final Stage stage, final long started) {
        if (ENABLED && started != 0L) recordWallNanos(stage, System.nanoTime() - started);
    }

    public static void recordWallNanos(final Stage stage, final long elapsed) {
        if (!ENABLED || !STATE.frameActive || elapsed < 0L || !RenderSystem.isOnRenderThread()) return;
        final int index = stage.ordinal();
        STATE.wall[index].add(elapsed);
        STATE.wallCalls[index]++;
        STATE.wallNanos[index] += elapsed;
    }

    /** Timestamp elapsed span, which may include GPU idle; this is not an active-busy counter. */
    public static long beginGpu(final Stage stage) {
        if (!ENABLED || !STATE.frameActive || !RenderSystem.isOnRenderThread()) return 0L;
        return STATE.gpu.begin(stage.ordinal());
    }

    public static void endGpu(final long token) {
        if (ENABLED && token != 0L && RenderSystem.isOnRenderThread()) STATE.gpu.end(token);
    }

    public static void beginFrame() {
        if (!ENABLED || !RenderSystem.isOnRenderThread()) return;
        if (STATE.frameActive) STATE.incompleteFrames++;
        STATE.frameActive = false;
        STATE.gpu.beginFrame(++STATE.frame);
        STATE.frameActive = true;
        if (STATE.presentStarted != 0L) STATE.lastSwapReturn = 0L;
        STATE.presentStarted = 0L;
        STATE.waitStarted = 0L;
        STATE.photonStage = null;
        STATE.photonGpu = 0L;
        STATE.photonWall = 0L;
        STATE.frameStarted = System.nanoTime();
        STATE.cpuStarted = STATE.metrics.cpu();
        STATE.heapStarted = STATE.metrics.heap();
        STATE.frameGpu = beginGpu(Stage.FRAME_END_BEFORE_PRESENT);
    }

    /** Called immediately before the actual GLFW swap, after replayQueue has submitted its work. */
    public static void beginPresent() {
        if (!ENABLED || !STATE.frameActive || !RenderSystem.isOnRenderThread()) return;
        if (STATE.frameGpu != 0L) {
            endGpu(STATE.frameGpu);
            STATE.frameGpu = 0L;
        }
        // Wall span ends at the same boundary as the timestamp span, before swap/vsync waits.
        if (STATE.beforePresentRecorded != STATE.frame) {
            recordWall(Stage.FRAME_END_BEFORE_PRESENT, STATE.frameStarted);
            STATE.beforePresentRecorded = STATE.frame;
        }
        STATE.presentStarted = System.nanoTime();
    }

    public static void endPresent() {
        if (!ENABLED || !STATE.frameActive || !RenderSystem.isOnRenderThread()) return;
        if (STATE.presentStarted == 0L) return;
        final long now = System.nanoTime();
        recordWallNanos(Stage.PRESENT, now - STATE.presentStarted);
        if (STATE.lastSwapReturn != 0L) STATE.swapReturnIntervals.add(now - STATE.lastSwapReturn);
        STATE.lastSwapReturn = now;
        STATE.presentStarted = 0L;
    }

    /** Brackets the actual glfwWaitEventsTimeout call, separate from swap and FPS-loop overhead. */
    public static void beginFrameLimitWait() {
        if (ENABLED && STATE.frameActive && RenderSystem.isOnRenderThread()) STATE.waitStarted = System.nanoTime();
    }

    public static void endFrameLimitWait() {
        if (!ENABLED || !STATE.frameActive || !RenderSystem.isOnRenderThread()) return;
        recordWall(Stage.FPS_LIMIT_WAIT, STATE.waitStarted);
        STATE.waitStarted = 0L;
    }

    public static void endFrame() {
        if (!ENABLED || !STATE.frameActive || !RenderSystem.isOnRenderThread()) return;
        final long elapsed = System.nanoTime() - STATE.frameStarted;
        final long cpu = STATE.metrics.cpu();
        final long heap = STATE.metrics.heap();
        STATE.frameWall.add(elapsed);
        STATE.intervalFrames++;
        if (cpu >= STATE.cpuStarted && STATE.cpuStarted >= 0L) STATE.frameCpu.add(cpu - STATE.cpuStarted);
        if (heap >= STATE.heapStarted && STATE.heapStarted >= 0L) STATE.frameHeap.add(heap - STATE.heapStarted);
        // No present means this GPU frame is incomplete; do not label a later boundary as pre-present.
        STATE.frameGpu = 0L;
        STATE.frameActive = false;
        STATE.reportIfDue();
    }

    /** Resource reload, world unload/logout and Window.close all route deletion to the render thread. */
    public static void release() {
        if (!ENABLED) return;
        if (RenderSystem.isOnRenderThread()) STATE.release();
        else RenderSystem.recordRenderCall(STATE::release);
    }

    /** Only fingerprints of the actual preprocessed Photon stages are registered, not program names. */
    public static Stage photonStage(final String source) {
        if (!ENABLED || source == null) return null;
        if (source.contains("raymarch_water_fog(") && source.contains("raymarch_air_fog(")
                && source.contains("vec3 world_start_pos") && source.contains("vec3 world_end_pos")
                && source.contains("vec3 world_back_pos")) return Stage.PHOTON_FRONT;
        if (source.contains("vec3 back_position_world") && source.contains("bool is_translucent")
                && source.contains("mat2x3 analytic_fog = air_fog_analytic(")
                && source.contains("fragment_color = blend_layers_with_fog(")) return Stage.PHOTON_BLEND;
        return null;
    }

    public static void registerPhotonProgram(final Object program, final Stage stage) {
        if (ENABLED && program instanceof PhotonProgram linked) linked.createNautilus$setFrameDiagnosticStage(stage);
    }

    public static void usePhotonProgram(final Object program) {
        if (ENABLED && RenderSystem.isOnRenderThread()) STATE.photonStage =
                program instanceof PhotonProgram linked ? linked.createNautilus$getFrameDiagnosticStage() : null;
    }

    public static void unbindPhotonProgram() {
        if (ENABLED && RenderSystem.isOnRenderThread()) STATE.photonStage = null;
    }

    /** The composite mixin calls these around renderQuad itself, not Program.use or source builds. */
    public static void beginPhotonDraw() {
        if (!ENABLED || !STATE.frameActive || STATE.photonStage == null || !RenderSystem.isOnRenderThread()) return;
        STATE.photonWall = startWall();
        STATE.photonGpu = beginGpu(STATE.photonStage);
    }

    public static void endPhotonDraw() {
        if (!ENABLED || !RenderSystem.isOnRenderThread()) return;
        if (STATE.photonStage != null) recordWall(STATE.photonStage, STATE.photonWall);
        endGpu(STATE.photonGpu);
        STATE.photonWall = 0L;
        STATE.photonGpu = 0L;
    }

    private static final class State {
        final Stage[] stages = Stage.values();
        final LongWindow frameWall = new LongWindow(WINDOW_SIZE);
        final LongWindow frameCpu = new LongWindow(WINDOW_SIZE);
        final LongWindow frameHeap = new LongWindow(WINDOW_SIZE);
        final LongWindow swapReturnIntervals = new LongWindow(WINDOW_SIZE);
        final LongWindow[] wall = new LongWindow[stages.length];
        final LongWindow[] gpuSamples = new LongWindow[stages.length];
        final long[] wallCalls = new long[stages.length];
        final long[] wallNanos = new long[stages.length];
        final long[] gpuCalls = new long[stages.length];
        final long[] gpuNanos = new long[stages.length];
        final long[] gpuFirstOrigin = new long[stages.length];
        final long[] gpuLastOrigin = new long[stages.length];
        final ThreadMetrics metrics = new ThreadMetrics();
        final GpuTimestampTimer gpu = new GpuTimestampTimer(new GlBackend(), this::recordGpu);
        long frame;
        long frameStarted;
        long cpuStarted;
        long heapStarted;
        long frameGpu;
        long beforePresentRecorded;
        long presentStarted;
        long lastSwapReturn;
        long waitStarted;
        Stage photonStage;
        long photonWall;
        long photonGpu;
        boolean frameActive;
        long intervalFrames;
        long incompleteFrames;
        long firstReportFrame = 1L;
        long lastReport = System.nanoTime();

        State() {
            for (int i = 0; i < stages.length; i++) {
                wall[i] = new LongWindow(WINDOW_SIZE);
                gpuSamples[i] = new LongWindow(WINDOW_SIZE);
            }
        }

        void recordGpu(final int stage, final long elapsed, final long originFrame) {
            gpuSamples[stage].add(elapsed);
            if (gpuCalls[stage]++ == 0L) gpuFirstOrigin[stage] = originFrame;
            else gpuFirstOrigin[stage] = Math.min(gpuFirstOrigin[stage], originFrame);
            gpuLastOrigin[stage] = Math.max(gpuLastOrigin[stage], originFrame);
            gpuNanos[stage] += elapsed;
        }

        void reportIfDue() {
            final long now = System.nanoTime();
            if (now - lastReport < REPORT_NANOS) return;
            lastReport = now;
            try {
                CreateNautilus.LOGGER.info("[client-frame] frame={} intervalFirstFrame={} intervalCompletedFrames={} "
                                + "intervalIncompleteFrames={} rollingWindowMax={} frameWallNs={} "
                                + "renderThreadCpuNs={} renderThreadHeapAllocationBytes={} swapReturnIntervalNs={} "
                                + "gpu={} dropped={} errors={} "
                                + "GPU=timestamp elapsed spans including possible idle, end-before-present; "
                                + "wall/GPU are separate; nested stages overlap and must not be added; "
                                + "stage percentiles=per invocation; frame percentiles=per completed frame; "
                                + "PRESENT=actual glfwSwapBuffers, FPS_LIMIT_WAIT=actual glfwWaitEventsTimeout; "
                                + "swapReturnIntervalNs=interval between successful swap returns, not OS display time; "
                                + "GPU results delayed >=3 frames: resolved totals carry origin frame ranges and "
                                + "must not be divided by current interval frames",
                        frame, firstReportFrame, intervalFrames, incompleteFrames, WINDOW_SIZE,
                        frameWall.summary(), frameCpu.summary(), frameHeap.summary(), swapReturnIntervals.summary(),
                        gpu.status(), gpu.dropped(), gpu.errors());
                for (int i = 0; i < stages.length; i++) {
                    if (wall[i].size == 0 && gpuSamples[i].size == 0) continue;
                    CreateNautilus.LOGGER.info("[client-frame] stage={} wallNsPerInvocation={} gpuElapsedNsPerInvocation={} "
                                    + "intervalWallCalls={} intervalWallTotalNs={} intervalGpuResolvedSamples={} "
                                    + "intervalGpuResolvedTotalNs={} gpuOriginFirstFrame={} gpuOriginLastFrame={}",
                            stages[i], wall[i].summary(), gpuSamples[i].summary(), wallCalls[i], wallNanos[i],
                            gpuCalls[i], gpuNanos[i], gpuCalls[i] == 0L ? "unknown" : gpuFirstOrigin[i],
                            gpuCalls[i] == 0L ? "unknown" : gpuLastOrigin[i]);
                }
            } catch (final RuntimeException | LinkageError ignored) {
                // Diagnostic reporting must not fail a render frame.
            } finally {
                clearInterval();
            }
        }

        void clearInterval() {
            Arrays.fill(wallCalls, 0L);
            Arrays.fill(wallNanos, 0L);
            Arrays.fill(gpuCalls, 0L);
            Arrays.fill(gpuNanos, 0L);
            Arrays.fill(gpuFirstOrigin, 0L);
            Arrays.fill(gpuLastOrigin, 0L);
            intervalFrames = 0L;
            incompleteFrames = 0L;
            firstReportFrame = frame + 1L;
        }

        void release() {
            gpu.release();
            frameActive = false;
            frameGpu = 0L;
            presentStarted = 0L;
            lastSwapReturn = 0L;
            waitStarted = 0L;
            photonStage = null;
            photonGpu = 0L;
            photonWall = 0L;
            frameWall.clear();
            frameCpu.clear();
            frameHeap.clear();
            swapReturnIntervals.clear();
            clearInterval();
            for (int i = 0; i < stages.length; i++) {
                wall[i].clear();
                gpuSamples[i].clear();
            }
            lastReport = System.nanoTime();
        }
    }

    static final class LongWindow {
        private final long[] samples;
        private int cursor;
        private int size;

        LongWindow(final int capacity) { samples = new long[capacity]; }

        void add(final long value) {
            if (value < 0L) return;
            samples[cursor] = value;
            cursor = (cursor + 1) % samples.length;
            size = Math.min(size + 1, samples.length);
        }

        long percentile(final int percent) {
            if (size == 0) return -1L;
            final long[] sorted = sorted();
            return sorted[Math.max(0, (int) Math.ceil(percent * size / 100.0) - 1)];
        }

        String summary() {
            if (size == 0) return "unknown";
            final long[] sorted = sorted();
            double sum = 0.0;
            for (final long sample : sorted) sum += sample;
            return "{samples:" + size + ",mean:" + (long) (sum / size)
                    + ",p50:" + sorted[(int) Math.ceil(size * 0.50) - 1]
                    + ",p95:" + sorted[(int) Math.ceil(size * 0.95) - 1]
                    + ",p99:" + sorted[(int) Math.ceil(size * 0.99) - 1] + '}';
        }

        private long[] sorted() {
            final long[] copy = Arrays.copyOf(samples, size);
            Arrays.sort(copy);
            return copy;
        }

        void clear() { cursor = 0; size = 0; }
    }

    /** Each unsupported/denied MXBean counter stays unknown instead of being reported as zero. */
    private static final class ThreadMetrics {
        private ThreadMXBean cpu;
        private com.sun.management.ThreadMXBean heap;

        ThreadMetrics() {
            try {
                final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
                if (bean.isCurrentThreadCpuTimeSupported()) {
                    if (!bean.isThreadCpuTimeEnabled()) bean.setThreadCpuTimeEnabled(true);
                    cpu = bean;
                }
                if (bean instanceof com.sun.management.ThreadMXBean allocation
                        && allocation.isThreadAllocatedMemorySupported()) {
                    if (!allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
                    heap = allocation;
                }
            } catch (final RuntimeException | LinkageError ignored) { }
        }

        long cpu() {
            if (cpu == null) return -1L;
            try { return cpu.getCurrentThreadCpuTime(); }
            catch (final RuntimeException | LinkageError ignored) { cpu = null; return -1L; }
        }

        long heap() {
            if (heap == null) return -1L;
            try { return heap.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
            catch (final RuntimeException | LinkageError ignored) { heap = null; return -1L; }
        }
    }

    private static final class GlBackend implements GpuTimestampTimer.Backend {
        public boolean supported() {
            final var capabilities = GL.getCapabilities();
            return (capabilities.OpenGL33 || capabilities.GL_ARB_timer_query)
                    && GL15C.glGetQueryi(GL33C.GL_TIMESTAMP, GL15C.GL_QUERY_COUNTER_BITS) > 0;
        }

        public int createQuery() { return GL15C.glGenQueries(); }
        public void timestamp(final int query) { GL33C.glQueryCounter(query, GL33C.GL_TIMESTAMP); }
        public boolean available(final int query) {
            return GL15C.glGetQueryObjecti(query, GL15C.GL_QUERY_RESULT_AVAILABLE) != 0;
        }
        public long result(final int query) { return GL33C.glGetQueryObjectui64(query, GL15C.GL_QUERY_RESULT); }
        public void deleteQuery(final int query) { GL15C.glDeleteQueries(query); }
    }
}
