.PHONY: help checkstyle spotless lint format test verify clean java-version

MAVEN := ./mvnw

.DEFAULT_GOAL := help

help: ## Mostra os comandos disponiveis
	@awk 'BEGIN {FS = ":.*##"; printf "\n\033[1mComandos:\033[0m\n\n"} \
	/^[a-zA-Z_-]+:.*##/ { printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2 } \
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

test: ## Roda os testes
	@echo "==> test"
	@$(MAVEN) test

verify: ## Pipeline completo: checkstyle + spotless + testes + package
	@echo "==> verify"
	@$(MAVEN) verify

clean: ## Limpa target/
	@echo "==> clean"
	@$(MAVEN) clean
