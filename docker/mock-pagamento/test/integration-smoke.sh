#!/usr/bin/env bash
# Teste de integracao INT-1: valida que servicos conversam pela network do
# compose por nome de host. Um container efemero resolve "mock-pagamento" e
# chama POST /v1/payments, esperando 201 com status PENDING.
#
# Uso: ./docker/mock-pagamento/test/integration-smoke.sh
set -euo pipefail

NETWORK="${NETWORK:-assinatura_default}"
MOCK_HOST="${MOCK_HOST:-mock-pagamento}"
MOCK_PORT="${MOCK_PORT:-8081}"

echo "[1/3] Subindo mock-pagamento..."
docker compose up -d mock-pagamento

cleanup() {
  docker compose down >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "[2/3] Aguardando mock ficar saudavel..."
for _ in $(seq 1 15); do
  if curl -sf "http://localhost:${MOCK_PORT}/healthz" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "[3/3] Chamando POST /v1/payments pela network do compose (resolucao por nome)..."
body=$(docker run --rm --network "${NETWORK}" curlimages/curl:8.7.1 \
  -s -w "\n%{http_code}" \
  -X POST "http://${MOCK_HOST}:${MOCK_PORT}/v1/payments" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: int-smoke-$(date +%s)" \
  -d '{"externalReference":"assinatura_uuid","amount":4990,"currency":"BRL","paymentMethod":"PIX","notificationUrl":"http://assinatura:8080/webhooks/payments"}')

status=$(echo "${body}" | tail -1)
payload=$(echo "${body}" | head -n -1)

echo "HTTP: ${status}"
echo "Body: ${payload}"

if [ "${status}" = "201" ] && echo "${payload}" | grep -q '"status":"PENDING"'; then
  echo "PASS: integracao INT-1 OK (mock-pagamento alcancavel por nome de host, resposta 201 PENDING)"
  exit 0
fi

echo "FAIL: esperado 201 com status PENDING"
exit 1
