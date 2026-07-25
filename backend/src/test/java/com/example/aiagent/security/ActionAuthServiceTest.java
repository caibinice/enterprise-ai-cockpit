package com.example.aiagent.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ActionAuthServiceTest {
    @Test
    void correctPasswordIssuesVerifiableToken() {
        ActionAuthService service = new ActionAuthService(
            "correct-password",
            "test-token-secret-with-at-least-32-bytes",
            30
        );

        String token = service.verifyAndIssue("correct-password");

        assertTrue(service.authorized("Bearer " + token));
        assertFalse(service.authorized("Bearer " + token + "tampered"));
    }

    @Test
    void wrongPasswordIsRejected() {
        ActionAuthService service = new ActionAuthService(
            "correct-password",
            "test-token-secret-with-at-least-32-bytes",
            30
        );

        assertThrows(
            ResponseStatusException.class,
            () -> service.verifyAndIssue("wrong-password")
        );
    }

    @Test
    void webFilterProtectsMutationsButAllowsVerificationEndpoint() {
        ActionAuthService service = new ActionAuthService(
            "correct-password",
            "test-token-secret-with-at-least-32-bytes",
            30
        );
        ActionAuthWebFilter filter = new ActionAuthWebFilter(service);
        AtomicBoolean invoked = new AtomicBoolean(false);
        WebFilterChain chain = exchange -> {
            invoked.set(true);
            return Mono.empty();
        };

        MockServerWebExchange blocked = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/api/chat").build()
        );
        filter.filter(blocked, chain).block();
        assertFalse(invoked.get());

        MockServerWebExchange verification = MockServerWebExchange.from(
            MockServerHttpRequest.method(
                HttpMethod.POST,
                "/api/action-auth/verify"
            ).build()
        );
        filter.filter(verification, chain).block();
        assertTrue(invoked.get());
    }
}
