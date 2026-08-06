from deid_svc.settings import DeidSettings


def test_defaults_are_explicit() -> None:
    settings = DeidSettings()

    assert settings.service_name == "deid-svc"
    assert settings.grpc_port == 9002
    assert settings.grpc_workers > 0
    assert settings.grpc_max_concurrent_rpcs > 0
