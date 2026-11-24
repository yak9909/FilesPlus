package app.filesplus.extension.smb;

import app.filesplus.extension.DiagLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local SMB timings. Fast calls update the summary without writing log lines. */
public final class SmbDiagnostics {
    private static final int MAX_EVENTS = 200;
    private static final int SUMMARY_MINUTES = 5;
    private static final long SLOW_MS = 2_000;
    // Leave ample Binder space for the rest of the clipboard transaction.
    private static final int MAX_DUMP_CHARS = 150_000;
    private static final String DUMP_TRUNCATED = "\n[Clipboard size limit reached]\n";
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>(MAX_EVENTS);
    private static final Set<Call> IN_FLIGHT = new LinkedHashSet<>();
    private static final Map<String, Stats[]> SUMMARY = new LinkedHashMap<>();
    static final AtomicInteger OPEN_PROXIES = new AtomicInteger();
    static final AtomicInteger PROXY_THREADS = new AtomicInteger();

    private SmbDiagnostics() {}

    static long now() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    static String connectionTag(String id) {
        return Integer.toHexString(id.hashCode());
    }

    static String pathTag(SmbDocId id) {
        String path = id.path;
        int start = Math.max(path.lastIndexOf('/') + 1, path.length() - 40);
        String tail = path.substring(start).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return connectionTag(id.connectionId) + " " + (start == 0 ? "/" : "/…/") + tail;
    }

    static Call begin(String operation) {
        Call call = new Call(operation);
        synchronized (LOCK) {
            IN_FLIGHT.add(call);
        }
        return call;
    }

    static final class Call {
        private final String operation;
        private final long started = now();
        private final long wallStart = System.currentTimeMillis();
        private final long threadId = Thread.currentThread().getId();
        private Map<String, Long> phases;
        private String target = "";
        private String phase;
        private long phaseStart;
        private boolean finished;

        private Call(String operation) {
            this.operation = operation;
        }

        void target(String target) {
            synchronized (LOCK) {
                this.target = target;
            }
        }

        void phase(String next) {
            synchronized (LOCK) {
                long now = now();
                stopPhase(now);
                phase = next;
                phaseStart = now;
            }
        }

        private void stopPhase(long now) {
            if (phase == null) return;
            if (phases == null) phases = new LinkedHashMap<>();
            phases.merge(phase, now - phaseStart, Long::sum);
        }

        void finish(String result, Throwable error) {
            finish(result, error, 0, 0);
        }

        void finishRead(long offset, int read, Throwable error) {
            finish(null, error, offset, read);
        }

        private void finish(String result, Throwable error, long offset, int read) {
            String event = null;
            synchronized (LOCK) {
                if (finished) return;
                finished = true;
                long now = now();
                long elapsed = now - started;
                stopPhase(now);
                IN_FLIGHT.remove(this);
                boolean failed = error != null;
                boolean slow = elapsed > SLOW_MS;
                summarize(operation, now, elapsed, failed, slow);
                if (failed || slow) {
                    String detail = result == null ? "offset=" + offset + " read=" + read : result;
                    String outcome = failed ? "error=" + errorName(error) + " " + detail : detail;
                    event = describe(now, false) + " end=" + System.currentTimeMillis() + " " + outcome;
                    if (EVENTS.size() == MAX_EVENTS) EVENTS.removeFirst();
                    EVENTS.addLast(event);
                }
            }
            if (event != null) {
                try {
                    DiagLog.i("smb", event);
                } catch (Throwable ignored) {
                    // Retain the ring entry even when the log sink is unavailable.
                }
            }
        }

        private String describe(long now, boolean live) {
            StringBuilder line = new StringBuilder(operation).append(' ').append(target)
                    .append(" tid=").append(threadId).append(" start=").append(wallStart)
                    .append(live ? " age=" : " took=").append(now - started).append("ms");
            if (phases != null) line.append(" phases(ms)=").append(phases);
            if (live && phase != null) {
                line.append(" current=").append(phase).append(' ').append(now - phaseStart).append("ms");
            }
            return line.toString();
        }
    }

