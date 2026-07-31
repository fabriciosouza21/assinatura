package com.globo.pagamento.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuração de segurança do Pagamento Service.
 *
 * <p>Libera o healthcheck e o endpoint de webhook. O webhook ({@code /webhooks/payments}) e
 * autenticado por assinatura HMAC, validada no proprio endpoint, por isso fica publico na cadeia do
 * Spring Security.
 */
@Configuration
public class SecurityConfig {

  /**
   * Constrói a cadeia de filtros de segurança.
   *
   * <p>Libera {@code /actuator/health} e {@code /webhooks/payments}; todas as demais rotas exigem
   * autenticacao.
   *
   * @param http o builder de segurança do Spring
   * @return a cadeia de filtros configurada
   * @throws Exception se a configuração falhar
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/webhooks/payments")
                    .permitAll()
                    .anyRequest()
                    .authenticated());
    return http.build();
  }
}
