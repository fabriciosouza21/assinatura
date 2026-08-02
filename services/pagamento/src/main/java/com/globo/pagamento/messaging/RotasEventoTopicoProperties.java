package com.globo.pagamento.messaging;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rotas de eventos da outbox para topicos Kafka.
 *
 * <p>Mapeia cada {@code eventType} (ex.: {@code PagamentoStatusAtualizado}, {@code
 * PagamentoRenovacaoAprovado}) ao nome do topico Kafka de destino. O roteamento por evento permite
 * que a outbox publique cada tipo de evento em seu topico proprio, desacoplando o publisher de um
 * topico unico.
 *
 * <p>Exemplo de configuracao em {@code application.yaml}:
 *
 * <pre><code>
 * app:
 *   kafka:
 *     rotas-evento-topico:
 *       PagamentoStatusAtualizado: pagamento-status-atualizado
 *       PagamentoRenovacaoAprovado: renovacao-resultado
 * </code></pre>
 *
 * @param rotasEventoTopico mapa de eventType para topico Kafka de destino
 */
@ConfigurationProperties("app.kafka")
public record RotasEventoTopicoProperties(Map<String, String> rotasEventoTopico) {}
