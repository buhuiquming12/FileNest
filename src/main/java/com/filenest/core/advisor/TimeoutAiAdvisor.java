package com.filenest.core.advisor;

import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A decorator that makes any {@link AiAdvisor} safe to call: it bounds the delegate with a
 * timeout and falls back to another advisor (normally {@link NoOpAiAdvisor}) on timeout or
 * error. This is what keeps a slow or broken AI from ever stalling or breaking the
 * organize flow — the rule-based plan is unaffected.
 */
public final class TimeoutAiAdvisor implements AiAdvisor {

    private final AiAdvisor delegate;
    private final AiAdvisor fallback;
    private final Duration timeout;
    private volatile RunStatus lastRun = RunStatus.notRun();

    /** Outcome of the latest delegate call so the UI can explain a remote fallback. */
    public record RunStatus(boolean attempted, boolean succeeded, String error) {
        static RunStatus notRun() { return new RunStatus(false, false, null); }
        static RunStatus success() { return new RunStatus(true, true, null); }
        static RunStatus failure(String error) { return new RunStatus(true, false, error); }
    }

    public TimeoutAiAdvisor(AiAdvisor delegate, AiAdvisor fallback, Duration timeout) {
        this.delegate = delegate;
        this.fallback = fallback;
        this.timeout = timeout;
    }

    @Override
    public List<FileAction> suggest(List<FileMeta> files, OrganizeContext context) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ai-advisor");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<List<FileAction>> future = exec.submit(() -> delegate.suggest(files, context));
            try {
                List<FileAction> result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                lastRun = RunStatus.success();
                return result;
            } catch (TimeoutException e) {
                future.cancel(true);
                String error = "Request timed out after " + timeout.toSeconds() + " seconds";
                lastRun = RunStatus.failure(error);
                System.err.println("[AI] " + delegate.name() + " timed out after " + timeout
                        + " -- falling back to " + fallback.name());
                return fallback.suggest(files, context);
            } catch (Exception e) {
                Throwable cause = e instanceof java.util.concurrent.ExecutionException
                        && e.getCause() != null ? e.getCause() : e;
                String error = cause.getMessage() == null
                        ? cause.getClass().getSimpleName() : cause.getMessage();
                lastRun = RunStatus.failure(error);
                System.err.println("[AI] " + delegate.name() + " failed (" + error
                        + ") -- falling back to " + fallback.name());
                return fallback.suggest(files, context);
            }
        } finally {
            exec.shutdownNow();
        }
    }

    public RunStatus lastRun() {
        return lastRun;
    }

    @Override
    public String name() {
        return delegate.name() + " (超时保护)";
    }

    @Override
    public boolean available() {
        return delegate.available();
    }
}
