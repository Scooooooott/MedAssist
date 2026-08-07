package com.medassist.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

class InfrastructureSmokeTest {
  private static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");
  private static final DockerImageName MINIO_IMAGE =
      DockerImageName.parse("minio/minio:RELEASE.2025-07-23T15-54-02Z");
  private static final DockerImageName MINIO_CLIENT_IMAGE =
      DockerImageName.parse("minio/mc:RELEASE.2025-07-21T05-28-08Z");

  @Test
  void postgresStartsWithVectorAndPgcryptoExtensions() throws Exception {
    assumeTrue(dockerAvailable(), "Docker is required for integration smoke tests");
    try (PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("medassist")
            .withUsername("medassist")
            .withPassword("medassist")) {
      postgres.start();
      try (var connection =
              DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
        statement.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        try (var results =
            statement.executeQuery(
                "SELECT COUNT(*) FROM pg_extension WHERE extname IN ('vector', 'pgcrypto')")) {
          assertTrue(results.next());
          assertEquals(2, results.getInt(1));
        }
      }
    }
  }

  @Test
  void minioCreatesRequiredBucketsAndEnablesRawDocumentVersioning() throws Exception {
    assumeTrue(dockerAvailable(), "Docker is required for integration smoke tests");
    try (Network network = Network.newNetwork();
        GenericContainer<?> minio =
            new GenericContainer<>(MINIO_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("minio")
                .withEnv("MINIO_ROOT_USER", "minioadmin")
                .withEnv("MINIO_ROOT_PASSWORD", "minioadmin123")
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).forStatusCode(200));
        GenericContainer<?> minioClient =
            new GenericContainer<>(MINIO_CLIENT_IMAGE)
                .withNetwork(network)
                .dependsOn(minio)
                .withCreateContainerCmdModifier(command -> command.withEntrypoint("/bin/sh", "-c"))
                .withCommand(
                    "mc alias set local http://minio:9000 minioadmin minioadmin123 "
                        + "&& mc mb --ignore-existing local/raw-documents "
                        + "&& mc mb --ignore-existing local/processed "
                        + "&& mc mb --ignore-existing local/artifacts "
                        + "&& mc version enable local/raw-documents "
                        + "&& mc version info local/raw-documents")
                .withStartupCheckStrategy(
                    new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
      minio.start();
      minioClient.start();

      assertEquals(0L, minioClient.getCurrentContainerInfo().getState().getExitCodeLong());
      assertTrue(minioClient.getLogs().contains("raw-documents"));
    }
  }

  private static boolean dockerAvailable() {
    try {
      DockerClientFactory.instance().client();
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (RuntimeException exception) {
      return false;
    }
  }
}
