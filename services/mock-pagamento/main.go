package main

import (
	"log"
	"net/http"
	"os"
)

// Skeleton do Mock Payment API.
// Estado em memória, apenas biblioteca padrão. Endpoints em MOCK-1/MOCK-2.
func main() {
	addr := ":" + envOrDefault("MOCK_PORT", "8081")

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	})

	log.Printf("mock-pagamento ouvindo em %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("erro ao subir servidor: %v", err)
	}
}

func envOrDefault(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
