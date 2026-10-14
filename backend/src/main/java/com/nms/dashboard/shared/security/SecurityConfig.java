package com.nms.dashboard.shared.security;

import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what. {@code /api/**} needs a bearer token from the configured OIDC issuer (Keycloak), whose
 * signature, issuer, expiry and audience are all checked; the Angular app, health and the sign-in settings are
 * public — the app signs in itself, and none of them carries data. A token from a session signed out through
 * {@code POST /api/auth/logout} is refused ({@link SignedOutSessions}).
 */
@Configuration
public class SecurityConfig {

  private final boolean enabled;
  private final String issuer;
  private final String jwksUri;
  private final String audience;
  private final SignedOutSessions signedOut;

  public SecurityConfig(@Value("${app.auth.enabled}") boolean enabled, @Value("${app.auth.issuer}") String issuer,
      @Value("${app.auth.jwks-uri}") String jwksUri, @Value("${app.auth.audience}") String audience,
      SignedOutSessions signedOut) {
    this.enabled = enabled;
    this.issuer = issuer;
    this.jwksUri = jwksUri;
    this.audience = audience;
    this.signedOut = signedOut;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
    http.csrf(csrf -> csrf.disable()) // bearer tokens, no cookies: nothing for a forged request to ride on
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    if (!enabled) {
      return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
    AuthenticationEntryPoint entryPoint = entryPoint();
    return http
        .authorizeHttpRequests(a -> a
            .requestMatchers("/api/health", "/api/auth/config", "/error").permitAll()
            .requestMatchers("/api/**").authenticated()
            .anyRequest().permitAll())
        .oauth2ResourceServer(o -> o
            .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter()))
            .authenticationEntryPoint(entryPoint))
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
        .build();
  }

  @Bean
  JwtDecoder jwtDecoder() {
    // The key set is fetched on first use, so the backend starts even while Keycloak is still coming up.
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        // Issued for this API — a token the same realm issued for another client is refused.
        new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)),
        notSignedOut()));
    return decoder;
  }

  /** Refuses a token whose session was signed out, though the token itself has not expired yet. */
  private OAuth2TokenValidator<Jwt> notSignedOut() {
    return jwt -> signedOut.contains(jwt.getClaimAsString("sid"))
        ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "This sign-in was signed out; sign in again.", null))
        : OAuth2TokenValidatorResult.success();
  }

  /** {@code nms_access}: write → dashboard:read + dashboard:write; read → dashboard:read. */
  static JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setPrincipalClaimName("preferred_username");
    converter.setJwtGrantedAuthoritiesConverter((Jwt jwt) -> {
      List<GrantedAuthority> authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("dashboard:read")));
      if ("write".equals(jwt.getClaimAsString("nms_access"))) authorities.add(new SimpleGrantedAuthority("dashboard:write"));
      return authorities;
    });
    return converter;
  }

  /** 401 as {@code {"error": "..."}} with a {@code WWW-Authenticate: Bearer} challenge (RFC 6750). */
  static AuthenticationEntryPoint entryPoint() {
    return (request, response, failure) -> {
      String header = "Bearer";
      String message = "Sign in required.";
      if (failure instanceof OAuth2AuthenticationException oauth) {
        // A validator's own words (expired, wrong audience, signed out) rather than Spring's generic "Invalid token".
        String description = failure.getCause() instanceof JwtValidationException invalid && !invalid.getErrors().isEmpty()
            ? invalid.getErrors().iterator().next().getDescription()
            : oauth.getError().getDescription();
        header += " error=\"invalid_token\""
            + (description != null ? ", error_description=\"" + description.replace("\"", "'") + "\"" : "");
        message = "The access token was refused: " + (description != null ? description : oauth.getError().getErrorCode());
      }
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      // Header values are Latin-1 only: one non-ASCII character in a description and Tomcat drops the whole header.
      response.setHeader("WWW-Authenticate", header.replaceAll("[^\\x20-\\x7E]", "?"));
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    };
  }
}
