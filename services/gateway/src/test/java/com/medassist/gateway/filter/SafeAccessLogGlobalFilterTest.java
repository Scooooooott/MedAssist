package com.medassist.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.medassist.gateway.config.GatewayLoggingProperties;
import com.medassist.gateway.http.ProblemResponseWriter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class SafeAccessLogGlobalFilterTest {

  @Test
  void accessLogNeverContainsRequestOrResponseBody() {
    final Logger logger = (Logger) LoggerFactory.getLogger(SafeAccessLogGlobalFilter.class);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      final SafeAccessLogGlobalFilter filter =
          new SafeAccessLogGlobalFilter(new GatewayLoggingProperties(false, false));
      final MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.post("/api/agent/answer?query=QUERY_SECRET")
                  .body("REQUEST_BODY_SECRET"));
      exchange.getAttributes().put(ProblemResponseWriter.REQUEST_ID_ATTRIBUTE, "request-1");
      exchange
          .getAttributes()
          .put(
              GATEWAY_ROUTE_ATTR,
              Route.async()
                  .id("agent")
                  .uri("http://localhost:8085")
                  .predicate(ignored -> true)
                  .build());

      filter.filter(exchange, ignored -> Mono.empty()).block();

      final String logs =
          appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
      assertFalse(logs.contains("REQUEST_BODY_SECRET"));
      assertFalse(logs.contains("QUERY_SECRET"));
      assertFalse(logs.contains("response"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void bodyLoggingConfigurationFailsClosed() {
    assertThrows(IllegalArgumentException.class, () -> new GatewayLoggingProperties(true, false));
    assertThrows(IllegalArgumentException.class, () -> new GatewayLoggingProperties(false, true));
  }
}
