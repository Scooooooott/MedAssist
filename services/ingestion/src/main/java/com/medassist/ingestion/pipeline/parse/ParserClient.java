package com.medassist.ingestion.pipeline.parse;

/** Narrow port for parser sidecar calls. */
@FunctionalInterface
public interface ParserClient {
  ParserResponse parse(ParserRequest request) throws ParserException;
}
