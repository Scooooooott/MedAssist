from model_svc.settings import ModelSettings


def test_defaults_are_explicit() -> None:
    settings = ModelSettings()

    assert settings.service_name == "model-svc"
    assert settings.grpc_port == 9003
    assert settings.grpc_workers > 0
    assert settings.grpc_max_concurrent_rpcs > 0
    assert settings.rerank_enabled is False
    assert settings.rerank_profile == "online"
    assert settings.rerank_max_candidates == 100
    assert settings.rerank_online_max_length == 512
    assert settings.rerank_offline_model_name == "BAAI/bge-reranker-v2-m3"
    assert settings.max_resident_embedding_models == 1
