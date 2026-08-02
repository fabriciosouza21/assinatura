package com.globo.assinatura.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configuração de segurança do serviço. Define a cadeia de filtros, a política de sessão stateless
 * (JWT) e as rotas públicas.
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
   * <p>Libera apenas {@code POST /usuarios} (auto-cadastro publico), {@code /auth/login} e {@code
   * /actuator/health}; todas as demais rotas exigem autenticacao, inclusive as de assinatura. Sem
   * token, ou com token invalido, a resposta e {@code 401}; o {@code 403} fica reservado a quem
   * esta autenticado mas nao e dono do recurso.
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
                auth.requestMatchers(HttpMethod.POST, "/usuarios")
                    .permitAll()
                    .requestMatchers("/auth/login", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /**
   * Retorna o codificador de senhas baseado em BCrypt.
   *
   * @return codificador de senhas
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Expõe o {@link AuthenticationManager} para uso na autenticação de credenciais.
   *
   * @param config a configuração de autenticação do Spring
   * @return o gerenciador de autenticação
   */
  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config) {
    return config.getAuthenticationManager();
  }
}
