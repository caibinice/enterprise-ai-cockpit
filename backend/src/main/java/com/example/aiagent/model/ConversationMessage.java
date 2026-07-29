package com.example.aiagent.model;

import java.time.Instant;

public record ConversationMessage(String role, String content, Instant createdAt) {
}
