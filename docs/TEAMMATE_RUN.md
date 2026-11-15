# Run Owlibi on your computer

Use the same Owlibi source snapshot as your teammates. If you received a ZIP, extract it to a normal writable folder, such as `C:\\Projects\\Owlibi` or `~/Projects/Owlibi`, and open a terminal in the extracted folder that contains `package.json`.

## Install once

1. Install a JDK 21 or newer and Node.js 24 or newer with npm 11 or newer for your operating system. Use maintained releases with current security patches; no particular Java vendor or exact patch version is required.
2. Install Docker Desktop for [Windows](https://docs.docker.com/desktop/setup/install/windows-install/) or [Mac](https://docs.docker.com/desktop/setup/install/mac-install/). On Windows use the WSL 2 Linux engine. Open Docker Desktop and wait for the engine to start.
3. Open a new terminal. Check `java -version`, `javac -version`, `node --version`, `npm --version` (Windows: `npm.cmd --version`), and `docker compose version`. The launcher uses these installed tools through `PATH`; standard `JAVA_HOME` can select the JDK.

You can use any editor or IDE. The checked-in Maven Wrapper downloads Maven 3.9.16 and checks its checksum. Docker starts PostgreSQL. The launcher builds and serves the browser UI with Spring Boot.

## Start Owlibi

**Windows (PowerShell)**

```powershell
npm.cmd run dev
```

**Mac (Terminal)**

```sh
npm run dev
```

Keep that terminal open. Once Spring reports that it started, open [Owlibi](http://127.0.0.1:8080/) and create an account with demo-only information. The first run needs internet access for Maven and the PostgreSQL image. Later runs can reuse the built application when the source is unchanged.

The launcher creates a private `ai-reference/.env` and assigns a separate local database volume for each fresh copy. Keep that file with your copy to retain access to its database. Never share the file or the volume. Ctrl+C stops Java. The database stays on your computer and your data is available after restarting. To stop the database container too, run `docker compose --env-file .env stop db` from the `ai-reference` folder with your normal local Docker context. The volume is retained.

## If startup fails

| What you see | What to do |
| --- | --- |
| Missing or outdated Java, Node, or npm | Install JDK 21+, Node 24+ and npm 11+, then open a new terminal. Set standard `JAVA_HOME` or update `PATH` if several installations exist. Old `OWLIBI_JAVA_HOME`/`OWLIBI_NODE_HOME` entries in `.env` are ignored. |
| Docker Linux engine unavailable | Start Docker Desktop and wait. Check that `docker info --format '{{.OSType}}'` prints `linux`. On Windows follow Docker’s WSL 2 instructions if the engine still fails. |
| Port 8080 or 55580 in use | If an Owlibi copy is already running, stop it or open its URL. Otherwise set unused ports in your private `.env`, for example `OWLIBI_API_PORT=8081` and `OWLIBI_DB_PORT=55581`. Open the new API port in your browser. |
| Maven or image download failed | Check your internet connection and retry the startup command. The first build can take several minutes. |
| Database authentication error | Use the original `.env` paired with this copy’s database. If you inherited a copy with a placeholder `.env`, use a fresh extraction without that file. Preserve existing volumes and credentials when troubleshooting. |

Each teammate gets a separate localhost installation with separate accounts and data. To demo leader and member workflows, create two demo accounts on **one** installation using separate browser profiles and redeem an invitation there.

## Find the Java OOP code

Open `ai-reference/pom.xml` as a Maven project and select the pinned JDK. The backend is in `ai-reference/server-api/src/main/java/dev/owlibi/reference/`. Start with `projects` for creation and membership, `tasks` for versioned task rules, `security` for sessions and access checks, and `snapshots`, `summaries`, and `reports` for coherent reads, calculations, and exports. `templates` supplies the data-driven catalogue. The frontend is under `ai-reference/web-client/src/`.

Use `npm run test:launcher` (Windows `npm.cmd run test:launcher`) for the fast startup checks. The complete project check is `npm run verify` (Windows `npm.cmd run verify`). It uses separate disposable fixtures and downloads the pinned test browser. A successful startup establishes that the app opens on that machine. See `ai-reference/AI_USAGE.md` for the AI-assisted reference and library disclosures.
