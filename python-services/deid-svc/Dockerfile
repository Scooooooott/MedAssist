FROM ghcr.io/astral-sh/uv:0.8.4-python3.12-bookworm-slim AS build
WORKDIR /app
COPY python-services/shared /app/python-services/shared
COPY python-services/deid-svc /app/python-services/deid-svc
COPY contracts/proto /app/contracts/proto
COPY scripts/generate_proto.py /app/scripts/generate_proto.py
RUN uv sync --project /app/python-services/shared --frozen
RUN uv run --project /app/python-services/shared python /app/scripts/generate_proto.py
WORKDIR /app/python-services/deid-svc
RUN uv sync --frozen --no-dev

FROM python:3.12-slim-bookworm
WORKDIR /app
COPY --from=build /app/python-services/deid-svc/.venv /app/.venv
COPY --from=build /app/python-services/_generated /app/python-services/_generated
ENV PATH="/app/.venv/bin:$PATH"
ENV MEDASSIST_GENERATED_PROTO_DIR="/app/python-services/_generated"
CMD ["python", "-m", "deid_svc.server"]
