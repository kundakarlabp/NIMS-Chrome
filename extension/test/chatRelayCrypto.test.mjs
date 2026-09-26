import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";


const require = createRequire(import.meta.url);
const relayCrypto = require("../src/chatRelayCrypto.js");

test("chat relay hybrid envelope round-trips synthetic JSON", async () => {
  const identity = await relayCrypto.generateRsaIdentity();
  const synthetic = {
    crNo: "000000000000001",
    note: "synthetic test only"
  };
  const envelope = await relayCrypto.encryptJson(synthetic, identity.publicKeyJwk);
  assert.equal(envelope.v, 1);
  assert.equal(envelope.alg, "RSA-OAEP-256+A256GCM");
  assert.equal(JSON.stringify(envelope).includes(synthetic.crNo), false);
  const decoded = await relayCrypto.decryptJson(envelope, identity.privateKeyJwk);
  assert.deepEqual(decoded, synthetic);
});

test("relay device secret is URL-safe and high entropy length", () => {
  const secret = relayCrypto.randomSecret(32);
  assert.match(secret, /^[A-Za-z0-9_-]+$/);
  assert.ok(secret.length >= 40);
});
