package com.seatreserve.api;

import com.seatreserve.config.JwtService;
import com.seatreserve.config.SeatProperties;
import com.seatreserve.domain.DomainException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Dev token mint. A real deployment would sit behind an identity provider. */
@RestController
public class AuthController {
    public record TokenRequest(String userId, String adminKey) {
    }

    private final JwtService jwt;
    private final SeatProperties props;

    public AuthController(JwtService jwt, SeatProperties props) {
        this.jwt = jwt;
        this.props = props;
    }

    @PostMapping("/auth/token")
    public Map<String, Object> token(@RequestBody TokenRequest req) {
        if (req == null || req.userId() == null || req.userId().isBlank() || req.userId().length() > 128)
            throw DomainException.bad("invalid-user", "user_id is required (max 128 chars)");
        boolean admin = false;
        if (req.adminKey() != null) {
            boolean ok = MessageDigest.isEqual(req.adminKey().getBytes(StandardCharsets.UTF_8),
                    props.adminKey().getBytes(StandardCharsets.UTF_8));
            if (!ok) throw DomainException.forbidden("bad-admin-key", "admin_key is not valid");
            admin = true;
        }
        return Map.of("token", jwt.issue(req.userId(), admin), "user_id", req.userId(), "admin", admin);
    }
}
