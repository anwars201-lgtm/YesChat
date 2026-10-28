import { PrismaClient } from '@prisma/client';

// Limit connection pool to 5 per container to prevent DB exhaustion at scale
const dbUrl = process.env.DATABASE_URL || '';
const pooledUrl = dbUrl.includes('connection_limit') ? dbUrl : `${dbUrl}${dbUrl.includes('?') ? '&' : '?'}connection_limit=5`;

const prisma = new PrismaClient({
  datasources: { db: { url: pooledUrl } },
});

export { prisma };
