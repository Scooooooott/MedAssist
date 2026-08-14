from __future__ import annotations

import os

from medassist_common.config import BaseServiceSettings


def apply_runtime_thread_settings(settings: BaseServiceSettings) -> None:
    """Apply explicit native runtime limits before model libraries initialize."""

    intra_op = str(settings.runtime_intra_op_threads)
    inter_op = str(settings.runtime_inter_op_threads)
    os.environ["OMP_NUM_THREADS"] = intra_op
    os.environ["OMP_THREAD_LIMIT"] = intra_op
    os.environ["MKL_NUM_THREADS"] = intra_op
    os.environ["OPENBLAS_NUM_THREADS"] = intra_op
    os.environ["NUMEXPR_NUM_THREADS"] = intra_op
    os.environ["ORT_INTRA_OP_NUM_THREADS"] = intra_op
    os.environ["ORT_INTER_OP_NUM_THREADS"] = inter_op
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
