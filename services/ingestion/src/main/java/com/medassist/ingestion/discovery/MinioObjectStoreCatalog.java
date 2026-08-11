package com.medassist.ingestion.discovery;

import com.medassist.ingestion.config.IngestionProperties;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class MinioObjectStoreCatalog implements ObjectStoreCatalog {
  private final IngestionProperties properties;

  public MinioObjectStoreCatalog(final IngestionProperties properties) {
    this.properties = properties;
  }

  @Override
  public List<ObjectDescriptor> listObjects() throws DiscoveryException {
    final IngestionProperties.Minio settings = properties.getMinio();
    if (settings.getAccessKey().isBlank() || settings.getSecretKey().isBlank()) {
      throw new DiscoveryPermanentException("MinIO credentials are not configured");
    }
    final MinioClient client = client(settings);
    final List<ObjectDescriptor> objects = new ArrayList<>();
    try {
      final Iterable<Result<Item>> results =
          client.listObjects(
              ListObjectsArgs.builder().bucket(settings.getBucket()).recursive(true).build());
      for (final Result<Item> result : results) {
        final Item item = result.get();
        if (item.isDir()) {
          continue;
        }
        final StatObjectResponse stat =
            client.statObject(
                StatObjectArgs.builder()
                    .bucket(settings.getBucket())
                    .object(item.objectName())
                    .build());
        final String objectName = item.objectName();
        objects.add(
            new ObjectDescriptor(
                URI.create("s3://" + settings.getBucket() + "/" + objectName),
                objectName,
                mimeType(stat.contentType(), objectName),
                item.size(),
                Map.of(
                    "etag", item.etag() == null ? "" : item.etag(),
                    "last_modified",
                        item.lastModified() == null ? "" : item.lastModified().toString()),
                () -> {
                  try {
                    return client.getObject(
                        GetObjectArgs.builder()
                            .bucket(settings.getBucket())
                            .object(objectName)
                            .build());
                  } catch (final Exception exception) {
                    throw new IOException("object stream could not be opened", exception);
                  }
                }));
      }
      return List.copyOf(objects);
    } catch (final Exception exception) {
      throw new DiscoveryTransientException("MinIO object listing failed", exception);
    }
  }

  private MinioClient client(final IngestionProperties.Minio settings) {
    return MinioClient.builder()
        .endpoint(settings.getEndpoint())
        .credentials(settings.getAccessKey(), settings.getSecretKey())
        .build();
  }

  private String mimeType(final String contentType, final String objectName) {
    if (contentType != null
        && !contentType.isBlank()
        && !"application/octet-stream".equals(contentType)) {
      return contentType;
    }
    final String lower = objectName.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".pdf")) {
      return "application/pdf";
    }
    if (lower.endsWith(".html") || lower.endsWith(".htm")) {
      return "text/html";
    }
    if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
      return "text/markdown";
    }
    if (lower.endsWith(".txt")) {
      return "text/plain";
    }
    return "application/octet-stream";
  }
}
