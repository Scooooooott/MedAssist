package com.medassist.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NonBlockingFilterArchitectureTest {
  private static final Set<String> REACTOR_BLOCKING_METHODS =
      Set.of("block", "blockOptional", "blockFirst", "blockLast", "toIterable", "toStream");

  @Test
  void gatewayFiltersDoNotCallKnownBlockingApis() {
    final var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.medassist.gateway");
    for (JavaClass item : classes) {
      if (item.getPackageName().contains(".filter")
          || item.getPackageName().contains(".ratelimit")) {
        for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
          assertFalse(isBlocking(call), call.getDescription());
        }
      }
    }
  }

  private static boolean isBlocking(final JavaMethodCall call) {
    final String owner = call.getTargetOwner().getName();
    final String method = call.getName();
    return (owner.startsWith("reactor.core.publisher.")
            && REACTOR_BLOCKING_METHODS.contains(method))
        || (owner.equals("java.lang.Thread") && method.equals("sleep"))
        || owner.startsWith("java.sql.")
        || owner.startsWith("org.springframework.jdbc.");
  }
}
