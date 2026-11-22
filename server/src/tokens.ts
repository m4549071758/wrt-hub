import { createHash, randomBytes, randomInt } from "node:crypto";

/** A long-lived bearer token: 256 random bits, URL-safe. */
export function newToken(): string {
  return randomBytes(32).toString("base64url");
}

export function hashSecret(secret: string): string {
  return createHash("sha256").update(secret, "utf8").digest("hex");
}

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

/** A one-time pairing code that is easy to type when the QR code cannot be scanned: 12 characters, about 60 bits. */
export function newPairingCode(): string {
  let code = "";
  for (let i = 0; i < 12; i++) code += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8)}`;
}

/** Codes are compared without dashes, spaces or case, as people retype them. */
export function normalizeCode(code: string): string {
  return code.replace(/[\s-]/g, "").toUpperCase();
}

export function newId(): string {
  return randomBytes(9).toString("base64url");
}
