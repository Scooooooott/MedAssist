# MedAssist Operations Console

This internal Django scaffold provides four review queues: feedback,
quarantine, document metadata, and evaluation candidates. The business models
are inspectdb-style projections with `managed = False`; Flyway remains the only
business schema owner.

The default settings use an in-memory SQLite database and keep the business
read model disabled. This makes the scaffold runnable without connecting to a
real database. A deployment must later provide the policy-compiled,
least-privilege read connection; a superuser, table owner, and RLS-bypass role
are out of scope.

The console does not define project permissions. The Java governance API is the
only state-changing and audit path, and its adapter rejects actions by default
until both an API URL and token are supplied. No automatic feedback promotion
or other automatic write-back is implemented.

Run locally after installing the project dependency:

```text
python manage.py check
python manage.py runserver 127.0.0.1:8000
```

Run the repository boundary check with `python scripts/check_migrations.py`.
