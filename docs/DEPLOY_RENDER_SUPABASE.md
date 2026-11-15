# Deploy manually: Render + Supabase

**Render hosts the website and Java API together. Supabase stores the data.** Share the Render URL when ready. Use free plans. Nothing has been published by this preparation; live/manual acceptance remains deferred. [Local verification](evidence/reference/hosted/2026-11-10-render-supabase.md).

## 1. Get the prepared files onto GitHub

Commit/push the deployment changes to `jeushdev/fairshare-draft`, then select that branch in Render. These files are currently local; importing the old GitHub version will not include them. Keep `.env`, edited password SQL, certificates and the unrelated `Group 2 Final Project.zip` out of Git. Agent staging/commit/push requires separate explicit approval under this project's rules.

## 2. Create the Supabase database

1. Open [Supabase](https://supabase.com/dashboard) → **New project**. Select your organization, name it `owlibi-demo`, set a private database administrator password, and choose Singapore if available. Use the **Free** plan and wait for provisioning.
2. Open **SQL Editor → New query**. Copy [bootstrap-supabase.sql](../ai-reference/hosting/bootstrap-supabase.sql).
3. Replace **all occurrences** of `REPLACE_WITH_RANDOM_MIGRATION_PASSWORD` with one randomly generated password, and all occurrences of `REPLACE_WITH_RANDOM_RUNTIME_PASSWORD` with a different one. At least 32 hexadecimal characters per password is easy to paste safely. Keep both privately; they differ from the administrator password. Run the SQL **once**, in the fresh project. Do not commit the edited copy.
4. Keep `owlibi` out of exposed Data API schemas; disable the Data API if unused. Java handles user accounts and permissions. Supabase Auth/browser API keys are not used.
5. Click **Connect** → **Session pooler**, port **5432**. Copy the actual host and project reference. Do not use transaction-pooler port 6543. See [Supabase connection guidance](https://supabase.com/docs/guides/database/connecting-to-postgres).
6. Under database SSL settings, download the CA certificate. You will paste its full PEM text into Render's secret file. [Supabase's verified-TLS example](https://supabase.com/docs/guides/database/psql) uses this certificate with the pooler.

Build this JDBC URL, replacing the host:

```text
jdbc:postgresql://YOUR_SESSION_POOLER_HOST:5432/postgres?currentSchema=owlibi&sslmode=verify-full&sslrootcert=/etc/secrets/db-ca.crt&connectTimeout=3&socketTimeout=5
```

## 3. Create one Render Web Service

Open [Render](https://dashboard.render.com) → **New → Web Service**. Connect GitHub and select `jeushdev/fairshare-draft`, using the branch containing the prepared files.

| Setting | Value |
| --- | --- |
| Name | `owlibi` or another available name |
| Language / runtime | Docker |
| Region | Singapore |
| Root directory | Leave blank |
| Dockerfile path | `ai-reference/hosting/Dockerfile.render` |
| Docker build context | `ai-reference` |
| Docker command | Leave blank |
| Instance type | **Free**, one instance |
| Health check path | `/api/v1/health` |
| Auto deploy | Off; deploy manually |

Some options appear under **Advanced**. Choose Web Service; Java also serves the website. No separate build/start commands are needed. See [Render Docker settings](https://render.com/docs/docker).

Add these **Environment Variables**:

| Key | Value |
| --- | --- |
| `OWLIBI_RUNTIME_URL` | JDBC URL above |
| `OWLIBI_RUNTIME_USER` | `owlibi_runtime.YOUR_PROJECT_REF` |
| `OWLIBI_RUNTIME_PASSWORD` | Runtime role password from your SQL |
| `OWLIBI_MIGRATION_URL` | Same JDBC URL |
| `OWLIBI_MIGRATION_USER` | `owlibi_migrator.YOUR_PROJECT_REF` |
| `OWLIBI_MIGRATION_PASSWORD` | Migration role password from your SQL |

Under **Secret Files**, add **`db-ca.crt`** containing the complete downloaded CA certificate, including the `BEGIN CERTIFICATE` and `END CERTIFICATE` lines. It is mounted at `/etc/secrets/db-ca.crt`. If this control appears only after service creation, add the file in Environment settings and manually redeploy; the initial attempt can fail while it is missing.

Let Render provide `PORT` and `RENDER_EXTERNAL_URL`. Startup automatically uses its own HTTPS URL as the allowed browser origin. Leave `OWLIBI_FRONTEND_ORIGIN` unset for the default `onrender.com` URL; a future custom domain requires setting that override to the exact custom HTTPS origin, without a trailing slash. [Render default variables](https://render.com/docs/environment-variables).

Click **Deploy Web Service**. The image builds the browser files and Java app, runs database migrations separately, then starts the website/API. Do not set `SPRING_FLYWAY_*` or change the Spring profile. Alternatively, **New → Blueprint** reads root `render.yaml`, requiring the same six credentials and CA file.

## 4. Share the Render URL

When the service reports **Live**, open `https://YOUR_SERVICE.onrender.com`. Share this URL with teammates. Users create their own Owlibi accounts; there are no seeded users/default passwords. A leader creates a project and shares its generated invitation.

One optional check is `/api/v1/health` on the same URL: `{"status":"UP"}` confirms Java reaches the migrated database. Full live/manual/accessibility testing remains unverified.

The free service can take about a minute to wake up after inactivity. Initial session checks wait up to 90 seconds; wait and refresh if necessary. Restarts/redeploys sign users out but preserve saved database records. Use demo data and export/back up anything important. [Render Free limits](https://render.com/docs/free) still apply. Earlier backup/shutdown policies are not scheduled by this setup.

Troubleshooting: `ORIGIN_INVALID` → remove a leftover Vercel `OWLIBI_FRONTEND_ORIGIN` or set it to your Render URL; certificate error → check the CA file/name and host; database authentication failure → verify each role password and `.YOUR_PROJECT_REF` suffix; deployment failure → share the first meaningful build/startup error, with secrets omitted.
