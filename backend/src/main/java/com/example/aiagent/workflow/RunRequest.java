package com.example.aiagent.workflow;

import jakarta.validation.constraints.*;

public record RunRequest(
    @NotBlank @Size(max=4000) String message,
    @Pattern(regexp="offline|live") String mode,
    @Pattern(regexp="support|engineering|all") String knowledgeBase,
    @Min(2) @Max(6) Integer maxIterations,
    @Pattern(regexp="none|retrieval_gap|tool_timeout|review_rework") String fault,
    @Size(max=6_000_000) String image,
    @Size(max=100) String imageName
) {
    public String effectiveMode() { return mode == null ? "offline" : mode; }
    public String effectiveKb() { return knowledgeBase == null ? "support" : knowledgeBase; }
    public int budget() { return maxIterations == null ? 3 : maxIterations; }
    public String effectiveFault() { return fault == null ? "none" : fault; }
}
