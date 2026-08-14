from parser_svc.settings import ParserSettings


def test_defaults_are_explicit() -> None:
    settings = ParserSettings()

    assert settings.service_name == "parser-svc"
    assert settings.grpc_port == 9001
    assert settings.grpc_workers > 0
    assert settings.grpc_max_concurrent_rpcs > 0
    assert settings.worker_threads > 0
    assert settings.work_queue_capacity >= 0
    assert settings.runtime_intra_op_threads == 1
    assert settings.runtime_inter_op_threads == 1
    assert settings.metrics_port == 9101
