package com.filenest.core.advisor;

import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;

import java.util.Collections;
import java.util.List;

/**
 * Extension-point template for a real large-language-model advisor.
 *
 * <p>It is intentionally <b>not</b> part of the default wiring — {@link HeuristicAiAdvisor}
 * is, so the app works offline with zero configuration. This class exists to demonstrate
 * the open/closed payoff: dropping in a genuine model requires implementing only
 * {@link #callModel(String)} here; the UI, executor, merge and undo logic never change.
 *
 * <p>Configuration is read from the environment so no secrets live in code:
 * <ul>
 *   <li>{@code FILENEST_LLM_ENDPOINT} — model HTTP endpoint</li>
 *   <li>{@code FILENEST_LLM_API_KEY}  — API key</li>
 * </ul>
 * When unconfigured it reports {@link #available()} == false and returns no suggestions,
 * behaving exactly like {@link NoOpAiAdvisor} — a safe default.
 */
public final class LlmAiAdvisor implements AiAdvisor {

    private final String endpoint;
    private final String apiKey;

    public LlmAiAdvisor() {
        this(System.getenv("FILENEST_LLM_ENDPOINT"), System.getenv("FILENEST_LLM_API_KEY"));
    }

    public LlmAiAdvisor(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public boolean available() {
        return endpoint != null && !endpoint.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<FileAction> suggest(List<FileMeta> files, OrganizeContext context) {
        if (!available()) {
            return Collections.emptyList();
        }
        try {
            String prompt = buildPrompt(files, context);
            String response = callModel(prompt);
            return parseResponse(response, context);
        } catch (Exception e) {
            // Never let AI break the flow — the TimeoutAiAdvisor wrapper also guards this.
            System.err.println("[LLM] suggestion failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Builds the model prompt. It must instruct the model to return, for each file, a
     * proposed category/target plus a confidence and a short reason — never a decision.
     */
    private String buildPrompt(List<FileMeta> files, OrganizeContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是文件整理助手。仅给出建议，不做决定。整理方式：")
                .append(context.scheme().label()).append("。\n")
                .append("对下列文件，各输出：建议目标文件夹、置信度(0-1)、简短理由。\n");
        for (FileMeta f : files) {
            sb.append("- ").append(f.fileName())
                    .append(" (").append(f.size()).append(" bytes)\n");
        }
        return sb.toString();
    }

    /**
     * TODO: perform the HTTP call to the configured model (e.g. via
     * {@link java.net.http.HttpClient}) and return the raw response body. Left unimplemented
     * on purpose so this template never pretends to work; the default build uses the local
     * heuristic advisor instead.
     */
    private String callModel(String prompt) {
        throw new UnsupportedOperationException(
                "LlmAiAdvisor.callModel 未实现：请在此接入真实模型 HTTP 调用（默认使用本地启发式 AI）");
    }

    /** Maps the model response into {@link FileAction#ai} suggestions (always require confirm). */
    private List<FileAction> parseResponse(String response, OrganizeContext context) {
        // TODO: parse the model's structured output into FileAction.ai(...) entries.
        return Collections.emptyList();
    }

    @Override
    public String name() {
        return "大模型AI" + (available() ? "" : "（未配置）");
    }
}
