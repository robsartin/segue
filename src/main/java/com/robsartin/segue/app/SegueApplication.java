package com.robsartin.segue.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point.
 *
 * <p>The banner is off and every appender targets stderr, because on the stdio transport stdout
 * carries the MCP protocol and a single stray line corrupts it. See docs/adr/0028-mcp-transports.md
 * — this is enforced by an ArchUnit rule and a stdout-purity integration test, not by remembering.
 */
@SpringBootApplication(scanBasePackages = "com.robsartin.segue")
public class SegueApplication {

  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(SegueApplication.class);
    application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
    application.run(args);
  }
}
