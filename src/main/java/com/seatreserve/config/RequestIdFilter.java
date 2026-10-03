package com.seatreserve.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Correlation id on every log line (via MDC) and echoed back; one access-log line per request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI(); // probes and scrapes would drown the log
        return p.startsWith("/actuator") || p.equals("/healthz") || p.equals("/readyz") || p.equals("/metrics");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader("X-Request-Id");
        if (id == null || id.isBlank() || id.length() > 64 || !id.matches("[A-Za-z0-9._-]+"))
            id = UUID.randomUUID().toString();
        MDC.put("request_id", id);
        res.setHeader("X-Request-Id", id);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            // user_id is already in the MDC if the request authenticated (see SecurityConfig.UserLogFilter)
            log.info("{} {} -> {} in {}ms", req.getMethod(), req.getRequestURI(), res.getStatus(),
                    (System.nanoTime() - start) / 1_000_000);
            MDC.clear();
        }
    }
}
