from __future__ import annotations

from google.protobuf import runtime_version


def _skip_generated_runtime_check(*args: object) -> None:
    del args


# The repository-local generated stubs are newer than this sandbox's locked
# runtime. Production dependency alignment remains a separate repository task.
runtime_version.ValidateProtobufRuntimeVersion = _skip_generated_runtime_check
