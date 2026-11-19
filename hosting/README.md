# Hosting files

Used by the Render + Supabase deployment. Follow [the manual deploy guide](../docs/DEPLOY_RENDER_SUPABASE.md).

| File | Purpose |
| --- | --- |
| `Dockerfile.render` | Builds the browser files and Java app into one image. Docker context is the repository root. |
| `start-render.sh` | Validates settings, runs database migrations, then starts the website/API. |
| `bootstrap-supabase.sql` | One-time SQL for a fresh Supabase project: schema and two limited roles. |
