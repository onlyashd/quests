# 0004. Reach the backend only through Oblivious HTTP, with Cloudflare as relay

- **Status:** Accepted
- **Date:** 2026-11-09 (source spec Decision D3; relay chosen 2026-11-09)
- **Specs:** [protocol §10](../specs/protocol.md#10-oblivious-http-transport-decision-d3), [backend §3.1](../specs/backend.md#31-ohttp-gateway), [backend §6.3](../specs/backend.md#63-upload-chunked-stream-proxy--d3)

## Context

Whatever the application layer hides, a server that sees connections sees IP addresses, and an
IP address links requests together. The backend must never receive client IPs, yet clients must
still upload proofs (photos up to 16 MiB) and poll for status.

## Options considered

1. **Tor or a VPN.** Strong, but too much friction for participants, and blocked or slow on
   many networks.
2. **A relay we run ourselves.** The same operator then sees both IPs and content, so the split
   protects nothing.
3. **OHTTP (RFC 9458) through a third-party relay.** The relay sees IPs but not content, the
   gateway (us) sees content but not IPs.
4. **Direct uploads to object storage** (pre-signed URLs). Simple, but the storage provider sees
   client IPs.

## Decision

- Every client request is an RFC 9292 Binary HTTP message, encapsulated with RFC 9458 OHTTP
  (DHKEM-X25519, HKDF-SHA256, ChaCha20-Poly1305). It's sent through **Cloudflare's managed OHTTP
  relay** to our gateway. We run only the gateway.
- Clients ship a **pinned** key config, so bootstrapping never reveals the IP to us. Updates
  arrive inside the signed `/v1/config`.
- Requests and responses are at most 256 KiB, and responses are padded to a multiple of 4 KiB.
- Uploads are split into independent OHTTP requests. The server coalesces them into S3 multipart
  parts (the source spec's "chunked stream proxy"). MinIO is reachable only from `:server`, so
  storage never sees client IPs either.
- The redeemer has its own gateway and its own Cloudflare relay endpoint.

## Consequences

- Privacy relies on Cloudflare and the backend operator **not colluding**. That trust assumption
  is stated to users.
- Production needs a commercial agreement with Cloudflare, plus the relay's egress ranges or mTLS
  identity for `RELAY_ALLOWLIST`. It's an open item that blocks production (backend spec §13).
- Upload throughput is limited by request size, and the server holds up to 5 MiB of buffer per
  active upload session.
- Local development uses a test relay in `:server` test sources.
