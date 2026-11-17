package com.challenge.balance.monitoring;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MonitoringServiceTest {
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private String lastRequest;

    private MonitoringService service(boolean enabled, String platform, String response, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> {
            calls.incrementAndGet();
            lastRequest = exchange.getRequestURI().toString();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new MonitoringService(enabled, platform, "http://127.0.0.1:" + server.getAddress().getPort(),
                "http://localhost:3000/d/balance-lab");
    }
    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void disabledMonitoringDoesNotCallUpstream() throws Exception {
        var report = service(false, "local", "", 200).snapshot();
        assertThat(report.enabled()).isFalse();
        assertThat(report.measurements()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test void preservesZeroTimestampsAndApprovedLabelsAndCachesBatch() throws Exception {
        var service = service(true, "docker", """
                {"status":"success","data":{"resultType":"vector","result":[
                {"metric":{"service":"app","name":"app-1","secret":"omit"},"value":[1700000000.125,"0"]}]}}
                """, 200);
        var report = service.snapshot();
        assertThat(report.measurements()).hasSize(11).allSatisfy(metric -> {
            assertThat(metric.error()).isNull();
            assertThat(metric.samples()).hasSize(1);
            var sample = metric.samples().getFirst();
            assertThat(sample.value()).isZero();
            assertThat(sample.observedAt().toEpochMilli()).isEqualTo(1700000000125L);
            assertThat(sample.labels()).containsEntry("service", "app").doesNotContainKey("secret");
        });
        assertThat(service.snapshot()).isSameAs(report);
        assertThat(calls).hasValue(11);
        assertThat(lastRequest).contains("query=", "timeout=1s", "limit=200");
    }

    @Test void nonfiniteResultsRemainUnknownAndKubernetesQueriesAreIncluded() throws Exception {
        var report = service(true, "kubernetes", """
                {"status":"success","data":{"resultType":"vector","result":[
                {"metric":{"pod":"app-1"},"value":[1700000000,"NaN"]}]}}
                """, 200).snapshot();
        assertThat(report.measurements()).hasSize(12)
                .allSatisfy(metric -> assertThat(metric.samples().getFirst().value()).isNull());
        assertThat(report.measurements()).extracting(MonitoringService.Measurement::id)
                .contains("pods", "restarts", "desiredReplicas", "readyReplicas").doesNotContain("containers");
    }

    @Test void upstreamFailureDoesNotExposeBodyOrFabricateZero() throws Exception {
        var report = service(true, "docker", "private internal failure", 503).snapshot();
        assertThat(report.measurements()).allSatisfy(metric -> {
            assertThat(metric.samples()).isEmpty();
            assertThat(metric.error()).isEqualTo("Prometheus query unavailable");
        });
        assertThat(report.toString()).doesNotContain("private internal failure");
    }

    @Test void invalidSuccessResponseIsNotTreatedAsAnEmptyHealthyResult() throws Exception {
        var report = service(true, "local", "{\"status\":\"error\"}", 200).snapshot();
        assertThat(report.measurements()).allSatisfy(metric -> assertThat(metric.error()).isNotNull());
    }

    @Test void rejectsUnsupportedPlatformAndPrometheusSchemes() {
        assertThatThrownBy(() -> new MonitoringService(true, "other", "http://localhost", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringService(true, "local", "file:///etc/passwd", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
