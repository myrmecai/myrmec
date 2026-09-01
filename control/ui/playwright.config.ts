import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright configuration for the Myrmec control UI end-to-end tests.
 *
 * <p>The harness spawns two long-running processes via Playwright's
 * {@code webServer} support:
 * <ol>
 *   <li>The Spring Boot engine on port 9090 with the {@code e2e} profile
 *       (H2 in-memory DB, auto-bootstrapped admin user).</li>
 *   <li>The Vite dev server on port 3000.</li>
 * </ol>
 *
 * <p>Both servers are kept alive for the entire run so the full spec suite
 * pays the engine boot cost (~20s) once, not per spec. When running locally,
 * already-running servers are reused.
 *
 * <p>The {@code globalSetup} script is intentionally optional in Phase 4a;
 * later phases that need seeded fixtures (projects, KB docs, workflows) will
 * register one alongside the engine startup.
 */
export default defineConfig({
  testDir: './e2e/specs',
  fullyParallel: false, // Single shared engine + single H2 DB ⇒ serialise to avoid cross-test bleed.
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: [
    {
      // Invoke spring-boot:run directly from the engine-core module rather than
      // from the multi-module root with -pl. With spring-boot-starter-parent
      // inherited, the spring-boot-maven-plugin is resolvable on EVERY project
      // in the reactor — running it from the parent dir invokes it on the parent
      // pom (no mainClass ⇒ failure). Running from inside engine-core/ scopes
      // it correctly. The plain library jar produced by `mvn install` (Phase 3
      // companion change) is what downstream Maven consumers use; only the
      // executable jar carries the `exec` classifier.
      command: 'mvn spring-boot:run "-Dspring-boot.run.profiles=e2e"',
      cwd: '../engine/engine-core',
      url: 'http://localhost:9090/actuator/health',
      timeout: 180_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: 'npm run dev',
      url: 'http://localhost:3000',
      timeout: 60_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
  globalTeardown: './e2e/global-teardown.ts',
})
