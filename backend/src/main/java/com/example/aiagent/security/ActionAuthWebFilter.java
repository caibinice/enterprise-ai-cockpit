package com.example.aiagent.security;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ActionAuthWebFilter implements WebFilter {
    private static final Set<HttpMethod> SENSITIVE_METHODS = Set.of(
        HttpMethod.POST,
        HttpMethod.PUT,
        HttpMethod.PATCH,
        HttpMethod.DELETE
    );
    private final ActionAuthService auth;

    public ActionAuthWebFilter(ActionAuthService auth) {
        this.auth = auth;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();
        boolean sensitive = SENSITIVE_METHODS.contains(method)
            && !path.equals("/api/action-auth/verify");
        if (!sensitive) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth.authorized(authorization)) {
            return chain.filter(exchange);
        }
        byte[] body = "{\"message\":\"此操作需要先验证操作密码\"}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse()
            .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
