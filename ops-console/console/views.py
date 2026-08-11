from django.conf import settings
from django.contrib.auth.decorators import login_required
from django.http import Http404, HttpRequest, HttpResponse
from django.shortcuts import redirect, render
from django.views.decorators.http import require_POST

from .adapters import ActionRejected, JavaGovernanceApiAdapter
from .models import DocumentMetadataReviewQueue, EvaluationCandidateQueue, FeedbackReviewQueue, QuarantineQueueItem


QUEUE_MODELS = {
    "feedback": FeedbackReviewQueue,
    "quarantine": QuarantineQueueItem,
    "document-metadata": DocumentMetadataReviewQueue,
    "evaluation-candidates": EvaluationCandidateQueue,
}


@login_required
def queue_index(request: HttpRequest) -> HttpResponse:
    queues = [(key, model._meta.verbose_name_plural.title()) for key, model in QUEUE_MODELS.items()]
    return render(request, "console/index.html", {"queues": queues})


@login_required
def queue_list(request: HttpRequest, queue_name: str) -> HttpResponse:
    model = QUEUE_MODELS.get(queue_name)
    if model is None:
        raise Http404("Unknown review queue")
    queryset = model.objects.none()
    if settings.OPS_CONSOLE_READ_MODEL_ENABLED:
        ordering = "-updated_at" if hasattr(model, "updated_at") else "-created_at"
        queryset = model.objects.all().order_by(ordering)
        if request.GET.get("status"):
            queryset = queryset.filter(status=request.GET["status"][:64])
    return render(request, "console/queue_list.html", {"queue_name": queue_name, "queue_label": model._meta.verbose_name_plural.title(), "items": queryset})


@login_required
@require_POST
def queue_action(request: HttpRequest, queue_name: str, item_id: str) -> HttpResponse:
    action = request.POST.get("action", "")
    actor = getattr(request.user, "get_username", lambda: "")()
    adapter = JavaGovernanceApiAdapter(base_url=settings.OPS_JAVA_API_BASE_URL, token=settings.OPS_JAVA_API_TOKEN)
    try:
        adapter.change_state(queue_name, item_id, action, actor)
    except ActionRejected as exc:
        return render(request, "console/error.html", {"message": str(exc)}, status=403)
    return redirect("queue-list", queue_name=queue_name)
