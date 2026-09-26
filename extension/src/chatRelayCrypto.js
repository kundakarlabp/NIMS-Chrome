(function (root) {
  "use strict";

  const encoder = new TextEncoder();
  const decoder = new TextDecoder();

  function bytesToBase64Url(bytes) {
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
  }

  function base64UrlToBytes(value) {
    const raw = String(value || "").replace(/-/g, "+").replace(/_/g, "/");
    const padded = raw + "=".repeat((4 - (raw.length % 4 || 4)) % 4);
    const binary = atob(padded);
    return Uint8Array.from(binary, (ch) => ch.charCodeAt(0));
  }

  function randomSecret(byteLength = 32) {
    const bytes = new Uint8Array(byteLength);
    crypto.getRandomValues(bytes);
    return bytesToBase64Url(bytes);
  }

  async function generateRsaIdentity() {
    const pair = await crypto.subtle.generateKey(
      { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
      true,
      ["encrypt", "decrypt"]
    );
    return {
      publicKeyJwk: await crypto.subtle.exportKey("jwk", pair.publicKey),
      privateKeyJwk: await crypto.subtle.exportKey("jwk", pair.privateKey)
    };
  }

  async function importPublicKey(jwk) {
    return crypto.subtle.importKey(
      "jwk",
      jwk,
      { name: "RSA-OAEP", hash: "SHA-256" },
      false,
      ["encrypt"]
    );
  }

  async function importPrivateKey(jwk) {
    return crypto.subtle.importKey(
      "jwk",
      jwk,
      { name: "RSA-OAEP", hash: "SHA-256" },
      false,
      ["decrypt"]
    );
  }

  async function encryptJson(value, publicKeyJwk) {
    const publicKey = await importPublicKey(publicKeyJwk);
    const aesKey = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, true, ["encrypt"]);
    const rawAes = new Uint8Array(await crypto.subtle.exportKey("raw", aesKey));
    const iv = new Uint8Array(12);
    crypto.getRandomValues(iv);
    const plaintext = encoder.encode(JSON.stringify(value));
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, aesKey, plaintext));
    const wrappedKey = new Uint8Array(await crypto.subtle.encrypt({ name: "RSA-OAEP" }, publicKey, rawAes));
    return {
      v: 1,
      alg: "RSA-OAEP-256+A256GCM",
      wrappedKey: bytesToBase64Url(wrappedKey),
      iv: bytesToBase64Url(iv),
      ciphertext: bytesToBase64Url(ciphertext)
    };
  }

  async function decryptJson(envelope, privateKeyJwk) {
    if (!envelope || envelope.v !== 1) throw new Error("unsupported_envelope");
    const privateKey = await importPrivateKey(privateKeyJwk);
    const rawAes = await crypto.subtle.decrypt(
      { name: "RSA-OAEP" },
      privateKey,
      base64UrlToBytes(envelope.wrappedKey)
    );
    const aesKey = await crypto.subtle.importKey("raw", rawAes, { name: "AES-GCM" }, false, ["decrypt"]);
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: base64UrlToBytes(envelope.iv) },
      aesKey,
      base64UrlToBytes(envelope.ciphertext)
    );
    return JSON.parse(decoder.decode(plaintext));
  }

  const api = {
    bytesToBase64Url,
    base64UrlToBytes,
    randomSecret,
    generateRsaIdentity,
    encryptJson,
    decryptJson
  };
  root.NimsChatRelayCrypto = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : self);
