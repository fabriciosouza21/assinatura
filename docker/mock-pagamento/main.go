package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
)

// Mock Payment API simplificado.
// Estado em memoria (map + mutex).
// Contrato: docs/mock-meio-pagamento.puml

type Payment struct {
	ID                string    `json:"id"`
	ExternalReference string    `json:"externalReference"`
	Amount            float64   `json:"amount"`
	Currency          string    `json:"currency"`
	PaymentMethod     string    `json:"paymentMethod"`
	Status            string    `json:"status"`
	PixCode           string    `json:"pixCode,omitempty"`
	ExpiresAt         time.Time `json:"expiresAt,omitempty"`
	ApprovedAt        time.Time `json:"approvedAt,omitempty"`
	NotificationURL   string    `json:"-"`
}

type CreatePaymentRequest struct {
	ExternalReference string  `json:"externalReference"`
	Amount            float64 `json:"amount"`
	Currency          string  `json:"currency"`
	PaymentMethod     string  `json:"paymentMethod"`
	NotificationURL   string  `json:"notificationUrl"`
}

type StatusUpdateRequest struct {
	Status string `json:"status"`
}

type StatusUpdateResponse struct {
	PaymentID        string `json:"paymentId"`
	PreviousStatus   string `json:"previousStatus"`
	Status           string `json:"status"`
	WebhookScheduled bool   `json:"webhookScheduled"`
}

type WebhookEvent struct {
	ID   string           `json:"id"`
	Type string           `json:"type"`
	Data WebhookEventData `json:"data"`
}

type WebhookEventData struct {
	PaymentID         string `json:"paymentId"`
	ExternalReference string `json:"externalReference"`
}

type store struct {
	mu            sync.Mutex
	byID          map[string]*Payment
	byIdempotency map[string]string // idempotency-key -> payment ID
}

var validTransitions = map[string]bool{
	"PENDING->APPROVED":  true,
	"PENDING->REJECTED":  true,
	"PENDING->CANCELLED": true,
	"PENDING->EXPIRED":   true,
}

func main() {
	addr := ":" + envOrDefault("MOCK_PORT", "8081")
	webhookSecret := envOrDefault("MOCK_WEBHOOK_SECRET", "mock-webhook-secret")

	shutdownTracer := initTracer()
	defer func() { _ = shutdownTracer(context.Background()) }()

	st := &store{
		byID:          make(map[string]*Payment),
		byIdempotency: make(map[string]string),
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", healthz)
	mux.HandleFunc("POST /v1/payments", st.createPayment)
	mux.HandleFunc("GET /v1/payments/{id}", st.getPayment)
	mux.HandleFunc("POST /v1/mock/payments/{id}/status", st.updateStatus(webhookSecret))

	handler := otelhttp.NewHandler(mux, "mock-pagamento")

	log.Printf("mock-pagamento ouvindo em %s", addr)
	if err := http.ListenAndServe(addr, handler); err != nil {
		log.Fatalf("erro ao subir servidor: %v", err)
	}
}

// initTracer configura um TracerProvider OTel com exporter OTLP HTTP e propagacao W3C.
//
// O endpoint OTLP e o nome do servico sao externalizados por variavel de ambiente. A criacao do
// exporter e best-effort: se o backend (Jaeger) estiver indisponivel, o mock sobe mesmo assim com
// um provider noop, para que a ausencia do exporter nunca bloqueie o fluxo de negocio.
//
// Retorna uma funcao de shutdown para drenar os spans ao encerrar o processo.
func initTracer() func(context.Context) error {
	endpoint := envOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318")
	serviceName := envOrDefault("OTEL_SERVICE_NAME", "mock-pagamento")

	exporter, err := otlptracehttp.New(
		context.Background(),
		otlptracehttp.WithEndpointURL(endpoint),
	)
	if err != nil {
		log.Printf("tracing: exporter indisponivel (%v); rodando sem exportacao", err)
		otel.SetTracerProvider(sdktrace.NewTracerProvider())
		return func(context.Context) error { return nil }
	}

	res, err := resource.New(
		context.Background(),
		resource.WithAttributes(semconv.ServiceName(serviceName)),
	)
	if err != nil {
		log.Printf("tracing: recurso indisponivel (%v); rodando sem atributos", err)
		res = resource.NewWithAttributes(semconv.SchemaURL, semconv.ServiceName(serviceName))
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))
	return tp.Shutdown
}

