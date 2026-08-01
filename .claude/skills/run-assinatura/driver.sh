#!/usr/bin/env bash
# Driver de fluxo E2E do Sistema de Assinaturas.
#
# Sobe (se preciso) a stack docker-compose e roda o fluxo ponta a ponta
# documentado em bruno/README.md: cadastra usuario -> solicita assinatura ->
# consulta a cobranca gerada pelo Pagamento Service -> simula aprovacao no
# mock do gateway -> confirma que a assinatura chegou a ATIVA.
#
# Uso:
#   .claude/skills/run-assinatura/driver.sh            # fluxo completo
#   .claude/skills/run-assinatura/driver.sh health      # so healthchecks
#   .claude/skills/run-assinatura/driver.sh login       # so o login JWT
#
# Rodar a partir da raiz do repo (onde fica o docker-compose.yml).

set -euo pipefail

ASSINATURA_URL="${ASSINATURA_URL:-http://localhost:18080}"
PAGAMENTO_URL="${PAGAMENTO_URL:-http://localhost:18082}"
MOCK_URL="${MOCK_URL:-http://localhost:8081}"
SENHA="${SENHA:-admin123}"

log() { echo "[driver] $*" >&2; }

wait_http_200() {
  local url="$1" tentativas="${2:-30}"
  for i in $(seq 1 "$tentativas"); do
    if curl -sf -o /dev/null "$url"; then
      return 0
    fi
    sleep 2
  done
  log "timeout esperando $url responder 200"
  return 1
}

cmd_health() {
  log "checando healthchecks..."
  wait_http_200 "$ASSINATURA_URL/actuator/health"
  wait_http_200 "$PAGAMENTO_URL/actuator/health"
  wait_http_200 "$MOCK_URL/healthz"
  echo "assinatura: $(curl -s "$ASSINATURA_URL/actuator/health")"
  echo "pagamento:  $(curl -s "$PAGAMENTO_URL/actuator/health")"
  echo "mock:       $(curl -s "$MOCK_URL/healthz")"
}

cmd_login() {
  local resp
  resp=$(curl -sf -X POST "$ASSINATURA_URL/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"admin\",\"password\":\"$SENHA\"}")
  echo "$resp"
  echo "$resp" | jq -r '.token' > /dev/null || {
    log "login nao retornou token"
    return 1
  }
}

cmd_flow() {
  cmd_health

  log "1/5 cadastrando usuario..."
  local email="usuario.driver.$(date +%s)@example.com"
  local resp usuario_id
  resp=$(curl -sf -X POST "$ASSINATURA_URL/usuarios" \
    -H "Content-Type: application/json" \
    -d "{\"nome\":\"Usuario Driver\",\"email\":\"$email\",\"senha\":\"$SENHA\"}")
  usuario_id=$(echo "$resp" | jq -r '.id')
  log "usuarioId=$usuario_id"
  [ -n "$usuario_id" ] && [ "$usuario_id" != "null" ] || { log "falhou: $resp"; return 1; }

  log "2/5 solicitando assinatura PREMIUM..."
  resp=$(curl -sf -w '\n%{http_code}' -X POST "$ASSINATURA_URL/assinaturas" \
    -H "Content-Type: application/json" \
    -d "{\"usuarioId\":\"$usuario_id\",\"plano\":\"PREMIUM\"}")
  local http_code assinatura_body assinatura_id
  http_code=$(echo "$resp" | tail -n1)
  assinatura_body=$(echo "$resp" | sed '$d')
  [ "$http_code" = "202" ] || { log "esperava 202, veio $http_code: $assinatura_body"; return 1; }
  assinatura_id=$(echo "$assinatura_body" | jq -r '.id')
  log "assinaturaId=$assinatura_id status=$(echo "$assinatura_body" | jq -r '.status')"

  log "3/5 consultando cobranca no Pagamento Service (ponte assinatura -> pagamento)..."
  local payment_id=""
  for i in $(seq 1 15); do
    resp=$(curl -s -w '\n%{http_code}' "$PAGAMENTO_URL/cobrancas/$assinatura_id")
    http_code=$(echo "$resp" | tail -n1)
    if [ "$http_code" = "200" ]; then
      payment_id=$(echo "$resp" | sed '$d' | jq -r '.paymentId')
      break
    fi
    sleep 1
  done
  [ -n "$payment_id" ] && [ "$payment_id" != "null" ] || { log "cobranca nao apareceu a tempo"; return 1; }
  log "paymentId=$payment_id"

  log "4/5 simulando aprovacao no mock gateway..."
  resp=$(curl -sf -w '\n%{http_code}' -X POST "$MOCK_URL/v1/mock/payments/$payment_id/status" \
    -H "Content-Type: application/json" \
    -d '{"status":"APPROVED"}')
  http_code=$(echo "$resp" | tail -n1)
  echo "$resp" | sed '$d'
  [ "$http_code" = "200" ] || [ "$http_code" = "202" ] || { log "esperava 200/202, veio $http_code"; return 1; }

  log "5/5 aguardando webhook -> kafka -> assinatura ATIVA..."
  local status=""
  for i in $(seq 1 15); do
    resp=$(curl -sf "$ASSINATURA_URL/assinaturas/$assinatura_id")
    status=$(echo "$resp" | jq -r '.status')
    if [ "$status" = "ATIVA" ]; then
      echo "$resp"
      log "OK: assinatura $assinatura_id chegou a ATIVA"
      return 0
    fi
    sleep 1
  done
  log "timeout: status final foi '$status', esperava ATIVA"
  return 1
}

case "${1:-flow}" in
  health) cmd_health ;;
  login) cmd_login ;;
  flow) cmd_flow ;;
  *) echo "uso: $0 [health|login|flow]" >&2; exit 1 ;;
esac
