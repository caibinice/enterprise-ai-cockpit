package com.example.aiagent.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ActionAuthService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final String password;
    private final String tokenSecret;
    private final long ttlSeconds;

    public ActionAuthService(
        @Value("${app.action-auth.password:}") String password,
        @Value("${app.action-auth.token-secret:}") String tokenSecret,
        @Value("${app.action-auth.token-ttl-minutes:30}") long ttlMinutes
    ) {
        this.password = password == null ? "" : password;
        this.tokenSecret = tokenSecret == null ? "" : tokenSecret;
        this.ttlSeconds = Math.max(60, ttlMinutes * 60);
    }

    public String verifyAndIssue(String suppliedPassword) {
        if (password.isBlank() || tokenSecret.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "敏感操作验证尚未配置"
            );
        }
        byte[] expected = password.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = (suppliedPassword == null ? "" : suppliedPassword)
            .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "操作密码错误");
        }
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;
        return expiresAt + "." + sign(Long.toString(expiresAt));
    }

    public boolean authorized(String authorization) {
        if (tokenSecret.isBlank() || authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        String[] parts = authorization.substring(7).trim().split("\\.", 2);
        if (parts.length != 2) {
            return false;
        }
        try {
            if (Long.parseLong(parts[0]) <= Instant.now().getEpochSecond()) {
                return false;
            }
        } catch (NumberFormatException ignored) {
            return false;
        }
        return MessageDigest.isEqual(
            sign(parts[0]).getBytes(StandardCharsets.UTF_8),
            parts[1].getBytes(StandardCharsets.UTF_8)
        );
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign action token", exception);
        }
    }
}
