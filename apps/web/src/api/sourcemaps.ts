import { apiFetch } from './client';

export interface SourceMapArtifact {
  id: number;
  releaseId: number;
  r2Key: string;
  originalPath: string;
  /** 64 lowercase hex chars. */
  sha256: string;
  sizeBytes: number;
  createdAt: string;
}

export interface CreateSourceMapUploadResponse {
  artifact: SourceMapArtifact;
  uploadUrl: string;
  expiresAt: string;
}

export interface CreateSourceMapUploadRequest {
  originalPath: string;
  sha256: string;
  sizeBytes: number;
}

export function listSourceMaps(
  projectId: number,
  releaseId: number,
): Promise<SourceMapArtifact[]> {
  return apiFetch<SourceMapArtifact[]>(
    `/api/v1/projects/${projectId}/releases/${releaseId}/sourcemaps`,
  );
}

/**
 * Step 1 of the two-phase upload: mints the artifact row in Postgres and returns a presigned R2
 * URL the caller PUTs the bytes to. See {@link uploadFileToPresignedUrl} for step 2.
 */
export function createSourceMapUpload(
  projectId: number,
  releaseId: number,
  body: CreateSourceMapUploadRequest,
): Promise<CreateSourceMapUploadResponse> {
  return apiFetch<CreateSourceMapUploadResponse>(
    `/api/v1/projects/${projectId}/releases/${releaseId}/sourcemaps`,
    { method: 'POST', body: JSON.stringify(body) },
  );
}

/**
 * Step 2 of the two-phase upload: PUTs the file bytes to the presigned URL. Uses XHR (not fetch)
 * so we get upload progress events — fetch() doesn't expose them as of mid-2026 across browsers.
 *
 * R2 returns 200 on success, 403 on a tampered URL, 411 if Content-Length doesn't match what was
 * signed. We surface any non-2xx as a thrown Error so the calling mutation can show it.
 */
export function uploadFileToPresignedUrl(
  url: string,
  file: File,
  onProgress?: (loaded: number, total: number) => void,
): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url);
    // R2 signs against the exact Content-Type sent at upload — most callers want
    // application/octet-stream so the bucket doesn't try to re-interpret the bytes.
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(e.loaded, e.total);
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        if (onProgress) onProgress(file.size, file.size);
        resolve();
      } else {
        reject(new Error(`Upload failed: HTTP ${xhr.status} — ${xhr.responseText || xhr.statusText}`));
      }
    };
    xhr.onerror = () => reject(new Error('Network error during upload'));
    xhr.onabort = () => reject(new Error('Upload aborted'));
    xhr.send(file);
  });
}
