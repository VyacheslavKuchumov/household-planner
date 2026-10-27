# Sites deployment

The canonical application frontend is `../web/`. The earlier standalone HTML demo is obsolete and must not be used for account management.

The Site identity has been preserved in `web/.openai/hosting.json`. Build `web` and publish its `dist` using the Sites source workflow. A Scala JVM backend and PostgreSQL cannot execute inside a static Sites deployment; run `compose.yaml` on a separate host and configure the frontend/API connection as described in `docs/deployment.md`.
