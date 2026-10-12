# The PostgreSQL 16.4 patch tag pins the client/server toolchain used for
# pg_dump and pg_restore. No private age identity is copied into this image.
FROM postgres:16.4-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        age \
        awscli \
        ca-certificates \
        jq \
    && rm -rf /var/lib/apt/lists/*

COPY ops/postgres-backup.sh /usr/local/bin/easydoc-postgres-backup
COPY ops/reapply-deletions.sh /usr/local/bin/easydoc-reapply-deletions
RUN chmod 0555 /usr/local/bin/easydoc-postgres-backup /usr/local/bin/easydoc-reapply-deletions

ENTRYPOINT ["/bin/sh", "/usr/local/bin/easydoc-postgres-backup"]
