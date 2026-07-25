package com.example.aiagent.controller;

import com.example.aiagent.security.ActionAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/action-auth")
public class ActionAuthController {
    private final ActionAuthService auth;

    public ActionAuthController(ActionAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@Valid @RequestBody PasswordRequest request) {
        return Map.of(
            "token", auth.verifyAndIssue(request.password()),
            "tokenType", "Bearer",
            "expiresIn", auth.ttlSeconds()
        );
    }

    public record PasswordRequest(
        @NotBlank @Size(max = 128) String password
    ) {}
}
