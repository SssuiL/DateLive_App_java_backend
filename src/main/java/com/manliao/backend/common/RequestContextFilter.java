package com.manliao.backend.common;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component("manliaoRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {
    public static String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("request_id"));
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-ID");
        String id = supplied != null && supplied.matches("[A-Za-z0-9_-]{1,64}")
                ? supplied : "req_" + UUID.randomUUID().toString().replace("-", "");
        request.setAttribute("request_id", id);
        response.setHeader("X-Request-ID", id);
        long started = System.nanoTime();
        MDC.put("request_id", id);
        try {
            chain.doFilter(request, response);
        } finally {
            request.setAttribute("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
            MDC.remove("request_id");
        }
    }
}
