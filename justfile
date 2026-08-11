set shell := ["powershell", "-NoLogo", "-Command"]

mvn := ".tools/apache-maven-3.9.11/bin/mvn.cmd"
uv := ".tools/uv.exe"
uv_cache := ".tools/uv-cache"
uv_run := "$env:UV_CACHE_DIR = '" + uv_cache + "'; " + uv + " run"
buf := ".tools/buf.exe"
buf_cache := ".tools/buf-cache"

bootstrap:
    {{mvn}} -version
    {{uv}} --version
    {{buf}} --version
    corepack pnpm --dir frontend install --frozen-lockfile
    $env:BUF_CACHE_DIR = "{{buf_cache}}"; {{buf}} lint contracts
    {{mvn}} -pl contracts -am test
    python scripts/check_forbidden_data.py

build:
    {{mvn}} clean verify
    corepack pnpm --dir frontend run build

test:
    {{mvn}} test
    corepack pnpm --dir frontend run test
    {{uv_run}} --project python-services/parser-svc python -m pytest python-services/parser-svc/tests
    {{uv_run}} --project python-services/deid-svc python -m pytest python-services/deid-svc/tests
    {{uv_run}} --project python-services/model-svc python -m pytest python-services/model-svc/tests
    {{uv_run}} --project tools/deid-eval python -m pytest tools/deid-eval/tests
    {{uv_run}} --project tools/eval-harness python -m pytest tools/eval-harness/tests
    {{uv_run}} --project tools/eval-harness python -m pytest scripts/experiments/tests

lint:
    {{mvn}} spotless:check checkstyle:check
    corepack pnpm --dir frontend run lint
    corepack pnpm --dir frontend run format
    python scripts/scan_language.py
    python scripts/check_forbidden_data.py
    {{uv_run}} --project python-services/parser-svc ruff check --config python-services/parser-svc/pyproject.toml python-services/parser-svc
    {{uv_run}} --project python-services/parser-svc ruff format --check --config python-services/parser-svc/pyproject.toml python-services/parser-svc
    {{uv_run}} --project python-services/parser-svc mypy --config-file python-services/parser-svc/pyproject.toml python-services/parser-svc/src python-services/parser-svc/tests
    {{uv_run}} --project python-services/deid-svc ruff check --config python-services/deid-svc/pyproject.toml python-services/deid-svc
    {{uv_run}} --project python-services/deid-svc ruff format --check --config python-services/deid-svc/pyproject.toml python-services/deid-svc
    {{uv_run}} --project python-services/deid-svc mypy --config-file python-services/deid-svc/pyproject.toml python-services/deid-svc/src python-services/deid-svc/tests
    {{uv_run}} --project python-services/model-svc ruff check --config python-services/model-svc/pyproject.toml python-services/model-svc
    {{uv_run}} --project python-services/model-svc ruff format --check --config python-services/model-svc/pyproject.toml python-services/model-svc
    {{uv_run}} --project python-services/model-svc mypy --config-file python-services/model-svc/pyproject.toml python-services/model-svc/src python-services/model-svc/tests
    {{uv_run}} --project python-services/model-svc ruff check --config python-services/shared/pyproject.toml python-services/shared/medassist_common
    {{uv_run}} --project python-services/model-svc ruff format --check --config python-services/shared/pyproject.toml python-services/shared/medassist_common
    {{uv_run}} --project python-services/model-svc mypy --config-file python-services/shared/pyproject.toml python-services/shared/medassist_common
    {{uv_run}} --project tools/deid-eval ruff check tools/deid-eval
    {{uv_run}} --project tools/deid-eval mypy --config-file tools/deid-eval/pyproject.toml tools/deid-eval/src tools/deid-eval/tests
    {{uv_run}} --project tools/eval-harness ruff check tools/eval-harness
    {{uv_run}} --project tools/eval-harness mypy --config-file tools/eval-harness/pyproject.toml tools/eval-harness/src tools/eval-harness/tests
    {{uv_run}} --project tools/eval-harness ruff check scripts/experiments

fmt:
    {{mvn}} spotless:apply
    corepack pnpm --dir frontend run format:write
    {{uv_run}} --project python-services/parser-svc ruff format .
    {{uv_run}} --project python-services/deid-svc ruff format .
    {{uv_run}} --project python-services/model-svc ruff format .
    {{uv_run}} --project tools/deid-eval ruff format tools/deid-eval
    {{uv_run}} --project tools/eval-harness ruff format tools/eval-harness
    {{uv_run}} --project tools/eval-harness ruff format scripts/experiments

frontend-build:
    corepack pnpm --dir frontend run build

frontend-test:
    corepack pnpm --dir frontend run test

frontend-lint:
    corepack pnpm --dir frontend run lint
    corepack pnpm --dir frontend run format

proto-gen:
    $env:BUF_CACHE_DIR = "{{buf_cache}}"; {{buf}} generate contracts --template contracts/buf.gen.yaml
    python scripts/generate_proto.py

up:
    docker compose -f deploy/compose/compose.base.yml --profile core up -d

up-pipeline:
    docker compose -f deploy/compose/compose.base.yml -f deploy/compose/compose.pipeline.yml --profile pipeline up -d

up-governance:
    docker compose -f deploy/compose/compose.base.yml -f deploy/compose/compose.governance.yml --profile governance up -d

governance-validate:
    python scripts/governance/policy_compiler.py validate
    python -m pytest scripts/governance -q -p no:cacheprovider
    python ops-console/scripts/check_migrations.py

down:
    docker compose -f deploy/compose/compose.base.yml -f deploy/compose/compose.pipeline.yml -f deploy/compose/compose.governance.yml down

reset:
    docker compose -f deploy/compose/compose.base.yml -f deploy/compose/compose.pipeline.yml -f deploy/compose/compose.governance.yml down -v
    docker compose -f deploy/compose/compose.base.yml --profile core up -d

clean:
    mvn clean

fetch-data:
    python scripts/data/fetch_data.py

fetch-data-manifest:
    python scripts/data/fetch_data.py --manifest-only
