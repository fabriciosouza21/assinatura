package com.globo.assinatura.consulta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.consulta.api.AssinaturaLista;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ListarAssinaturasTest {

  private static final Long USUARIO_INTERNO = 42L;

  @Mock private AssinaturaRepository assinaturaRepository;

  @Mock private UsuarioRepository usuarioRepository;

  @Mock private AssinaturaListaCache assinaturaListaCache;

  @InjectMocks private ListarAssinaturas query;

  private static Usuario usuarioComId() {
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    usuario.setId(USUARIO_INTERNO);
    return usuario;
  }

  @Test
  @DisplayName("Deve listar as assinaturas do usuario paginadas preservando a ordem da pagina")
  void deveListarAssinaturasPaginadasPreservandoOrdemDaPagina() {
    Usuario usuario = usuarioComId();
    Assinatura maisRecente = new Assinatura(USUARIO_INTERNO, Plano.PREMIUM);
    Assinatura maisAntiga = new Assinatura(USUARIO_INTERNO, Plano.BASICO);
    when(usuarioRepository.findByUuid(usuario.getUuid())).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.findByUsuarioIdOrderByIdDesc(USUARIO_INTERNO, PageRequest.of(0, 20)))
        .thenReturn(new PageImpl<>(List.of(maisRecente, maisAntiga), PageRequest.of(0, 20), 2));

    AssinaturaLista resposta = query.executar(usuario.getUuid(), 0, 20);

    assertThat(resposta.page()).as("Pagina corrente").isEqualTo(0);
    assertThat(resposta.size()).as("Tamanho da pagina").isEqualTo(20);
    assertThat(resposta.total()).as("Total de assinaturas do usuario").isEqualTo(2);
    assertThat(resposta.items())
        .as("Itens na ordem da pagina (mais recente primeiro)")
        .extracting(AssinaturaResponse::id)
        .containsExactly(maisRecente.getUuid(), maisAntiga.getUuid());
    assertThat(resposta.items())
        .as("Todos os itens pertencem ao usuario do token")
        .extracting(AssinaturaResponse::usuarioId)
        .containsOnly(usuario.getUuid());
    verify(assinaturaRepository)
        .findByUsuarioIdOrderByIdDesc(USUARIO_INTERNO, PageRequest.of(0, 20));
  }

  @Test
  @DisplayName("Deve responder do cache sem consultar o banco em acerto de cache")
  void deveResponderDoCacheSemConsultarBanco() {
    Usuario usuario = usuarioComId();
    AssinaturaLista cacheada = new AssinaturaLista(List.of(), 0, 20, 0);
    when(assinaturaListaCache.recuperar(usuario.getUuid(), 0, 20))
        .thenReturn(Optional.of(cacheada));

    AssinaturaLista resposta = query.executar(usuario.getUuid(), 0, 20);

    assertThat(resposta).as("Resposta vem do cache").isSameAs(cacheada);
    verify(usuarioRepository, never()).findByUuid(any());
    verify(assinaturaRepository, never()).findByUsuarioIdOrderByIdDesc(any(), any());
  }

  @Test
  @DisplayName("Deve consultar o banco e popular o cache em miss")
  void deveConsultarBancoQuandoCacheMiss() {
    Usuario usuario = usuarioComId();
    Assinatura assinatura = new Assinatura(USUARIO_INTERNO, Plano.PREMIUM);
    when(assinaturaListaCache.recuperar(usuario.getUuid(), 0, 20)).thenReturn(Optional.empty());
    when(usuarioRepository.findByUuid(usuario.getUuid())).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.findByUsuarioIdOrderByIdDesc(USUARIO_INTERNO, PageRequest.of(0, 20)))
        .thenReturn(new PageImpl<>(List.of(assinatura), PageRequest.of(0, 20), 1));

    AssinaturaLista resposta = query.executar(usuario.getUuid(), 0, 20);

    assertThat(resposta.items()).as("Itens consultados do banco").hasSize(1);
    verify(assinaturaListaCache).popular(usuario.getUuid(), 0, 20, resposta);
  }

  @Test
  @DisplayName("Deve popular o cache tambem para usuario sem assinaturas")
  void devePopularCacheParaUsuarioSemAssinaturas() {
    Usuario usuario = usuarioComId();
    when(assinaturaListaCache.recuperar(usuario.getUuid(), 0, 20)).thenReturn(Optional.empty());
    when(usuarioRepository.findByUuid(usuario.getUuid())).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.findByUsuarioIdOrderByIdDesc(USUARIO_INTERNO, PageRequest.of(0, 20)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    AssinaturaLista resposta = query.executar(usuario.getUuid(), 0, 20);

    assertThat(resposta.items()).as("Itens de usuario sem assinaturas").isEmpty();
    verify(assinaturaListaCache).popular(usuario.getUuid(), 0, 20, resposta);
  }

  @Test
  @DisplayName("Deve refletir datas e status da assinatura em cada item da lista")
  void deveRefletirDadosDaAssinaturaEmCadaItem() {
    Usuario usuario = usuarioComId();
    Assinatura assinatura = new Assinatura(USUARIO_INTERNO, Plano.PREMIUM);
    assinatura.ativar(
        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), Instant.parse("2026-08-31T00:00:00Z"));
    when(usuarioRepository.findByUuid(usuario.getUuid())).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.findByUsuarioIdOrderByIdDesc(USUARIO_INTERNO, PageRequest.of(0, 20)))
        .thenReturn(new PageImpl<>(List.of(assinatura), PageRequest.of(0, 20), 1));

    AssinaturaLista resposta = query.executar(usuario.getUuid(), 0, 20);

    AssinaturaResponse item = resposta.items().getFirst();
    assertThat(item.plano()).as("Plano do item").isEqualTo(Plano.PREMIUM);
    assertThat(item.status()).as("Status do item").isEqualTo(assinatura.getStatus());
    assertThat(item.dataInicio()).as("Data inicio do item").isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(item.dataExpiracao())
        .as("Data expiracao do item")
        .isEqualTo(LocalDate.of(2026, 8, 31));
  }

  @Test
  @DisplayName("Deve retornar lista vazia com total zero para usuario sem assinaturas")
  void deveRetornarListaVaziaParaUsuarioSemAssinaturas() {
    Usuario usuario = usuarioComId();
    when(usuarioRepository.findByUuid(usuario.getUuid())).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.findByUsuarioIdOrderByIdDesc(USUARIO_INTERNO, PageRequest.of(0, 20)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    AssinaturaLista resposta = query.executar(usuario.getUuid(), 0, 20);

    assertThat(resposta.items()).as("Itens de usuario sem assinaturas").isEmpty();
    assertThat(resposta.total()).as("Total de usuario sem assinaturas").isZero();
  }

  @Test
  @DisplayName("Deve retornar itens vazios preservando o total quando a pagina passa do fim")
  void deveRetornarItensVaziosPreservandoTotalQuandoPaginaPassaDoFim() {
    Usuario usuario = usuarioComId();
    when(usuarioRepository.findByUuid(usuario.getUuid())).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.findByUsuarioIdOrderByIdDesc(USUARIO_INTERNO, PageRequest.of(5, 20)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 20), 3));

    AssinaturaLista resposta = query.executar(usuario.getUuid(), 5, 20);

    assertThat(resposta.items()).as("Itens alem da ultima pagina").isEmpty();
    assertThat(resposta.total()).as("Total preservado alem da ultima pagina").isEqualTo(3);
  }

  @Test
  @DisplayName("Deve retornar lista vazia para usuario do token inexistente no banco")
  void deveRetornarListaVaziaParaUsuarioInexistente() {
    when(usuarioRepository.findByUuid("uuid-desconhecido")).thenReturn(Optional.empty());

    AssinaturaLista resposta = query.executar("uuid-desconhecido", 0, 20);

    assertThat(resposta.items()).as("Itens de usuario desconhecido").isEmpty();
    assertThat(resposta.total()).as("Total de usuario desconhecido").isZero();
    verify(assinaturaRepository, never()).findByUsuarioIdOrderByIdDesc(any(), any());
  }
}
