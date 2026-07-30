#!/bin/bash
#
# Cria bancos adicionais no mesmo container Postgres.
# Padrão da imagem oficial: scripts em /docker-entrypoint-initdb.d rodam apenas
# na primeira inicialização (volume vazio). Lê POSTGRES_MULTIPLE_DATABASES
# (lista separada por vírgula) e cria cada banco com o usuário padrão.
#
# Fonte: https://github.com/docker-library/docs/blob/master/postgres/README.md

set -e
set -u

if [ -n "${POSTGRES_MULTIPLE_DATABASES:-}" ]; then
  echo "Criando bancos: ${POSTGRES_MULTIPLE_DATABASES}"
  for db in $(echo "${POSTGRES_MULTIPLE_DATABASES}" | tr ',' ' '); do
    echo "  -> ${db}"
    psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" <<-EOSQL
      CREATE DATABASE "${db}";
      GRANT ALL PRIVILEGES ON DATABASE "${db}" TO "${POSTGRES_USER}";
EOSQL
  done
  echo "Bancos criados."
fi
