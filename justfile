set shell := ["powershell", "-NoLogo", "-Command"]

mvn := ".tools/apache-maven-3.9.11/bin/mvn.cmd"
uv := ".tools/uv.exe"
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

lint:
    {{mvn}} spotless:check checkstyle:check
    corepack pnpm --dir frontend run lint
    corepack pnpm --dir frontend run format
    python scripts/scan_language.py
    python scripts/check_forbidden_data.py

fmt:
    {{mvn}} spotless:apply
    corepack pnpm --dir frontend run format:write
    {{uv}} run --project python-services/parser-svc ruff format .
    {{uv}} run --project python-services/deid-svc ruff format .
    {{uv}} run --project python-services/model-svc ruff format .

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
