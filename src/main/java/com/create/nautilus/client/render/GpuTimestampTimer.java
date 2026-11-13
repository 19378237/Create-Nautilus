package com.create.nautilus.client.render;

/** Bounded timestamp pairs; a missing result is skipped, never waited for. Render-thread only. */
final class GpuTimestampTimer {
    static final int MAX_PAIRS = 128;
    static final int RESULT_DELAY_FRAMES = 3;

    interface Backend {
        boolean supported();
        int createQuery();
        void timestamp(int query);
        boolean available(int query);
        long result(int query);
        void deleteQuery(int query);
    }

    interface Results {
        void sample(int stage, long elapsedNanos, long originFrame);
    }

    private final Backend backend;
    private final Results results;
    private final Pair[] pairs = new Pair[MAX_PAIRS];
    private long frame;
    private long serial = 1L;
    private int cursor;
    private boolean checked;
    private boolean supported;
    private boolean failed;
    private long dropped;
    private long errors;

    GpuTimestampTimer(final Backend backend, final Results results) {
        this.backend = backend;
        this.results = results;
        for (int i = 0; i < pairs.length; i++) pairs[i] = new Pair();
    }

    void beginFrame(final long frame) {
        this.frame = frame;
        if (failed) return;
        try {
            if (!checked) {
                supported = backend.supported();
                checked = true;
            }
            if (!supported) return;
            for (final Pair pair : pairs) {
                // A renderer that abandoned its scope cannot permanently occupy the pool.
                if (pair.token != 0L && !pair.ended) {
                    pair.token = 0L;
                    dropped++;
                }
                if (pair.token == 0L || frame - pair.endFrame < RESULT_DELAY_FRAMES) continue;
                // Both timestamps must be available before either QUERY_RESULT is requested.
                if (!backend.available(pair.start) || !backend.available(pair.end)) continue;
                final long elapsed = backend.result(pair.end) - backend.result(pair.start);
                if (elapsed >= 0L) results.sample(pair.stage, elapsed, pair.endFrame);
                else dropped++;
                pair.token = 0L;
            }
        } catch (final RuntimeException | LinkageError failure) {
            fail();
        }
    }

    long begin(final int stage) {
        if (failed || !checked || !supported) {
            dropped++;
            return 0L;
        }
        for (int count = 0; count < pairs.length; count++) {
            final int index = (cursor + count) % pairs.length;
            final Pair pair = pairs[index];
            if (pair.token != 0L) continue;
            try {
                if (pair.start == 0) pair.start = backend.createQuery();
                if (pair.end == 0) pair.end = backend.createQuery();
                if (pair.start == 0 || pair.end == 0) throw new IllegalStateException("No query ID");
                if (serial > (Long.MAX_VALUE >>> 8)) serial = 1L;
                pair.token = (serial++ << 8) | (index + 1L);
                pair.stage = stage;
                pair.ended = false;
                backend.timestamp(pair.start);
                cursor = (index + 1) % pairs.length;
                return pair.token;
            } catch (final RuntimeException | LinkageError failure) {
                fail();
                return 0L;
            }
        }
        dropped++;
        return 0L;
    }

    void end(final long token) {
        if (token == 0L || failed) return;
        final int index = (int) (token & 255L) - 1;
        if (index < 0 || index >= pairs.length) return;
        final Pair pair = pairs[index];
        if (pair.token != token || pair.ended) return;
        try {
            backend.timestamp(pair.end);
            pair.ended = true;
            pair.endFrame = frame;
        } catch (final RuntimeException | LinkageError failure) {
            fail();
        }
    }

    /** Delete names only; no finish, result read, or waiting is required to delete a query. */
    void release() {
        for (final Pair pair : pairs) {
            final int start = pair.start;
            final int end = pair.end;
            pair.start = 0;
            pair.end = 0;
            pair.token = 0L;
            pair.ended = false;
            delete(start);
            delete(end);
        }
    }

    private void delete(final int query) {
        if (query == 0) return;
        try {
            backend.deleteQuery(query);
        } catch (final RuntimeException | LinkageError failure) {
            failed = true;
            errors++;
        }
    }

    private void fail() {
        failed = true;
        errors++;
        release();
    }

    String status() { return failed ? "failed" : !checked ? "uninitialized" : supported ? "enabled" : "unsupported"; }
    long dropped() { return dropped; }
    long errors() { return errors; }

    private static final class Pair {
        int start;
        int end;
        int stage;
        long token;
        long endFrame;
        boolean ended;
    }
}
