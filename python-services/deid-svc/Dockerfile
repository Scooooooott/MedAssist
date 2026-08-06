FROM ghcr.io/astral-sh/uv:0.8.4-python3.12-bookworm-slim AS build
WORKDIR /app
COPY python-services/shared /app/python-services/shared
COPY python-services/deid-svc /app/python-services/deid-svc
WORKDIR /app/python-services/deid-svc
RUN uv sync --frozen --no-dev || uv sync --no-dev

FROM python:3.12-slim-bookworm
WORKDIR /app
COPY --from=build /app/python-services/deid-svc/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH"
CMD ["python", "-m", "deid_svc.server"]
