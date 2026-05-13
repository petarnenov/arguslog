/**
 * Compute SHA-256 of an arbitrary File / Blob in the browser, returning the digest as a 64-char
 * lowercase hex string (the wire format the API expects).
 *
 * Uses {@link crypto.subtle.digest}, which is available on every modern browser in
 * secure contexts (HTTPS or localhost). The {@code Cross-device dev — DEV_HOST + Chrome flag}
 * memory note covers the LAN-IP dev workaround when subtle is gated.
 *
 * Reads the file as an ArrayBuffer in one shot — fine for source maps (<50MB typically).
 * Switch to streaming + Web Crypto Streams if we ever need GB-scale uploads.
 */
export async function sha256OfFile(file: Blob): Promise<string> {
  const buffer = await file.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  const bytes = new Uint8Array(digest);
  let hex = '';
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i]!.toString(16).padStart(2, '0');
  }
  return hex;
}
