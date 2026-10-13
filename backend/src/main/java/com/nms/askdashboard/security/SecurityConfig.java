package com.nms.askdashboard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what. {@code /api/**} and {@code /mcp} need a bearer token from the configured OIDC issuer (Keycloak),
 * whose signature, issuer, expiry and audience are all checked; the Angular app, the panel HTML, health and the
 * discovery endpoints are public — the app signs in itself, and none of them carries data.
 *
 * <p>MCP discovery — {@code initialize}, {@code tools/list}, the panel resource — is open without a token
 * ({@link McpDiscoveryFilter}), so Claude can list the tools at start-up and the sign-in waits for the first question
 * that needs data.
 *
 * <p>A request without a valid token gets 401 with {@code WWW-Authenticate: Bearer resource_metadata="…"}: the MCP
 * authorization flow. An MCP client (Claude Code) follows it to {@link ProtectedResourceController}, finds Keycloak
 * there, registers itself, signs the user in and comes back with a token.
 */
@Configuration
public class SecurityConfig {

  private final boolean enabled;
  private final String issuer;
  private final String jwksUri;
  private final String audience;
  private final String publicUrl;

  public SecurityConfig(@Value("${app.auth.enabled}") boolean enabled, @Value("${app.auth.issuer}") String issuer,
      @Value("${app.auth.jwks-uri}") String jwksUri, @Value("${app.auth.audience}") String audience,
      @Value("${app.public-url}") String publicUrl) {
    this.enabled = enabled;
    this.issuer = issuer;
    this.jwksUri = jwksUri;
    this.audience = audience;
    this.publicUrl = publicUrl;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable()) // bearer tokens, no cookies: nothing for a forged request to ride on
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    if (!enabled) {
      return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
    return http
        .authorizeHttpRequests(a -> a
            .requestMatchers("/api/health", "/api/auth/config", "/.well-known/**", "/error").permitAll()
            // MCP discovery (initialize, tools/list, …) without a token — see McpDiscoveryFilter. Data needs a token.
            .requestMatchers(request -> Boolean.TRUE.equals(request.getAttribute(McpDiscoveryFilter.PUBLIC))).permitAll()
            .requestMatchers("/api/**", "/mcp", "/mcp/**").authenticated()
            .anyRequest().permitAll())
        .oauth2ResourceServer(o -> o
            .jwt(jwt -> jwt.decoder(jwtDecoder()).jwtAuthenticationConverter(jwtAuthenticationConverter()))
            .authenticationEntryPoint(entryPoint()))
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
    // The key set is fetched on first use, so the backend starts even while Keycloak is still coming up.
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        // Issued for this API — a token the same realm issued for another client is refused.
        new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience))));
    return decoder;
  }

  /** {@code nms_access}: write → dashboard:read + dashboard:write; read → dashboard:read. */
  static JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setPrincipalClaimName("preferred_username");
    converter.setJwtGrantedAuthoritiesConverter((Jwt jwt) -> {
      List<GrantedAuthority> authorities = new java.util.ArrayList<>(List.of(new SimpleGrantedAuthority("dashboard:read")));
      if ("write".equals(jwt.getClaimAsString("nms_access"))) authorities.add(new SimpleGrantedAuthority("dashboard:write"));
      return authorities;
    });
    return converter;
  }

  /** 401 that tells an MCP client where to find the authorization server (RFC 9728). */
  AuthenticationEntryPoint entryPoint() {
    String metadata = publicUrl + ProtectedResourceController.MCP_METADATA_PATH;
    return (request, response, failure) -> {
      String header = "Bearer resource_metadata=\"" + metadata + "\"";
      String message = "Sign in required.";
      if (failure instanceof OAuth2AuthenticationException oauth) {
        String description = oauth.getError().getDescription();
        header += ", error=\"invalid_token\"" + (description != null ? ", error_description=\"" + description.replace("\"", "'") + "\"" : "");
        message = "The access token was refused: " + (description != null ? description : oauth.getError().getErrorCode());
      }
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setHeader("WWW-Authenticate", header);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    };
  }
}
