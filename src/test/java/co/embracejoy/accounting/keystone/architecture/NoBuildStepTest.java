package co.embracejoy.accounting.keystone.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces ADR-0021 (server-rendered admin UI, no JS build step): fails the build if a JS toolchain
 * (npm/yarn/pnpm) is introduced at the repo root.
 *
 * <p>This is a plain {@code @Test}, not an {@code @ArchTest}, because it reads the filesystem
 * rather than inspecting compiled bytecode — ArchUnit has nothing to say about repo topology. Maven
 * runs tests with the working directory at the project root, so the relative paths below resolve
 * correctly.
 */
class NoBuildStepTest {

  @Test
  @DisplayName("no package.json exists at repo root (ADR-0021)")
  void shouldHaveNoPackageJson() {
    assertThat(Files.exists(Paths.get("package.json"))).isFalse();
    assertThat(Files.exists(Paths.get("node_modules"))).isFalse();
  }
}
