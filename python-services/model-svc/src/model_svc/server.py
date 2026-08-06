from __future__ import annotations

from medassist_common import configure_logging, serve_health
from model_svc.settings import ModelSettings


def main() -> None:
    settings = ModelSettings()
    configure_logging(settings.service_name)
    serve_health(settings)


if __name__ == "__main__":
    main()
