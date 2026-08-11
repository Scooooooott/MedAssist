package com.medassist.retrieval.evaluation;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluations")
public final class EvaluationTrendController {
  private final EvaluationTrendService service;

  public EvaluationTrendController(final EvaluationTrendService service) {
    this.service = service;
  }

  @GetMapping("/trends")
  public List<EvaluationRunView> trends(
      @RequestParam(required = false) final String evalSetVersion,
      @RequestParam(name = "model", required = false) final String modelName,
      @RequestParam(required = false) final Integer limit) {
    return service.find(evalSetVersion, modelName, limit);
  }
}
