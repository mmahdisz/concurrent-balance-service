# Local fallback using Grafana's official release archive, without Docker Hub.
# This archive and checksum are for Linux amd64 (Docker Desktop on this host).
FROM quay.io/prometheus/busybox:glibc@sha256:c78ea71d5da0a24559fdc3a5eb49c04223e28dd6c6570c754395326f66d50728
USER 0
ADD https://dl.grafana.com/oss/release/grafana-12.4.13.linux-amd64.tar.gz /tmp/grafana.tar.gz
RUN echo 'f8b1683ff202940cbfab3160c9730f5797647990163624d51e607a78a59361b7  /tmp/grafana.tar.gz' | sha256sum -c - \
    && mkdir -p /usr/share/grafana /etc/grafana/provisioning/datasources /etc/grafana/provisioning/dashboards \
       /etc/grafana/provisioning/plugins /etc/grafana/provisioning/alerting /etc/grafana/provisioning/notifiers \
       /var/lib/grafana/plugins /var/lib/grafana/dashboards /var/log/grafana \
    && tar -xzf /tmp/grafana.tar.gz -C /usr/share/grafana --strip-components=1 \
    && cp /usr/share/grafana/conf/defaults.ini /etc/grafana/grafana.ini \
    && chown -R 472:472 /var/lib/grafana /var/log/grafana \
    && rm /tmp/grafana.tar.gz \
    && /usr/share/grafana/bin/grafana server --help > /dev/null
ENV GF_PATHS_HOME=/usr/share/grafana \
    GF_PATHS_CONFIG=/etc/grafana/grafana.ini \
    GF_PATHS_DATA=/var/lib/grafana \
    GF_PATHS_LOGS=/var/log/grafana \
    GF_PATHS_PLUGINS=/var/lib/grafana/plugins \
    GF_PATHS_PROVISIONING=/etc/grafana/provisioning \
    GF_LOG_MODE=console \
    HOME=/var/lib/grafana
USER 472:472
WORKDIR /usr/share/grafana
EXPOSE 3000
ENTRYPOINT ["/usr/share/grafana/bin/grafana", "server", "--homepath=/usr/share/grafana", "--config=/etc/grafana/grafana.ini"]
