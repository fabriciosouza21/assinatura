package main

import (
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

	st := &store{
		byID:          make(map[string]*Payment),
		byIdempotency: make(map[string]string),
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", healthz)
	mux.HandleFunc("POST /v1/payments", st.createPayment)
	mux.HandleFunc("GET /v1/payments/{id}", st.getPayment)
	mux.HandleFunc("POST /v1/mock/payments/{id}/status", st.updateStatus(webhookSecret))

	log.Printf("mock-pagamento ouvindo em %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("erro ao subir servidor: %v", err)
	}
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

		// Webhook assincrono: nao bloqueia a resposta.
		if notifURL != "" {
			go dispatchWebhook(notifURL, webhookSecret, id, extRef)
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
func dispatchWebhook(notificationURL, secret, paymentID, externalReference string) {
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

	req, err := http.NewRequest(http.MethodPost, notificationURL, strings.NewReader(string(body)))
	if err != nil {
		log.Printf("webhook: erro ao montar requisicao: %v", err)
		return
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Mock-Event-Id", event.ID)
	req.Header.Set("X-Mock-Signature", "sha256="+hex.EncodeToString(mac.Sum(nil)))

	client := &http.Client{Timeout: 5 * time.Second}
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
