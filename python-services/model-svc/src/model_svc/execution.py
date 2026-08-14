from __future__ import annotations

from collections.abc import Callable
from typing import TypeVar

from medassist_common import BoundedExecutor, SafeTracer, ServiceMetrics

from model_svc.core import EmbeddingInputType
from model_svc.settings import ModelSettings

T = TypeVar("T")

PROCESS_MODEL = "single_process_thread_pool"


class ModelExecutionPools:
    """Separate low-latency query capacity from throughput-oriented passage work."""

    def __init__(self, settings: ModelSettings) -> None:
        metrics = ServiceMetrics(settings.service_name, PROCESS_MODEL)
        tracer = SafeTracer(settings.service_name)
        self.query = BoundedExecutor(
            service_name=settings.service_name,
            process_model=PROCESS_MODEL,
            queue_name="query",
            max_workers=settings.query_worker_threads,
            queue_capacity=0,
            metrics=metrics,
            tracer=tracer,
        )
        self.passage = BoundedExecutor(
            service_name=settings.service_name,
            process_model=PROCESS_MODEL,
            queue_name="passage",
            max_workers=settings.worker_threads,
            queue_capacity=settings.work_queue_capacity,
            metrics=metrics,
            tracer=tracer,
        )

    @property
    def ready(self) -> bool:
        return self.query.ready and self.passage.ready

    def execute_embed(
        self,
        input_type: EmbeddingInputType,
        task: Callable[[], T],
        *,
        batch_size: int,
    ) -> T:
        executor = self.query if input_type == "query" else self.passage
        return executor.execute(
            "Embed",
            input_type,
            task,
            batch_size=batch_size,
        )

    def execute_rerank(self, task: Callable[[], T], *, batch_size: int) -> T:
        return self.query.execute(
            "Rerank",
            "query",
            task,
            batch_size=batch_size,
        )

    def shutdown(self) -> None:
        self.query.shutdown()
        self.passage.shutdown()
