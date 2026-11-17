# contract

The offline transfer format between the clinic app (`../doctor-web/`) and the patient app
(`../patnub-ai/`), format version 1. Spec v1.2 §4.6.1 and §8.1 explain the why.

- `contract.ts`: reference implementation (WebCrypto only, no dependencies). doctor-web
  imports it; the scripts run it with Node 24+ (native type stripping).
- `vectors.json`: known keys, codes and expected results. **Every implementation must pass
  them**, including the Kotlin one in the Android app.
- `demo/root.json`: the DEMO root key. Its private half is committed on purpose so the team
  can sign demo licenses. Never use it for real patients.

```sh
node --test                                    # 7 tests: round trip, wrong phone, tampering, expiry, license chain, vectors
node scripts/issue-license.ts rhu-san-isidro "RHU San Isidro" 365 > license.json
node scripts/make-vectors.ts                   # only when the format changes
node scripts/demo-root.ts --force              # new demo root: breaks all licenses, needs a new APK
```

## Crypto

| Use | Algorithm |
| --- | --- |
| Signatures (root, clinic admin, staff) | Ed25519 |
| Encryption to the patient's phone | X25519 ECDH, HKDF-SHA256, AES-256-GCM |
| Compression | raw DEFLATE (`deflate-raw`) |
| QR text | Base45 (RFC 9285), so the QR uses alphanumeric mode |

All keys are raw 32 bytes, written as unpadded base64url in JSON. Times are Unix seconds.

## Certificates

`cert = u16 body length (big-endian) | body (UTF-8 JSON) | Ed25519 signature over body (64 bytes)`

Signing the exact body bytes avoids JSON canonicalisation. Parse the JSON only after the
signature checks out.

- **License** (signed by the root key): `{"t":"license","clinic_id","clinic_name","admin_pub","nbf","exp"}`.
  `exp` is the end of the subscription.
- **Staff** (signed by the license's `admin_pub`): `{"t":"staff","clinic_id","staff_id","name","profession","license_no","pub","nbf","exp"}`.

## Check-in code (phone shows, clinic scans)

`"PNBC1:" + base45(UTF-8 JSON {"name","age","brgy"?,"pub"})`, where `pub` is the phone's X25519
public key.

## Record QR (clinic shows, phone scans)

```
payload = 0x01 (format version)
        | license cert | staff cert
        | ephemeral X25519 public key (32) | nonce (12)          <- end of header
        | AES-256-GCM ciphertext + tag (16)                       aad = header
        | Ed25519 signature by staff key (64) over everything before it
text    = "PNB1:" + base45(payload)
```

- AES key = HKDF-SHA256(ikm = X25519(ephemeral, patient), salt = ephemeral pub | patient pub,
  info = `"patnub-record-v1"`, 32 bytes).
- Plaintext = deflate-raw(UTF-8 JSON patient copy):
  `{"rid","rv","iat","exp","encounter_date","assessment","meds":[{"name","dose","freq"}],"instructions":[],"warnings":[],"follow_up"}`.
  The raw clinician note is never included.

### Opening a record (patient app), in this order

1. Prefix, Base45, format version → else `FORMAT`.
2. License signed by the bundled root key; staff signed by `admin_pub`; same `clinic_id`; record
   signed by staff `pub` → else `SIGNATURE` / `CHAIN`. **Verify before decrypting.**
3. Decrypt with the phone's private key → failure means `NOT_FOR_THIS_PHONE`.
4. `iat` inside both certificates' `nbf..exp` → else `CHAIN`. Judged at issue time, so a
   lapsed license never takes records away from patients.
5. `now <= exp` → else `EXPIRED`.
6. Refuse a `rid` + `rv` already imported; a higher `rv` supersedes a lower one.

The sample record in the tests is 1,391 characters, well inside one QR (alphanumeric mode
holds up to 4,296). Multi-frame QR is not specified yet.

Known demo gap: `iat` is set by the clinic device, so a clinic with a lapsed license could
backdate (spec §8.1).
