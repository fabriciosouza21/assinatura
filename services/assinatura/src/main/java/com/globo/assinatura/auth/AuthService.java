package com.globo.assinatura.auth;

import com.globo.assinatura.security.JwtService;
import com.globo.assinatura.user.User;
import com.globo.assinatura.user.UserRepository;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Serviço de autenticação que valida credenciais e emite tokens JWT. */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  /**
   * Hash BCrypt de uma senha aleatoria, usado quando o usuario nao existe para manter o tempo de
   * resposta do login constante e nao revelar quais credenciais estao cadastradas.
   */
  private static final String SENHA_FANTASMA =
      "$2a$10$5izR2tfZoGCXuxrp6P5jPOnreExikbWQC8ZLNSzlslZpUyXUhZ5GW";

  private final UserRepository userRepository;
  private final UsuarioRepository usuarioRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final long expirationMillis;

  /**
   * Cria o serviço com suas dependências.
   *
   * @param userRepository o repositório de credenciais
   * @param usuarioRepository o repositório de usuários de domínio
   * @param passwordEncoder o codificador de senhas
   * @param jwtService o serviço de emissão de tokens JWT
   * @param expirationMillis o tempo de expiração do token, em milissegundos
   */
  public AuthService(
      UserRepository userRepository,
      UsuarioRepository usuarioRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      @Value("${app.jwt.expiration-ms}") long expirationMillis) {
    this.userRepository = userRepository;
    this.usuarioRepository = usuarioRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.expirationMillis = expirationMillis;
  }

  /**
   * Valida as credenciais e emite um token JWT.
   *
   * <p>Quando a credencial está ligada a um usuário de domínio, o uuid público desse usuário viaja
   * no token como claim {@code usuarioId} e identifica o dono das assinaturas nas requisições
   * seguintes. O administrador não possui usuário ligado e recebe token sem a claim.
   *
   * <p>Usuarios inexistentes pagam o mesmo custo de BCrypt que usuarios cadastrados, via um hash
   * fantasma, para nao vazar por tempo quais credenciais existem.
   *
   * @param request credenciais de login
   * @return o token gerado, com tipo e tempo de expiração
   * @throws BadCredentialsException se o usuário não existir ou a senha for inválida
   */
  public LoginResponse login(LoginRequest request) {
    User user = userRepository.findByUsername(request.username()).orElse(null);
    if (!passwordEncoder.matches(request.password(), hashDe(user))) {
      throw new BadCredentialsException("Credenciais inválidas");
    }
    String token = jwtService.generateToken(user.getUsername(), user.getRole(), usuarioUuid(user));
    return new LoginResponse(token, "Bearer", expirationMillis);
  }

  private String hashDe(User user) {
    return user != null ? user.getPassword() : SENHA_FANTASMA;
  }

  private String usuarioUuid(User user) {
    if (user.getUsuarioId() == null) {
      return null;
    }
    return usuarioRepository
        .findById(user.getUsuarioId())
        .map(Usuario::getUuid)
        .orElseGet(
            () -> {
              log.atWarn()
                  .addKeyValue("event", "credencial_usuario_orfao")
                  .log("Credencial ligada a usuario de dominio inexistente");
              return null;
            });
  }
}
