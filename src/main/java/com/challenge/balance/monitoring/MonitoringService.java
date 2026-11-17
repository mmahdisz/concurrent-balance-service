package com.challenge.balance.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Bounded read-only evidence API. Callers cannot supply queries, URLs, or credentials. */
@Service
public class MonitoringService {
    public record Sample(Map<String, String> labels, Double value, Instant observedAt) {}
    public record Measurement(String id, String unit, List<Sample> samples, String error) {}
    public record Report(Instant capturedAt, boolean enabled, String platform, String grafanaUrl,
                         List<Measurement> measurements, String message) {}
    private record Query(String id, String unit, String expression) {}
    private final boolean enabled;
    private final String platform;
    private final String grafanaUrl;
    private final RestClient client;
    private Report cached;

    public MonitoringService(@Value("${balance.monitoring.enabled:false}") boolean enabled,
            @Value("${balance.monitoring.platform:local}") String platform,
            @Value("${balance.monitoring.prometheus-url:http://localhost:9090}") String prometheusUrl,
            @Value("${balance.monitoring.grafana-url:http://localhost:3000/d/balance-lab}") String grafanaUrl) {
        this.enabled = enabled;
        if (!Set.of("local", "docker", "kubernetes").contains(platform))
            throw new IllegalArgumentException("Monitoring platform must be local, docker, or kubernetes");
        this.platform = platform;
        URI uri = URI.create(prometheusUrl);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null)
            throw new IllegalArgumentException("Prometheus URL must be HTTP(S)");
        URI dashboard = URI.create(grafanaUrl);
        this.grafanaUrl = Set.of("http", "https").contains(Objects.toString(dashboard.getScheme(), ""))
                && dashboard.getHost() != null && dashboard.getUserInfo() == null ? grafanaUrl : "";
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = RestClient.builder().baseUrl(prometheusUrl).requestFactory(factory).build();
    }

    public synchronized Report snapshot() {
        if (cached != null && cached.capturedAt().isAfter(Instant.now().minusSeconds(5))) return cached;
        Instant now = Instant.now();
        if (!enabled) return new Report(now, false, platform, "", List.of(), "Monitoring is not configured for this deployment.");
        List<Query> queries = new ArrayList<>(List.of(
                new Query("targets", "up", "up{job=~\"balance-app|prometheus|cadvisor|kube-state-metrics\"}"),
                new Query("requests", "req/s", "sum(rate(http_server_requests_seconds_count{job=\"balance-app\",uri!~\"/actuator.*|/api/monitoring\"}[2m]))"),
                new Query("errors", "errors/s", "sum(rate(http_server_requests_seconds_count{job=\"balance-app\",status=~\"5..\",uri!~\"/actuator.*|/api/monitoring\"}[2m]))"),
                new Query("latency", "seconds", "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"balance-app\",uri!~\"/actuator.*|/api/monitoring\"}[2m])))"),
                new Query("heap", "bytes", "sum(jvm_memory_used_bytes{job=\"balance-app\",area=\"heap\"})"),
                new Query("connections", "connections", "sum(hikaricp_connections_active{job=\"balance-app\"})"),
                // Each application instance observes the same shared queue.
                new Query("outbox", "events", "max(balance_kafka_outbox_pending{job=\"balance-app\"})"),
                new Query("publishFailures", "errors/s", "sum(rate(balance_kafka_outbox_failures_total{job=\"balance-app\"}[2m]))")));
        if (platform.equals("docker")) queries.addAll(List.of(
                new Query("containers", "unix seconds", "max by (service, name) (container_last_seen{job=\"cadvisor\",service!=\"\"})"),
                new Query("containerCpu", "cores", "sum by (service, name) (rate(container_cpu_usage_seconds_total{job=\"cadvisor\",service!=\"\"}[2m]))"),
                new Query("containerMemory", "bytes", "sum by (service, name) (container_memory_working_set_bytes{job=\"cadvisor\",service!=\"\"})")));
        if (platform.equals("kubernetes")) queries.addAll(List.of(
                new Query("pods", "ready", "kube_pod_status_ready{job=\"kube-state-metrics\",condition=\"true\"}"),
                new Query("restarts", "restarts", "sum by (pod) (kube_pod_container_status_restarts_total{job=\"kube-state-metrics\"})"),
                new Query("desiredReplicas", "replicas", "label_replace(kube_deployment_spec_replicas{job=\"kube-state-metrics\"},\"workload\",\"$1\",\"deployment\",\"(.*)\") or label_replace(kube_statefulset_replicas{job=\"kube-state-metrics\"},\"workload\",\"$1\",\"statefulset\",\"(.*)\")"),
                new Query("readyReplicas", "replicas", "label_replace(kube_deployment_status_replicas_available{job=\"kube-state-metrics\"},\"workload\",\"$1\",\"deployment\",\"(.*)\") or label_replace(kube_statefulset_status_replicas_ready{job=\"kube-state-metrics\"},\"workload\",\"$1\",\"statefulset\",\"(.*)\")")));
        List<Measurement> measurements = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = queries.stream().map(q -> executor.submit(() -> query(q))).toList();
            for (int i = 0; i < futures.size(); i++) {
                try { measurements.add(futures.get(i).get()); }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Monitoring interrupted", ex);
                } catch (ExecutionException ex) {
                    measurements.add(new Measurement(queries.get(i).id(), queries.get(i).unit(), List.of(), "Metric unavailable"));
                }
            }
        }
        return cached = new Report(now, true, platform, grafanaUrl, List.copyOf(measurements),
                "Prometheus observations; missing samples are unknown, not zero. Scrape interval: 15 seconds.");
    }

    private Measurement query(Query query) {
        try {
            JsonNode root = client.get().uri(builder -> builder.path("/api/v1/query")
                    .queryParam("query", "{expression}").queryParam("timeout", "1s").queryParam("limit", "200")
                    .build(query.expression())).retrieve().body(JsonNode.class);
            if (root == null || !root.path("status").asText().equals("success")
                    || !root.path("data").path("resultType").asText().equals("vector"))
                throw new IllegalStateException("Invalid Prometheus response");
            List<Sample> samples = new ArrayList<>();
            for (JsonNode result : root.path("data").path("result")) {
                if (samples.size() == 200) break;
                Map<String, String> labels = new TreeMap<>();
                result.path("metric").fields().forEachRemaining(e -> {
                    if (Set.of("job", "instance", "service", "name", "pod", "workload").contains(e.getKey()))
                        labels.put(e.getKey(), e.getValue().asText());
                });
                double value = Double.parseDouble(result.path("value").path(1).asText());
                long timestamp = (long) (result.path("value").path(0).asDouble() * 1000);
                samples.add(new Sample(Map.copyOf(labels), Double.isFinite(value) ? value : null, Instant.ofEpochMilli(timestamp)));
            }
            return new Measurement(query.id(), query.unit(), List.copyOf(samples), null);
        } catch (RuntimeException ex) {
            // Do not expose internal addresses, credentials, or upstream response bodies.
            return new Measurement(query.id(), query.unit(), List.of(), "Prometheus query unavailable");
        }
    }
}
