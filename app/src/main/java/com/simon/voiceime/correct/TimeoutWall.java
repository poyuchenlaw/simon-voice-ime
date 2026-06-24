package com.simon.voiceime.correct;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Hard timeout wall around a possibly-blocking computation, with a guaranteed fallback.
 *
 * <p>PURE JAVA — no Android dependencies — so the budget-enforcement logic is unit-testable on the
 * JVM (see {@code tools/selftest}). This is the mechanism behind v6.9.2's GUARANTEE:
 * <b>once on-device APPEND has a non-empty preview/recognized text, SOME text is always committed
 * within ~2s — never a silent freeze.</b>
 *
 * <p>Why this exists (v6.9.2): on the physical Fold6, the on-device LLM punctuator
 * ({@link LlmPunctuator#nativeComplete}, llama.cpp) could HANG forever once its ~1GB model finished
 * downloading. {@code nativeComplete} has no internal timeout, so {@code OnDeviceCorrector.correct}
 * blocked forever and nothing was ever committed (status froze at "辨識中"). The LLM punctuator is
 * disabled in v6.9.2, but this wall is the belt-and-suspenders guarantee that is INDEPENDENT of any
 * model behaviour: even if some future step blocks, the user's already-seen preview text is
 * committed within the budget.
 *
 * <p>Semantics of {@link #runWithBudget}: the {@code task} runs on a dedicated single-thread worker.
 * If it finishes within {@code budgetMs} and returns a non-empty result, that result is returned.
 * Otherwise (timeout, exception, interruption, null/empty result) the {@code fallback} is returned.
 * On timeout the worker is cancelled-interrupt and ABANDONED — we never block waiting for it, so a
 * truly-hung native call cannot stall the caller. The executor is shut down (the worker thread, if
 * still running, dies with the JVM as a daemon thread).
 */
public final class TimeoutWall {

    private TimeoutWall() {}

    private static final ThreadFactory DAEMON_FACTORY = r -> {
        Thread t = new Thread(r, "TimeoutWall-worker");
        // Daemon so an abandoned (hung) worker can never keep the process alive.
        t.setDaemon(true);
        return t;
    };

    /**
     * Run {@code task} with a hard wall-clock budget; return {@code fallback} if it does not produce
     * a non-empty result in time. NEVER blocks beyond {@code budgetMs} (plus negligible scheduling
     * overhead), NEVER throws, NEVER returns null/empty.
     *
     * @param task     the work that may block (e.g. the on-device correction). May return
     *                 {@code null}/empty, which is treated as "produced nothing" → {@code fallback}.
     * @param fallback the guaranteed-good value to return on timeout/failure/empty (e.g. the preview
     *                 text the user already sees). MUST be non-null/non-empty for the guarantee to
     *                 hold; if it is itself null/empty the method returns it as-is (caller's choice).
     * @param budgetMs hard budget in milliseconds (e.g. 2000).
     * @return {@code task}'s non-empty result if produced within budget, else {@code fallback}.
     */
    public static String runWithBudget(Callable<String> task, String fallback, long budgetMs) {
        if (task == null) return fallback;
        ExecutorService worker = Executors.newSingleThreadExecutor(DAEMON_FACTORY);
        Future<String> future = worker.submit(task);
        try {
            String result = future.get(budgetMs, TimeUnit.MILLISECONDS);
            if (result != null && !result.isEmpty()) {
                return result;
            }
            return fallback; // task produced nothing usable
        } catch (TimeoutException te) {
            // The task is still running (possibly hung in a native call). Cancel-interrupt and
            // ABANDON it — do NOT block on it. Returning the fallback here is the guarantee.
            future.cancel(true);
            return fallback;
        } catch (Throwable t) {
            // ExecutionException (task threw) / InterruptedException / anything else → fallback.
            future.cancel(true);
            return fallback;
        } finally {
            // shutdownNow interrupts the worker if still running; we never awaitTermination, so this
            // call returns immediately and the (daemon) worker thread is left to die on its own.
            worker.shutdownNow();
        }
    }
}
