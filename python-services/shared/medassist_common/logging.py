from __future__ import annotations

import logging
import sys

from opentelemetry import trace
from pythonjsonlogger.json import JsonFormatter


class _TraceContextFilter(logging.Filter):
    def __init__(self, service_name: str) -> None:
        super().__init__()
        self._service_name = service_name

    def filter(self, record: logging.LogRecord) -> bool:
        span_context = trace.get_current_span().get_span_context()
        record.service_name = self._service_name
        record.trace_id = f"{span_context.trace_id:032x}" if span_context.is_valid else "-"
        record.span_id = f"{span_context.span_id:016x}" if span_context.is_valid else "-"
        return True


def configure_logging(service_name: str) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.addFilter(_TraceContextFilter(service_name))
    handler.setFormatter(
        JsonFormatter(
            "%(asctime)s %(levelname)s %(name)s %(message)s "
            "%(service_name)s %(trace_id)s %(span_id)s"
        )
    )
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(logging.INFO)
