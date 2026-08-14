package com.medassist.integration.faults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

@Tag("fault-nightly")
class ToxiproxyRecoveryTest {
  private static final DockerImageName UPSTREAM_IMAGE =
      DockerImageName.parse("nginx:1.27.4-alpine");
  private static final DockerImageName TOXIPROXY_IMAGE =
      DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0");
  private static final int PROXY_PORT = 8666;

  @Test
  void latencyPacketLossAndConnectionRefusalRecoverWithoutManualIntervention() throws Exception {
    assumeTrue(dockerAvailable(), "Docker is required for Toxiproxy fault injection");
    try (Network network = Network.newNetwork();
        GenericContainer<?> upstream =
            new GenericContainer<>(UPSTREAM_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("fault-upstream")
                .withExposedPorts(80)
                .waitingFor(Wait.forHttp("/").forPort(80).forStatusCode(200));
        ToxiproxyContainer toxiproxy =
            new ToxiproxyContainer(TOXIPROXY_IMAGE).withNetwork(network)) {
      upstream.start();
      toxiproxy.start();

      final ToxiproxyClient control =
          new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
      final Proxy proxy =
          control.createProxy(
              "medassist_http_dependency", "0.0.0.0:" + PROXY_PORT, "fault-upstream:80");
      final URI endpoint =
          URI.create("http://" + toxiproxy.getHost() + ':' + toxiproxy.getMappedPort(PROXY_PORT));
      final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();

      assertHealthy(client, endpoint);

      final var latency =
          proxy.toxics().latency("injected-latency", ToxicDirection.DOWNSTREAM, 750);
      assertThrows(HttpTimeoutException.class, () -> get(client, endpoint, Duration.ofMillis(200)));
      latency.remove();
      assertEventuallyHealthy(client, endpoint);

      addPacketLossToxic(toxiproxy, "medassist_http_dependency");
      assertThrows(IOException.class, () -> get(client, endpoint, Duration.ofMillis(250)));
      removeToxic(toxiproxy, "medassist_http_dependency", "injected-packet-loss");
      assertEventuallyHealthy(client, endpoint);

      proxy.disable();
      assertThrows(IOException.class, () -> get(client, endpoint, Duration.ofMillis(250)));
      proxy.enable();
      assertEventuallyHealthy(client, endpoint);
    }
  }

  private static void addPacketLossToxic(final ToxiproxyContainer toxiproxy, final String proxyName)
      throws Exception {
    final String body =
        "{\"name\":\"injected-packet-loss\",\"type\":\"packet_loss\","
            + "\"stream\":\"downstream\",\"toxicity\":1.0,"
            + "\"attributes\":{\"loss_rate\":1.0,\"correlation\":0.0}}";
    final HttpRequest request =
        HttpRequest.newBuilder(controlUri(toxiproxy, proxyName, "/toxics"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(2))
            .build();
    final HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
  }

  private static void removeToxic(
      final ToxiproxyContainer toxiproxy, final String proxyName, final String toxicName)
      throws Exception {
    final HttpRequest request =
        HttpRequest.newBuilder(controlUri(toxiproxy, proxyName, "/toxics/" + toxicName))
            .DELETE()
            .timeout(Duration.ofSeconds(2))
            .build();
    final HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
  }

  private static URI controlUri(
      final ToxiproxyContainer toxiproxy, final String proxyName, final String suffix) {
    return URI.create(
        "http://"
            + toxiproxy.getHost()
            + ':'
            + toxiproxy.getControlPort()
            + "/proxies/"
            + proxyName
            + suffix);
  }

  private static void assertHealthy(final HttpClient client, final URI endpoint) throws Exception {
    assertEquals(200, get(client, endpoint, Duration.ofSeconds(2)).statusCode());
  }

  private static void assertEventuallyHealthy(final HttpClient client, final URI endpoint)
      throws Exception {
    Exception lastFailure = null;
    for (int attempt = 0; attempt < 20; attempt++) {
      try {
        assertHealthy(client, endpoint);
        return;
      } catch (final IOException exception) {
        lastFailure = exception;
        Thread.sleep(100);
      }
    }
    throw new AssertionError("proxied dependency did not recover", lastFailure);
  }

  private static HttpResponse<String> get(
      final HttpClient client, final URI endpoint, final Duration timeout)
      throws IOException, InterruptedException {
    final HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static boolean dockerAvailable() {
    try {
      DockerClientFactory.instance().client();
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (final RuntimeException exception) {
      return false;
    }
  }
}
