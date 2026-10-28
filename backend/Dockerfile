FROM node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293

WORKDIR /app

COPY package*.json ./
RUN npm install --silent

COPY prisma ./prisma
RUN npx prisma generate

COPY tsconfig.json ./
COPY src ./src

RUN npm run build

# Drop devDeps from the final image so vitest / supertest / @types/* don't
# bloat the runtime layer (~50 MB savings). Build artifacts in dist/ are
# already produced; npm cache is cleaned for an extra few MB.
RUN npm prune --omit=dev && npm cache clean --force

EXPOSE 3000

# No --accept-data-loss: destructive schema changes must be handled explicitly (protects customer data).
# TMPL-4: run the COMPILED seed (node dist/lib/seed.js), not `npm run seed`.
# The seed script is `tsx src/lib/seed.ts`, and `tsx` is a devDependency that
# `npm prune --omit=dev` above removes — so `npm run seed` failed with
# "tsx: not found" and the `&&` chain then never reached `npm start`, leaving
# the exported backend dead on boot. `tsc` already emitted dist/lib/seed.js at
# build time (before the prune), and `node` needs no dev deps. Seed failures
# still propagate (the `&&` keeps them fail-loud) so a broken seed surfaces
# rather than booting an unseeded backend silently.
CMD ["sh", "-c", "npx prisma db push && node dist/lib/seed.js && npm start"]
