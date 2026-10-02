package com.seatreserve.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/** Bearer-JWT auth. Public: token mint, actuator, and read-only GET /shows/**. */
@Component
public class AuthFilter extends OncePerRequestFilter {
    public static final String ATTR = "principal";
    private final JwtService jwt;

    public AuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return p.startsWith("/auth/") || p.startsWith("/actuator")
                || ("GET".equals(req.getMethod()) && p.startsWith("/shows/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        Optional<Principal> p = (h != null && h.startsWith("Bearer "))
                ? jwt.parse(h.substring(7).trim()) : Optional.empty();
        if (p.isEmpty()) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\",\"reason\":\"missing-or-invalid-token\"}");
            return;
        }
        req.setAttribute(ATTR, p.get());
        chain.doFilter(req, res);
    }
}
