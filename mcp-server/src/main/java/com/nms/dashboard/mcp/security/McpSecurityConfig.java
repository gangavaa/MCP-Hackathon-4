package com.nms.dashboard.mcp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call {@code /mcp}. A tool call needs a bearer token from the configured OIDC issuer (Keycloak) — checked
 * here the same way the API checks it (signature, issuer, expiry, audience), so a bad token is turned away with the
 * 401 that starts an MCP client's sign-in, not with a tool error. The API checks it again when the tool forwards it.
 *
 * <ul>
 *   <li>Discovery — {@code initialize}, {@code tools/list}, the panel resource — is open without a token
 *       ({@link McpDiscoveryFilter}), so Claude can list the tools at start-up and the sign-in waits for the first
 *       question that needs data.
 *   <li>A request without a valid token gets 401 with {@code WWW-Authenticate: Bearer resource_metadata="…"}: the MCP
 *       authorization flow. The client follows it to {@link McpAuthMetadataController}, finds Keycloak there, signs
 *       the user in and comes back with a token.
 * </ul>
 */
@Configuration
public class McpSecurityConfig {

  private final boolean enabled;
  private final String issuer;
  private final String jwksUri;
  private final String audience;
  private final String publicUrl;
  private final SignedOutSessions signedOut;

  public McpSecurityConfig(@Value("${app.auth.enabled}") boolean enabled, @Value("${app.auth.issuer}") String issuer,
      @Value("${app.auth.jwks-uri}") String jwksUri, @Value("${app.auth.audience}") String audience,
      @Value("${app.public-url}") String publicUrl, SignedOutSessions signedOut) {
    this.enabled = enabled;
    this.issuer = issuer;
    this.jwksUri = jwksUri;
    this.audience = audience;
    this.publicUrl = publicUrl;
    this.signedOut = signedOut;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable()) // bearer tokens, no cookies: nothing for a forged request to ride on
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    if (!enabled) {
      return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
    return http
        .authorizeHttpRequests(a -> a
            // Discovery without a token — see McpDiscoveryFilter. Tool calls need a token.
            .requestMatchers(request -> Boolean.TRUE.equals(request.getAttribute(McpDiscoveryFilter.PUBLIC))).permitAll()
            .requestMatchers("/mcp", "/mcp/**").authenticated()
            .anyRequest().permitAll())
        .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.decoder(jwtDecoder())).authenticationEntryPoint(entryPoint()))
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint()))
        .build();
  }

  /** Ahead of the security filter chain, so its verdict is on the request before authorization is decided. */
  @Bean
  FilterRegistrationBean<McpDiscoveryFilter> mcpDiscoveryFilter(ObjectMapper json) {
    FilterRegistrationBean<McpDiscoveryFilter> registration = new FilterRegistrationBean<>(new McpDiscoveryFilter(json));
    registration.addUrlPatterns("/mcp");
    registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 10);
    registration.setEnabled(enabled);
    return registration;
  }

  @Bean
  JwtDecoder jwtDecoder() {
    // The key set is fetched on first use, so the server starts even while Keycloak is still coming up.
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        // Issued for the NMS API — the token is forwarded there, and the API refuses any other audience.
        new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)),
        notSignedOut()));
    return decoder;
  }

  /** Refuses a token whose session the logout tool signed out, though the token itself has not expired yet. */
  private OAuth2TokenValidator<Jwt> notSignedOut() {
    return jwt -> signedOut.contains(jwt.getClaimAsString("sid"))
        ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "This sign-in was signed out; sign in again.", null))
        : OAuth2TokenValidatorResult.success();
  }

  /** 401 that tells an MCP client where to find the authorization server (RFC 9728). */
  AuthenticationEntryPoint entryPoint() {
    String metadata = publicUrl + McpAuthMetadataController.METADATA_PATH;
    return (request, response, failure) -> {
      String header = "Bearer resource_metadata=\"" + metadata + "\"";
      String message = "Sign in required.";
      if (failure instanceof OAuth2AuthenticationException oauth) {
        // A validator's own words (expired, wrong audience, signed out) rather than Spring's generic "Invalid token".
        String description = failure.getCause() instanceof JwtValidationException invalid && !invalid.getErrors().isEmpty()
            ? invalid.getErrors().iterator().next().getDescription()
            : oauth.getError().getDescription();
        header += ", error=\"invalid_token\"" + (description != null ? ", error_description=\"" + description.replace("\"", "'") + "\"" : "");
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
