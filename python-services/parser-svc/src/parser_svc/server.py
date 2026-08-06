from __future__ import annotations

from medassist_common import configure_logging, serve_health

from parser_svc.settings import ParserSettings


def main() -> None:
    settings = ParserSettings()
    configure_logging(settings.service_name)
    serve_health(settings)


if __name__ == "__main__":
    main()
