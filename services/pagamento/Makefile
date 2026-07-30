.PHONY: help checkstyle spotless lint format test test-integration test-integration-up test-integration-down verify clean java-version

MAVEN := ./mvnw
MAKE := make
# docker-compose.yml vive na raiz do monorepo; o make roda de services/pagamento.
COMPOSE := docker compose -f ../../docker-compose.yml

.DEFAULT_GOAL := help

help: ## Mostra os comandos disponiveis
	@awk 'BEGIN {FS = ":.*##"; printf "\n\033[1mComandos:\033[0m\n\n"} \
	/^[a-zA-Z_-]+:.*##/ { printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2 } \
	END { printf "\n" }' $(MAKEFILE_LIST)

java-version: ## Mostra versao do Java ativa
	@java -version 2>&1 | head -1

checkstyle: ## Verifica regras estruturais (Google Style). Falha com lista de violacoes
	@echo "==> checkstyle:check"
	@$(MAVEN) checkstyle:check

spotless: ## Verifica formatacao (NAO corrige). Falha apontando arquivos a formatar
	@echo "==> spotless:check"
	@$(MAVEN) spotless:check

lint: checkstyle spotless ## Roda checkstyle + spotless em sequencia

format: ## Corrige formatacao automaticamente (spotless:apply)
	@echo "==> spotless:apply"
	@$(MAVEN) spotless:apply

test: ## Roda os testes unitarios (exclui os marcados com @Tag("integration"))
	@echo "==> test (unitarios)"
	@$(MAVEN) test -DexcludedGroups=integration

test-integration-up: ## Sobe o Postgres do docker-compose (porta 5433) e aguarda a porta host aceitar TCP
	@echo "==> docker compose up -d --wait postgres"
	@$(COMPOSE) up -d --wait postgres
	@echo "==> aguardando porta 5433 no host aceitar conexoes..."
	@for i in 1 2 3 4 5 6 7 8 9 10; do \
		nc -z -w2 localhost 5433 >/dev/null 2>&1 && { echo "    pronto"; exit 0; }; \
		echo "    tentativa $$i/10..."; sleep 1; \
	done; \
	echo "porta 5433 nao abriu a tempo"; exit 1

test-integration-down: ## Derruba o Postgres do docker-compose
	@echo "==> docker compose stop postgres"
	@$(COMPOSE) stop postgres

test-integration: ## Roda os testes de integracao (@Tag("integration")); sobe o Postgres automaticamente
	@echo "==> test-integration"
	@$(MAKE) test-integration-up
	@$(MAVEN) test -Dgroups=integration

verify: ## Pipeline completo: checkstyle + spotless + testes unitarios + package (sem integracao)
	@echo "==> verify"
	@$(MAVEN) verify -DexcludedGroups=integration

clean: ## Limpa target/
	@echo "==> clean"
	@$(MAVEN) clean
