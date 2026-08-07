set shell := ["powershell", "-NoLogo", "-Command"]

mvn := ".tools/apache-maven-3.9.11/bin/mvn.cmd"
uv := ".tools/uv.exe"
buf := ".tools/buf.exe"

build:
    {{mvn}} clean verify

test:
    {{mvn}} test

lint:
    {{mvn}} spotless:check checkstyle:check
    python scripts/scan_language.py
    python scripts/check_forbidden_data.py

fmt:
    {{mvn}} spotless:apply
    {{uv}} run --project python-services/parser-svc ruff format .
    {{uv}} run --project python-services/deid-svc ruff format .
    {{uv}} run --project python-services/model-svc ruff format .

proto-gen:
    {{buf}} generate contracts --template contracts/buf.gen.yaml
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
