package com.globo.pagamento.shared.seguranca;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configuração de segurança do Pagamento Service.
 *
 * <p>Libera o healthcheck e o endpoint de webhook. O webhook ({@code /webhooks/payments}) e
 * autenticado por assinatura HMAC, validada no proprio endpoint, por isso fica publico na cadeia do
 * Spring Security. As consultas {@code GET} de {@code /cobrancas} e {@code /renovacoes} sao
 * publicas para o cliente acompanhar a cobranca corrente sem token. As demais rotas exigem JWT,
 * validado pelo {@link JwtAuthenticationFilter}, com {@code 401} para requisições sem token ou com
 * token invalido.
 */
@Configuration
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;

  /**
   * Cria a configuração de segurança com o filtro JWT.
   *
   * @param jwtAuthenticationFilter o filtro de autenticação JWT
   */
  public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
  }

  /**
   * Constrói a cadeia de filtros de segurança.
   *
   * <p>Libera {@code /actuator/health}, {@code /actuator/prometheus} e {@code /webhooks/payments};
   * todas as demais rotas exigem autenticacao.
   *
   * @param http o builder de segurança do Spring
   * @return a cadeia de filtros configurada
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health", "/actuator/prometheus", "/webhooks/payments")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/cobrancas/**", "/renovacoes/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
