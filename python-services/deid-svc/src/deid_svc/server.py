from __future__ import annotations

from deid_svc.settings import DeidSettings
from medassist_common import configure_logging, serve_health


def main() -> None:
    settings = DeidSettings()
    configure_logging(settings.service_name)
    serve_health(settings)


if __name__ == "__main__":
    main()