func healthz(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

func (s *store) createPayment(w http.ResponseWriter, r *http.Request) {
	var req CreatePaymentRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "corpo invalido"})
		return
	}

	idempotencyKey := r.Header.Get("Idempotency-Key")

	s.mu.Lock()
	// Idempotencia: mesma chave devolve o pagamento existente.
	if idempotencyKey != "" {
		if existingID, ok := s.byIdempotency[idempotencyKey]; ok {
			existing := s.byID[existingID]
			s.mu.Unlock()
			writeJSON(w, http.StatusOK, existing)
			return
		}
	}

	payment := &Payment{
		ID:                uuid.NewString(),
		ExternalReference: req.ExternalReference,
		Amount:            req.Amount,
		Currency:          req.Currency,
		PaymentMethod:     req.PaymentMethod,
		Status:            "PENDING",
		PixCode:           "000201010212" + mustRandomHex(16),
		ExpiresAt:         time.Now().Add(30 * time.Minute).UTC(),
		NotificationURL:   req.NotificationURL,
	}
	s.byID[payment.ID] = payment
	if idempotencyKey != "" {
		s.byIdempotency[idempotencyKey] = payment.ID
	}
	s.mu.Unlock()

	log.Printf("pagamento criado: paymentId=%s externalReference=%s", payment.ID, payment.ExternalReference)
	writeJSON(w, http.StatusCreated, payment)
}

func (s *store) getPayment(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")

	s.mu.Lock()
	payment, ok := s.byID[id]
	s.mu.Unlock()

	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "pagamento nao encontrado"})
		return
	}
	writeJSON(w, http.StatusOK, payment)
}

func (s *store) updateStatus(webhookSecret string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		var req StatusUpdateRequest
		if err := decodeJSON(r, &req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "corpo invalido"})
			return
		}

		s.mu.Lock()
		payment, ok := s.byID[id]
		if !ok {
			s.mu.Unlock()
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "pagamento nao encontrado"})
			return
		}
		previous := payment.Status
		if !validTransitions[previous+"->"+req.Status] {
			s.mu.Unlock()
			writeJSON(w, http.StatusConflict, map[string]string{
				"error": "transicao invalida",
			})
			return
		}
		payment.Status = req.Status
		if req.Status == "APPROVED" {
			payment.ApprovedAt = time.Now().UTC()
		}
		notifURL := payment.NotificationURL
		extRef := payment.ExternalReference
		s.mu.Unlock()

		// Webhook assincrono: nao bloqueia a resposta. O contexto do trace (que vive no
		// r.Context() instrumentado pelo otelhttp) e preservado, mas desvinculado do
		// cancelamento da requisicao original, que morre quando a resposta 202 retorna.
		if notifURL != "" {
			go dispatchWebhook(context.WithoutCancel(r.Context()), notifURL, webhookSecret, id, extRef)
		}

		writeJSON(w, http.StatusAccepted, StatusUpdateResponse{
			PaymentID:        id,
			PreviousStatus:   previous,
			Status:           req.Status,
			WebhookScheduled: notifURL != "",
		})
	}
}

// dispatchWebhook envia o evento de pagamento atualizado para a notificationUrl.
// Headers: X-Mock-Event-Id e X-Mock-Signature (HMAC SHA256 do corpo).
//
// Recebe o contexto da requisicao de origem para que o trace propagado pelo otelhttp chegue ao
// envio do webhook, evitando que ele apareca como um trace orfao no backend.
func dispatchWebhook(ctx context.Context, notificationURL, secret, paymentID, externalReference string) {
	event := WebhookEvent{
		ID:   uuid.NewString(),
		Type: "payment.updated",
		Data: WebhookEventData{PaymentID: paymentID, ExternalReference: externalReference},
	}
	body, err := json.Marshal(event)
	if err != nil {
		log.Printf("webhook: erro ao serializar evento: %v", err)
		return
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, notificationURL, strings.NewReader(string(body)))
	if err != nil {
		log.Printf("webhook: erro ao montar requisicao: %v", err)
		return
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Mock-Event-Id", event.ID)
	req.Header.Set("X-Mock-Signature", "sha256="+hex.EncodeToString(mac.Sum(nil)))

	client := &http.Client{
		Timeout:   5 * time.Second,
		Transport: otelhttp.NewTransport(http.DefaultTransport),
	}
	resp, err := client.Do(req)
	if err != nil {
		log.Printf("webhook: falha ao enviar para %s: %v", notificationURL, err)
		return
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	log.Printf("webhook enviado para %s: evento=%s status=%d", notificationURL, event.ID, resp.StatusCode)
}

func decodeJSON(r *http.Request, dst any) error {
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	defer r.Body.Close()
	return dec.Decode(dst)
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func mustRandomHex(n int) string {
	h, err := randomHex(n)
	if err != nil {
		log.Fatalf("erro ao gerar bytes aleatorios: %v", err)
	}
	return h
}

func envOrDefault(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
