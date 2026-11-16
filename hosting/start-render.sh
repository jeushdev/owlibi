#!/bin/sh
set -eu
umask 077
# Render serves the website and API together; its public URL is the browser origin.
# An explicit override remains available for a custom domain.
export OWLIBI_FRONTEND_ORIGIN="${OWLIBI_FRONTEND_ORIGIN:-${RENDER_EXTERNAL_URL:-}}"
: "${OWLIBI_FRONTEND_ORIGIN:?Set the service HTTPS origin, without a trailing slash}"
: "${OWLIBI_RUNTIME_URL:?Set the runtime JDBC URL with verified TLS}"
: "${OWLIBI_RUNTIME_USER:?Set the runtime database user}"
: "${OWLIBI_RUNTIME_PASSWORD:?Set the runtime database password}"
: "${OWLIBI_MIGRATION_URL:?Set the migration JDBC URL with verified TLS}"
: "${OWLIBI_MIGRATION_USER:?Set the migration database user}"
: "${OWLIBI_MIGRATION_PASSWORD:?Set the migration database password}"
case "${PORT:-10000}" in ''|*[!0-9]*) echo 'Invalid PORT' >&2; exit 78;; esac
if [ "${PORT:-10000}" -lt 1024 ] || [ "${PORT:-10000}" -gt 65535 ]; then
    echo 'PORT must be between 1024 and 65535' >&2; exit 78
fi

# One small JVM finishes migrations before the web service opens its port.
java -Xms32m -Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 \
    -Dloader.main=dev.owlibi.reference.hosting.MigrationMain \
    -cp /opt/owlibi/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
unset OWLIBI_MIGRATION_URL OWLIBI_MIGRATION_USER OWLIBI_MIGRATION_PASSWORD
unset SPRING_FLYWAY_URL SPRING_FLYWAY_USER SPRING_FLYWAY_PASSWORD

# Leave room for native memory in Render Free's 512 MiB container.
exec java -Xms32m -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError \
    -Djava.io.tmpdir=/var/lib/owlibi/tmp -jar /opt/owlibi/app.jar \
    --spring.profiles.active=hosted --spring.main.banner-mode=off \
    --spring.lifecycle.timeout-per-shutdown-phase=30s
