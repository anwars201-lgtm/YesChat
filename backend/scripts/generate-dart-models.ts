import { generatorHandler } from '@prisma/generator-helper';
import fs from 'fs';
import path from 'path';

import { generateDartModels, type PreservedDartModel } from './lib/dart-models';

// Registered UNCONDITIONALLY (no `require.main` guard): Prisma spawns this file
// as a child process for every `prisma generate`, and a guard that some future
// loader mode evaluated as false would silently turn Dart model generation off
// for every Flutter project. Invoked directly (no Prisma parent, stdin at EOF)
// this registers the JSON-RPC listener and exits without generating — models
// always come from the `prisma generate` / `prisma db push` run just before.
generatorHandler({
  onManifest() {
    return {
      version: '1.0.0',
      defaultOutput: '../../app/lib/models',
      prettyName: 'Prisma Dart Models Generator',
    };
  },
  async onGenerate(options) {
    // Stack guard: this generator emits Flutter Dart models into app/lib/models.
    // A native build (kotlin/swift) has no Dart layer — emitting .dart here creates
    // app/lib and pollutes the workspace (duplicate models alongside the native ones,
    // and the stray .dart historically poisoned the Flutter wiring/finalize gates).
    // `npx prisma generate` (required for the Prisma client in Todo 1) would otherwise
    // trigger this on every native build. APP_STACK unset/flutter → generate as before
    // (byte-identical Flutter behaviour).
    const stack = (process.env.APP_STACK || 'flutter').toLowerCase();
    if (stack !== 'flutter') {
      console.log(
        `[generate-dart-models] APP_STACK=${stack} — skipping Dart model generation (native stack has no app/lib/).`,
      );
      return;
    }

    const outputDir = options.generator.output?.value || path.join(__dirname, '../../../app/lib/models');

    // Starter workspaces ship hand-written model files (see lib/dart-models.ts);
    // everywhere else the generator overwrites exactly as it always has.
    const starterTemplateId = process.env.STARTER_TEMPLATE_ID || '';
    const preserveHandWritten = starterTemplateId.length > 0;

    const result = generateDartModels(options.dmmf.datamodel.models, outputDir, {
      preserveHandWritten,
      // Prisma runs generators with stdout INHERITED and stderr as its JSON-RPC
      // channel: a non-JSON stderr line is swallowed unless the generator
      // crashes. Log to stdout so the warning shows in `prisma generate`
      // output and in the container boot log.
      log: { info: (message) => console.log(message), warn: (message) => console.log(message) },
    });

    if (preserveHandWritten) {
      writePreservedReport(outputDir, starterTemplateId, result.preserved);
    }
  },
});

/**
 * `<workspace>/.appbuilder/dart-models-preserved.json` — machine-readable
 * twin of the PRESERVED log line for the boot smoke + support diagnostics.
 * Written on every starter-workspace run (an empty list is a valid report).
 */
function writePreservedReport(outputDir: string, starterTemplateId: string, preserved: PreservedDartModel[]): void {
  try {
    // outputDir is <workspace>/app/lib/models
    const workspaceRoot = path.resolve(outputDir, '..', '..', '..');
    const reportDir = path.join(workspaceRoot, '.appbuilder');
    fs.mkdirSync(reportDir, { recursive: true });
    fs.writeFileSync(
      path.join(reportDir, 'dart-models-preserved.json'),
      JSON.stringify({ version: 1, starterTemplateId, generatedAt: new Date().toISOString(), preserved }, null, 2) + '\n',
    );
  } catch (error) {
    console.log(`[generate-dart-models] could not write dart-models-preserved.json: ${String(error)}`);
  }
}
