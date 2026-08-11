import json
import re
from collections.abc import Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID


class ActionRejected(RuntimeError):
    """Raised when a state transition cannot be delegated to Java."""


class JavaGovernanceApiAdapter:
    """Delegate state changes and audit creation to the Java governance API."""

    def __init__(
        self,
        base_url: str = "",
        token: str = "",
        opener: Callable[..., object] = urlopen,
        timeout: float = 5.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.opener = opener
        self.timeout = timeout

    def change_state(self, queue: str, item_id: UUID | str, action: str, actor: str) -> dict:
        if not self.base_url or not self.token:
            raise ActionRejected("State changes are disabled until the Java API is configured.")
        if not re.fullmatch(r"[a-z0-9-]+", queue) or not re.fullmatch(r"[a-z0-9_.:-]+", action):
            raise ActionRejected("Invalid queue or action identifier.")
        if not actor or "\n" in actor or "\r" in actor:
            raise ActionRejected("An authenticated actor is required.")

        payload = {
            "queue": queue,
            "item_id": str(item_id),
            "action": action,
            "actor": actor,
            "audit": {"event_type": "OPS_CONSOLE_STATE_CHANGE", "required": True},
        }
        request = Request(
            f"{self.base_url}/internal/ops-console/state-change",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Accept": "application/json",
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "X-Audit-Required": "true",
            },
            method="POST",
        )
        try:
            with self.opener(request, timeout=self.timeout) as response:
                body = response.read().decode("utf-8")
        except (HTTPError, URLError, TimeoutError) as exc:
            raise ActionRejected("The Java API rejected or could not receive the action.") from exc
        return json.loads(body) if body else {"accepted": True}
