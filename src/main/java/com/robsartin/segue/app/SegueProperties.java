package com.robsartin.segue.app;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param database where the assertion log lives — a single file, per ADR 24
 * @param maxNewEdges default bound on one expansion
 */
@ConfigurationProperties(prefix = "segue")
public record SegueProperties(Path database, int maxNewEdges) {

  public SegueProperties {
    if (maxNewEdges <= 0) {
      maxNewEdges = 200;
    }
  }
}
