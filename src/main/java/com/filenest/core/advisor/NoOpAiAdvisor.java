package com.filenest.core.advisor;

import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;

import java.util.Collections;
import java.util.List;

/**
 * The degradation target: an advisor that offers no suggestions at all.
 *
 * <p>Used when AI is unavailable (offline, misconfigured, timed out, errored). The
 * deterministic rule layer still produces a full plan, so the system keeps working with
 * zero AI — this is the whole point of keeping classification and AI as parallel,
 * independent sources rather than chaining them.
 */
public final class NoOpAiAdvisor implements AiAdvisor {

    @Override
    public List<FileAction> suggest(List<FileMeta> files, OrganizeContext context) {
        return Collections.emptyList();
    }

    @Override
    public String name() {
        return "无AI（降级）";
    }

    @Override
    public boolean available() {
        return false;
    }
}
