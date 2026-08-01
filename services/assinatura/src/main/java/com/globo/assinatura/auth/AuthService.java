package com.globo.assinatura.auth;

import com.globo.assinatura.security.JwtService;
import com.globo.assinatura.user.User;
import com.globo.assinatura.user.UserRepository;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Serviço de autenticação que valida credenciais e emite tokens JWT. */
@Service
public class AuthService {

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
   * @param request credenciais de login
   * @return o token gerado, com tipo e tempo de expiração
   * @throws BadCredentialsException se o usuário não existir ou a senha for inválida
   */
  public LoginResponse login(LoginRequest request) {
    User user =
        userRepository
            .findByUsername(request.username())
            .orElseThrow(() -> new BadCredentialsException("Credenciais inválidas"));
    if (!passwordEncoder.matches(request.password(), user.getPassword())) {
      throw new BadCredentialsException("Credenciais inválidas");
    }
    String token = jwtService.generateToken(user.getUsername(), user.getRole(), usuarioUuid(user));
    return new LoginResponse(token, "Bearer", expirationMillis);
  }

  private String usuarioUuid(User user) {
    if (user.getUsuarioId() == null) {
      return null;
    }
    return usuarioRepository.findById(user.getUsuarioId()).map(Usuario::getUuid).orElse(null);
  }
}
