package com.manliao.backend.common;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ErrorResponses {
    private final ObjectMapper mapper;
    public ErrorResponses(ObjectMapper mapper) { this.mapper = mapper; }
    public Map<String, Object> body(String code, String message, HttpServletRequest request, Object details) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("message", message);
        body.put("request_id", RequestContextFilter.requestId(request));
        body.put("details", details);
        return body;
    }
    public void write(HttpServletRequest request, HttpServletResponse response, ApiError error) throws IOException {
        response.setStatus(error.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (error.status() == 429) response.setHeader("Retry-After", String.valueOf(error.retryAfterSeconds()));
        response.getWriter().write(mapper.writeValueAsString(body(error.code(), error.getMessage(), request, error.details())));
    }
}
