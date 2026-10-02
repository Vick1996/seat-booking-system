package com.seatreserve.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtService {
    private static final long TTL_MS = 24L * 60 * 60 * 1000;
    private final SecretKey key;

    public JwtService(SeatProperties props) {
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(String userId, boolean admin) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim("admin", admin)
                .issuedAt(new Date(now))
                .expiration(new Date(now + TTL_MS))
                .signWith(key)
                .compact();
    }

    public Optional<Principal> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String sub = c.getSubject();
            if (sub == null || sub.isBlank()) return Optional.empty();
            return Optional.of(new Principal(sub, Boolean.TRUE.equals(c.get("admin", Boolean.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
