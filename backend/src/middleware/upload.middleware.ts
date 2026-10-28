import multer, { FileFilterCallback } from 'multer';
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { Request, Response, NextFunction, RequestHandler } from 'express';

// /workspace/uploads inside a customer container (unchanged); UPLOAD_DIR only
// exists so the template's own test suite can run outside a container (CI,
// a developer machine), where /workspace is not creatable.
const UPLOAD_DIR = process.env.UPLOAD_DIR || '/workspace/uploads';
const MAX_SIZE = 5 * 1024 * 1024; // 5MB
const ALLOWED_MIMES: Record<string, string> = {
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/gif': '.gif',
  'image/webp': '.webp',
};

// TMPL-6d: the client-declared Content-Type is untrusted. The mobile client
// sends real image bytes as `application/octet-stream` (image_service.dart), so
// the FILTER must accept it alongside the four image types — but the REAL gate
// is the post-write magic-byte check below, which unlinks anything that is not
// actually one of the four image formats.
const FILTER_ALLOWED_MIMES = new Set([...Object.keys(ALLOWED_MIMES), 'application/octet-stream']);

const EXT_FOR_TYPE: Record<string, string> = {
  jpg: '.jpg',
  png: '.png',
  gif: '.gif',
  webp: '.webp',
};

/** Real-image signatures — checked against the first bytes on disk. */
const IMAGE_MAGIC: Array<{ type: keyof typeof EXT_FOR_TYPE; test: (b: Buffer) => boolean }> = [
  { type: 'jpg', test: (b) => b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff },
  { type: 'png', test: (b) => b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47 },
  { type: 'gif', test: (b) => b.length >= 4 && b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x38 },
  {
    type: 'webp',
    // 'RIFF' .... 'WEBP'
    test: (b) =>
      b.length >= 12 &&
      b[0] === 0x52 &&
      b[1] === 0x49 &&
      b[2] === 0x46 &&
      b[3] === 0x46 &&
      b[8] === 0x57 &&
      b[9] === 0x45 &&
      b[10] === 0x42 &&
      b[11] === 0x50,
  },
];

function sniffImageType(filePath: string): keyof typeof EXT_FOR_TYPE | null {
  let fd: number | null = null;
  try {
    fd = fs.openSync(filePath, 'r');
    const buf = Buffer.alloc(12);
    fs.readSync(fd, buf, 0, 12, 0);
    for (const sig of IMAGE_MAGIC) {
      if (sig.test(buf)) return sig.type;
    }
    return null;
  } catch {
    return null;
  } finally {
    if (fd !== null) {
      try {
        fs.closeSync(fd);
      } catch {
        /* best effort */
      }
    }
  }
}

const storage = multer.diskStorage({
  destination: (_req, _file, cb) => {
    cb(null, UPLOAD_DIR);
  },
  filename: (_req, file, cb) => {
    // Provisional extension from the declared type; the magic-byte pass below
    // renames to the sniffed real extension (octet-stream lands here as .bin).
    const ext = ALLOWED_MIMES[file.mimetype] || '.bin';
    const name = crypto.randomBytes(16).toString('hex');
    cb(null, `${name}${ext}`);
  },
});

function fileFilter(_req: Request, file: Express.Multer.File, cb: FileFilterCallback) {
  if (FILTER_ALLOWED_MIMES.has(file.mimetype)) {
    cb(null, true);
  } else {
    cb(new Error('Only image files are allowed (jpeg, png, gif, webp)'));
  }
}

const multerInstance = multer({
  storage,
  limits: { fileSize: MAX_SIZE },
  fileFilter,
});

function unlinkQuietly(filePath: string | undefined): void {
  if (!filePath) return;
  try {
    fs.unlinkSync(filePath);
  } catch {
    /* best effort */
  }
}

/**
 * Post-write validation: for every file multer just wrote, sniff its magic
 * bytes. A file whose bytes are not one of the four real image formats is
 * unlinked and the request rejected — this is what actually stops a script /
 * SVG / executable smuggled in under a spoofed Content-Type. When the bytes ARE
 * a valid image but the on-disk extension disagrees (the octet-stream `.bin`
 * case, or a lying declared type), the file is renamed to the correct
 * extension so the served URL is right. Opt out with UPLOAD_MAGIC_BYTE_CHECK=false.
 */
function validateWrittenFiles(req: Request): { ok: true } | { ok: false; message: string } {
  if (process.env.UPLOAD_MAGIC_BYTE_CHECK === 'false') return { ok: true };
  const files: Express.Multer.File[] = req.file ? [req.file] : Array.isArray(req.files) ? req.files : [];
  for (const file of files) {
    const realType = sniffImageType(file.path);
    if (!realType) {
      // Not a real image — remove every file from this request and reject.
      for (const f of files) unlinkQuietly(f.path);
      return { ok: false, message: 'Uploaded file is not a valid image (jpeg, png, gif, webp)' };
    }
    const wantExt = EXT_FOR_TYPE[realType];
    if (path.extname(file.filename).toLowerCase() !== wantExt) {
      const newName = `${path.basename(file.filename, path.extname(file.filename))}${wantExt}`;
      const newPath = path.join(path.dirname(file.path), newName);
      try {
        fs.renameSync(file.path, newPath);
        file.filename = newName;
        file.path = newPath;
      } catch {
        /* keep the original name if the rename fails — the bytes are still valid */
      }
    }
  }
  return { ok: true };
}

function withMagicByteValidation(multerMw: RequestHandler): RequestHandler {
  return (req: Request, res: Response, next: NextFunction) => {
    multerMw(req, res, (err?: unknown) => {
      if (err) return next(err);
      const result = validateWrittenFiles(req);
      if (!result.ok) {
        return next(new Error(result.message));
      }
      next();
    });
  };
}

/** Single file upload middleware (field name: "file") */
export const upload: RequestHandler = withMagicByteValidation(multerInstance.single('file'));

/** Multiple file upload middleware — up to 5 files (field name: "files") */
export const uploadMultiple: RequestHandler = withMagicByteValidation(multerInstance.array('files', 5));
