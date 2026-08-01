package com.globo.assinatura.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtro que extrai o token JWT do cabeçalho {@code Authorization} e popula o contexto de segurança
 * quando o token é válido.
 *
 * <p>O principal publicado é o {@link UsuarioAutenticado} do token, e a authority vem da claim
 * {@code role}, preservando a distinção entre cliente e administrador ao longo da requisição.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String HEADER = "Authorization";
  private static final String PREFIX = "Bearer ";

  private final JwtService jwtService;

  /**
   * Cria o filtro com o serviço de validação de tokens.
   *
   * @param jwtService o serviço de tokens JWT
   */
  public JwtAuthenticationFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
      throws ServletException, IOException {

    String header = request.getHeader(HEADER);
    if (header != null && header.startsWith(PREFIX)) {
      String token = header.substring(PREFIX.length());
      jwtService.extrairPrincipal(token).ifPresent(this::autenticar);
    }
    chain.doFilter(request, response);
  }

  private void autenticar(UsuarioAutenticado principal) {
    if (principal.role() == null) {
      return;
    }
    var auth =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
