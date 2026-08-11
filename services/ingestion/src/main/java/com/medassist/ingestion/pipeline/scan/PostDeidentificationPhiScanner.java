package com.medassist.ingestion.pipeline.scan;

import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Fail-closed residual-PHI scan performed immediately before indexing. */
public final class PostDeidentificationPhiScanner {
  private static final String INVALID_INPUT_MESSAGE = "PHI scan input is invalid";
  private static final String INVALID_RESPONSE_MESSAGE = "PHI detection returned no response";
  private static final String CLIENT_FAILURE_MESSAGE = "PHI detection client failure";

  private final PhiDetectionPort detectionPort;

  public PostDeidentificationPhiScanner(final PhiDetectionPort detectionPort) {
    this.detectionPort = Objects.requireNonNull(detectionPort, "detectionPort");
  }

  public PostDeidentificationPhiScan scan(final List<String> textFragments, final Duration timeout)
      throws PhiDetectionException {
    if (textFragments == null || timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new PhiDetectionPermanentException(INVALID_INPUT_MESSAGE);
    }

    final Set<String> entityTypes = new TreeSet<>();
    for (final String textFragment : textFragments) {
      if (textFragment == null) {
        throw new PhiDetectionPermanentException(INVALID_INPUT_MESSAGE);
      }

      final PhiDetectionResponse response;
      try {
        response = detectionPort.detect(new PhiDetectionRequest(textFragment, timeout));
      } catch (final PhiDetectionTransientException exception) {
        throw new PhiDetectionTransientException("PHI detection failed transiently");
      } catch (final PhiDetectionException exception) {
        throw new PhiDetectionPermanentException("PHI detection failed permanently");
      } catch (final RuntimeException exception) {
        throw new PhiDetectionPermanentException(CLIENT_FAILURE_MESSAGE);
      }
      if (response == null) {
        throw new PhiDetectionPermanentException(INVALID_RESPONSE_MESSAGE);
      }
      entityTypes.addAll(response.entityTypes());
    }
    return new PostDeidentificationPhiScan(
        entityTypes.isEmpty() ? PhiScanStatus.CLEAN : PhiScanStatus.SUSPECT, entityTypes);
  }
}
