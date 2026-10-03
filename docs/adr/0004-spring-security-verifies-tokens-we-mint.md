# Spring Security verifies bearer tokens; we still mint them

Authentication runs on Spring Security's OAuth2 resource server (stateless, HS256, JSON 401/403, `@PreAuthorize` for the admin-only route) instead of a hand-written filter, because hand-rolled security is the thing a reviewer least wants to find. Spring Security only verifies tokens, so `POST /auth/token` and the `JwtEncoder` remain ours: the email does not say how a checker obtains a token, so the service has to hand them out.

## Considered Options

A custom `OncePerRequestFilter` with the jjwt library was built first (about 45 lines, one test per branch). It worked, but it left every security detail ours to get right and taught nothing of the framework the project is built on. The migration was done test-first behind characterization tests of the HTTP boundary, so behaviour did not change.

## Consequences

The algorithm is pinned to HS256 on both the signing and verifying side (jjwt had inferred HS384 from the key length). The decoder uses zero clock skew (Spring's default accepts a token that expired a minute ago) and requires a subject. The shared dev secret and admin key still come from configuration with development defaults, so a real deployment must set `SEAT_JWT_SECRET` and `SEAT_ADMIN_KEY`.
