# RootForge OS toolkit image: CLI + host adb/fastboot + headless API.
# USB access is a run-time concern (see docker/README.md), not baked in.
#
# Build from the repository root:
#   docker build -f docker/Dockerfile -t rootforge-os-toolkit .
FROM debian:bookworm-slim

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        python3 \
        python3-yaml \
        adb \
        fastboot \
        android-sdk-libsparse-utils \
        jq \
        ca-certificates \
        usbutils \
    && rm -rf /var/lib/apt/lists/*

COPY config/includes.chroot/usr/local /usr/local
COPY docker/api /opt/rootforge-api
COPY docker/web /opt/rootforge-web
COPY docker/entrypoint.sh /opt/rootforge-api/entrypoint.sh

RUN chmod 0755 /usr/local/bin/rootforge /opt/rootforge-api/entrypoint.sh /opt/rootforge-api/rootforge_api.py \
    && mkdir -p /root/rootforge

ENV PYTHONPATH=/usr/local/lib:/opt/rootforge-api \
    PATH=/usr/local/bin:/usr/bin:/bin \
    ROOTFORGE_HOME=/root/rootforge \
    ROOTFORGE_WEB=/opt/rootforge-web/index.html \
    ROOTFORGE_API_BIND=0.0.0.0 \
    ROOTFORGE_API_PORT=8787

EXPOSE 8787
WORKDIR /root
ENTRYPOINT ["/opt/rootforge-api/entrypoint.sh"]
