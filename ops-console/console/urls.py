from django.urls import path

from . import views


urlpatterns = [
    path("", views.queue_index, name="queue-index"),
    path("queues/<slug:queue_name>/", views.queue_list, name="queue-list"),
    path("queues/<slug:queue_name>/items/<uuid:item_id>/actions/", views.queue_action, name="queue-action"),
]
