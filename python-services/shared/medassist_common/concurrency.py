from __future__ import annotations

import contextvars
import threading
import time
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from typing import TypeVar

from medassist_common.observability import SafeTracer, ServiceMetrics

T = TypeVar("T")


class WorkRejectedError(RuntimeError):
    """Raised when a bounded worker pool has no execution or queue capacity."""


class BoundedExecutor:
    """Thread pool with immediate, observable rejection and context propagation."""

    def __init__(
        self,
        *,
        service_name: str,
        process_model: str,
        queue_name: str,
        max_workers: int,
        queue_capacity: int,
        metrics: ServiceMetrics | None = None,
        tracer: SafeTracer | None = None,
    ) -> None:
        if max_workers < 1:
            raise ValueError("max_workers must be positive")
        if queue_capacity < 0:
            raise ValueError("queue_capacity must not be negative")
        self.service_name = service_name
        self.process_model = process_model
        self.queue_name = queue_name
        self.max_workers = max_workers
        self.queue_capacity = queue_capacity
        self._metrics = metrics or ServiceMetrics(service_name, process_model)
        self._tracer = tracer or SafeTracer(service_name)
        self._executor = ThreadPoolExecutor(
            max_workers=max_workers,
            thread_name_prefix=f"{service_name}-{queue_name}",
        )
        self._capacity = threading.BoundedSemaphore(max_workers + queue_capacity)
        self._state_lock = threading.Lock()
        self._waiting = 0
        self._shutdown = False

    @property
    def ready(self) -> bool:
        with self._state_lock:
            return not self._shutdown

    def execute(
        self,
        operation: str,
        workload: str,
        task: Callable[[], T],
        *,
        batch_size: int = 1,
    ) -> T:
        request_started = time.perf_counter()
        if not self._capacity.acquire(blocking=False):
            self._metrics.reject(self.queue_name)
            self._metrics.observe_request(operation, "rejected", 0.0)
            with self._tracer.span(
                "python.capacity_rejected",
                self._attributes(operation, workload, batch_size, "rejected"),
            ):
                pass
            raise WorkRejectedError(f"{self.queue_name} capacity exhausted")

        submitted = time.perf_counter()
        request_context = contextvars.copy_context()
        with self._state_lock:
            if self._shutdown:
                self._capacity.release()
                raise WorkRejectedError(f"{self.queue_name} is shutting down")
            self._waiting += 1
            self._metrics.set_queue_depth(self.queue_name, self._waiting)

        def run() -> T:
            started = time.perf_counter()
            with self._state_lock:
                self._waiting -= 1
                self._metrics.set_queue_depth(self.queue_name, self._waiting)
            self._metrics.observe_queue_wait(self.queue_name, started - submitted)
            self._tracer.completed_span(
                "python.queue_wait",
                self._attributes(operation, workload, batch_size, "accepted"),
                elapsed=started - submitted,
            )
            execution_started = time.perf_counter()
            try:
                with self._tracer.span(
                    "python.inference_execution",
                    self._attributes(operation, workload, batch_size, "running"),
                ):
                    return task()
            finally:
                self._metrics.observe_execution(
                    operation,
                    time.perf_counter() - execution_started,
                )

        try:
            future = self._executor.submit(request_context.run, run)
        except BaseException:
            with self._state_lock:
                self._waiting -= 1
                self._metrics.set_queue_depth(self.queue_name, self._waiting)
            self._capacity.release()
            raise

        try:
            result = future.result()
        except BaseException:
            self._metrics.observe_request(
                operation,
                "error",
                time.perf_counter() - request_started,
            )
            raise
        else:
            self._metrics.observe_request(
                operation,
                "success",
                time.perf_counter() - request_started,
            )
            return result
        finally:
            self._capacity.release()

    def shutdown(self) -> None:
        with self._state_lock:
            self._shutdown = True
        self._executor.shutdown(wait=True, cancel_futures=True)
        self._metrics.set_queue_depth(self.queue_name, 0)

    def _attributes(
        self,
        operation: str,
        workload: str,
        batch_size: int,
        outcome: str,
    ) -> dict[str, str | int]:
        return {
            "service.name": self.service_name,
            "process.model": self.process_model,
            "queue.name": self.queue_name,
            "queue.capacity": self.queue_capacity,
            "worker.count": self.max_workers,
            "rpc.method": operation,
            "workload": workload,
            "batch.size": batch_size,
            "outcome": outcome,
        }
