package com.globo.assinatura.messaging;

import java.util.Map;

/**
 * Rotas de eventos da outbox para topicos Kafka.
 *
 * <p>Mapeia cada {@code eventType} (ex.: {@code AssinaturaSolicitada}, {@code RenovacaoSolicitada})
 * ao nome do topico Kafka de destino. O roteamento por evento permite que a outbox publique cada
 * tipo de evento em seu topico proprio, desacoplando o publisher de um topico unico.
 */
public record RotasEventoTopicoProperties(Map<String, String> rotasEventoTopico) {}