    private static String errorName(Throwable error) {
        String name = error.getClass().getSimpleName();
        for (int depth = 0; depth < 4; depth++) {
            Throwable cause = error.getCause();
            if (cause == null || cause == error) break;
            name += "/" + cause.getClass().getSimpleName();
            error = cause;
        }
        return name;
    }

    private static final class Stats {
        final long minute;
        long count;
        long errors;
        long slow;
        long max;

        Stats(long minute) {
            this.minute = minute;
        }
    }

    private static void summarize(String operation, long now, long elapsed, boolean failed, boolean slow) {
        long minute = Math.floorDiv(now, 60_000L);
        int slot = (int) Math.floorMod(minute, SUMMARY_MINUTES);
        Stats[] buckets = SUMMARY.computeIfAbsent(operation, ignored -> new Stats[SUMMARY_MINUTES]);
        Stats stats = buckets[slot];
        if (stats == null || stats.minute != minute) buckets[slot] = stats = new Stats(minute);
        stats.count++;
        if (failed) stats.errors++;
        if (slow) stats.slow++;
        stats.max = Math.max(stats.max, elapsed);
    }

    public static String liveReport() {
        StringBuilder report = new StringBuilder("Open proxy FDs: ").append(OPEN_PROXIES.get())
                .append("  |  Live proxy threads: ").append(PROXY_THREADS.get());
        List<String> entries = SmbSessionPool.diagnosticEntries();
        report.append("\nPool entries: ").append(entries.size());
        for (String entry : entries) report.append('\n').append(entry);
        synchronized (LOCK) {
            long now = now();
            report.append("\nIn flight: ").append(IN_FLIGHT.size());
            for (Call call : IN_FLIGHT) report.append('\n').append(call.describe(now, true));
            report.append("\nRecent completions (~5 min):");
            long minute = Math.floorDiv(now, 60_000L);
            SUMMARY.forEach((operation, buckets) -> {
                long count = 0;
                long errors = 0;
                long slow = 0;
                long max = 0;
                for (Stats stats : buckets) {
                    if (stats == null || minute - stats.minute >= SUMMARY_MINUTES) continue;
                    count += stats.count;
                    errors += stats.errors;
                    slow += stats.slow;
                    max = Math.max(max, stats.max);
                }
                if (count != 0) report.append('\n').append(operation).append(" count=").append(count)
                        .append(" errors=").append(errors).append(" slow=").append(slow)
                        .append(" max=").append(max).append("ms");
            });
        }
        return report.toString();
    }

    static List<String> recentEvents() {
        synchronized (LOCK) {
            return new ArrayList<>(EVENTS);
        }
    }

    public static String threadReport() {
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        List<Thread> threads = new ArrayList<>(traces.keySet());
        threads.sort(Comparator.comparing(Thread::getName).thenComparingLong(Thread::getId));
        StringBuilder report = new StringBuilder("SMB\n").append(liveReport())
                .append("\n\nRecent SMB events (start/end in epoch ms)\n");
        for (String event : recentEvents()) report.append(event).append('\n');
        report.append("\nApp threads: ").append(threads.size()).append('\n');
        int limit = MAX_DUMP_CHARS - DUMP_TRUNCATED.length();
        for (Thread thread : threads) {
            if (report.length() > limit) break;
            report.append('\n').append(thread.getName()).append(" tid=").append(thread.getId())
                    .append(' ').append(thread.getState()).append('\n');
            StackTraceElement[] trace = traces.get(thread);
            for (int i = 0; i < Math.min(trace.length, 25); i++) {
                report.append("  at ").append(trace[i]).append('\n');
            }
        }
        if (report.length() > limit) {
            if (Character.isHighSurrogate(report.charAt(limit - 1))) limit--;
            report.setLength(limit);
            report.append(DUMP_TRUNCATED);
        }
        return report.toString();
    }
}
