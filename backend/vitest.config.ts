import { defineConfig } from 'vitest/config';

// Vitest with dual-target test layout:
//   - test/**          — integration tests (boot prisma + DB schema, see global-setup.ts)
//   - src/**/__tests__ — unit tests (pure logic, no I/O dependencies)
//
// We project-split so unit tests can run without a Postgres connection.
// Run all tests:    `npx vitest run`
// Only unit tests:  `npx vitest run --project unit`
export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    pool: 'forks',
    // Vitest 4 removed poolOptions.forks.singleFork. These supported options
    // preserve deterministic DB integration ordering without relying on a
    // deprecated setting that Vitest silently ignores.
    fileParallelism: false,
    maxWorkers: 1,
    testTimeout: 15_000,
    hookTimeout: 15_000,
    projects: [
      {
        extends: true,
        test: {
          name: 'integration',
          include: ['test/**/*.test.ts'],
          globalSetup: ['./test/global-setup.ts'],
          setupFiles: ['./test/setup.ts'],
        },
      },
      {
        extends: true,
        test: {
          name: 'unit',
          include: ['src/**/__tests__/*.test.ts'],
        },
      },
    ],
  },
});
