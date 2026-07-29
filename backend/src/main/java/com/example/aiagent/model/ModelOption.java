package com.example.aiagent.model;

public record ModelOption(
    String id,
    String name,
    String description,
    String badge,
    boolean recommended
) {
}
