package com.medassist.ingestion.pipeline.grpc;

import com.medassist.ingestion.pipeline.parse.DeidentificationException;
import com.medassist.ingestion.pipeline.parse.DeidentificationPermanentException;
import com.medassist.ingestion.pipeline.parse.DeidentificationTransientException;
import com.medassist.ingestion.pipeline.parse.ParserException;
import com.medassist.ingestion.pipeline.parse.ParserPermanentException;
import com.medassist.ingestion.pipeline.parse.ParserTransientException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** Converts transport failures without copying server-provided descriptions into messages. */
final class GrpcFailureMapper {
  private GrpcFailureMapper() {}

  static ParserException parser(final StatusRuntimeException exception) {
    final Status.Code code = Status.fromThrowable(exception).getCode();
    final String message = "parser gRPC failure: " + code.name();
    return isTransient(code)
        ? new ParserTransientException(message, exception)
        : new ParserPermanentException(message, exception);
  }

  static DeidentificationException deidentification(final StatusRuntimeException exception) {
    final Status.Code code = Status.fromThrowable(exception).getCode();
    final String message = "de-identification gRPC failure: " + code.name();
    return isTransient(code)
        ? new DeidentificationTransientException(message, exception)
        : new DeidentificationPermanentException(message, exception);
  }

  static boolean isTransient(final Status.Code code) {
    return code == Status.Code.DEADLINE_EXCEEDED
        || code == Status.Code.UNAVAILABLE
        || code == Status.Code.RESOURCE_EXHAUSTED
        || code == Status.Code.ABORTED;
  }
}
