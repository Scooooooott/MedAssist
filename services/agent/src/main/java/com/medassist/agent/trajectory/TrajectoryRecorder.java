package com.medassist.agent.trajectory;

import java.util.List;

public interface TrajectoryRecorder {
  void record(TrajectoryEvent event);

  List<TrajectoryEvent> events(String traceId);
}
