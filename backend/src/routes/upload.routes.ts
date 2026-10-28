import { Router, Request, Response } from 'express';
import fs from 'fs';
import { upload } from '../middleware/upload.middleware';
import { authMiddleware } from '../middleware/auth.middleware';
import { AppError } from '../middleware/error.middleware';
import { getPublicBaseUrl } from '../lib/base-url';

const router = Router();

// /workspace/uploads inside a customer container (unchanged); UPLOAD_DIR only
// exists so the template's own test suite can run outside a container (CI,
// a developer machine), where /workspace is not creatable.
const UPLOAD_DIR = process.env.UPLOAD_DIR || '/workspace/uploads';

// Ensure upload directory exists
if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
}

// POST /upload — upload a single image file
router.post('/', authMiddleware, (req: Request, res: Response, next: Function) => {
  upload(req, res, (err: unknown) => {
    if (err) {
      const errObj = err as Record<string, unknown>;
      const errorMessage = err instanceof Error ? err.message : String(err);
      if (errObj?.code === 'LIMIT_FILE_SIZE') {
        return next(new AppError(400, 'File too large. Maximum 5MB.'));
      }
      return next(new AppError(400, errorMessage || 'Upload failed'));
    }

    if (!req.file) {
      return next(new AppError(400, 'No file provided'));
    }

    // TMPL-3: return an ABSOLUTE URL when PUBLIC_BASE_URL is configured. The
    // client renders this straight into an <img> / CachedNetworkImage, which
    // cannot resolve a bare `/uploads/...` relative path against the API host,
    // so the image was broken. Falls back to the relative path when
    // PUBLIC_BASE_URL is unset (byte-identical to the previous behaviour).
    const baseUrl = getPublicBaseUrl();
    const relativePath = `/uploads/${req.file.filename}`;
    const url = baseUrl ? `${baseUrl}${relativePath}` : relativePath;
    res.json({ url, filename: req.file.filename });
  });
});

export default router;
