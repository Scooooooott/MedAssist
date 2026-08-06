from model_svc.settings import ModelSettings


def test_defaults_are_explicit() -> None:
    settings = ModelSettings()

    assert settings.service_name == "model-svc"
    assert settings.grpc_port == 9003
    assert settings.grpc_workers > 0
    assert settings.grpc_max_concurrent_rpcs > 0
