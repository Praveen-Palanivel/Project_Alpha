# LocalStream Transfer Protocol Specification

> **Version:** 1.0.0  
> **Author:** Agent A  
> **Status:** Draft  
> **Goal:** Ultra-high-speed local file transfer (>100 MB/s on gigabit LAN)

---

## 1. Overview

LocalStream uses **QUIC** as the primary transport protocol for maximum transfer speed on local Wi-Fi networks. QUIC provides:

- **Multiplexed streams** — Parallel chunk transfers without head-of-line blocking
- **0-RTT handshake** — Instant connection establishment
- **Built-in encryption** — TLS 1.3 (required by QUIC)
- **Connection migration** — Seamless resume if network changes
- **Optimized congestion control** — High throughput on LAN

**Fallback:** TCP is supported when QUIC is unavailable (corporate firewalls, NAT issues).

---

## 2. Connection Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   QUIC CONNECTION                       │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │ CONTROL STREAM (Stream ID: 0) - Bidirectional   │   │
│  │  • Handshake messages                           │   │
│  │  • File metadata                                │   │
│  │  • Transfer control (pause/resume/cancel)       │   │
│  │  • Acknowledgments                              │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐         │
│  │ DATA       │ │ DATA       │ │ DATA       │  ...    │
│  │ STREAM 4   │ │ STREAM 8   │ │ STREAM 12  │         │
│  │ (Chunk 0)  │ │ (Chunk 1)  │ │ (Chunk 2)  │         │
│  └────────────┘ └────────────┘ └────────────┘         │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Stream ID Assignment
| Stream ID | Purpose | Direction |
|-----------|---------|-----------|
| 0 | Control stream | Bidirectional |
| 4, 8, 12, 16... | Data streams | Unidirectional (sender → receiver) |

> **Note:** QUIC stream IDs increment by 4 for client-initiated unidirectional streams.

---

## 3. Control Message Format

All control messages use a simple binary format:

```
┌──────────┬───────────┬─────────────────────────┐
│ MsgType  │ PayloadLen│ Payload                 │
│ (1 byte) │ (4 bytes) │ (variable)              │
└──────────┴───────────┴─────────────────────────┘
```

| Field | Type | Size | Description |
|-------|------|------|-------------|
| `MsgType` | u8 | 1 byte | Message type identifier |
| `PayloadLen` | u32 | 4 bytes | Payload length (big-endian) |
| `Payload` | bytes | variable | JSON or binary payload |

### Message Types

| Code | Name | Direction | Description |
|------|------|-----------|-------------|
| `0x01` | HELLO | S → R | Sender initiates connection |
| `0x02` | HELLO_ACK | R → S | Receiver acknowledges |
| `0x03` | FILE_OFFER | S → R | Sender offers file for transfer |
| `0x04` | FILE_ACCEPT | R → S | Receiver accepts file |
| `0x05` | FILE_REJECT | R → S | Receiver rejects file |
| `0x06` | CHUNK_ACK | R → S | Acknowledge received chunks |
| `0x07` | TRANSFER_COMPLETE | S → R | All chunks sent |
| `0x08` | TRANSFER_VERIFIED | R → S | Receiver verified file |
| `0x09` | ERROR | Both | Error occurred |
| `0x0A` | CANCEL | Both | Cancel transfer |
| `0x0B` | RESUME_REQUEST | S → R | Resume interrupted transfer |
| `0x0C` | RESUME_RESPONSE | R → S | Resume state from receiver |

---

## 4. Message Payloads

### 4.1 HELLO (0x01)
```json
{
  "protocol_version": "1.0.0",
  "device_name": "MyPhone",
  "device_id": "uuid-v4",
  "capabilities": ["quic", "tcp", "resume"]
}
```

### 4.2 HELLO_ACK (0x02)
```json
{
  "protocol_version": "1.0.0",
  "device_name": "MyPC",
  "device_id": "uuid-v4",
  "accepted": true
}
```

### 4.3 FILE_OFFER (0x03)
```json
{
  "transfer_id": "uuid-v4",
  "file_name": "video.mp4",
  "file_size": 5368709120,
  "mime_type": "video/mp4",
  "chunk_size": 4194304,
  "chunk_count": 1280,
  "checksum_type": "xxhash64",
  "file_checksum": "a1b2c3d4e5f67890",
  "created_at": "2026-02-08T12:00:00Z",
  "modified_at": "2026-02-08T11:30:00Z"
}
```

### 4.4 FILE_ACCEPT (0x04)
```json
{
  "transfer_id": "uuid-v4",
  "accepted": true,
  "save_path": "/storage/Downloads/video.mp4"
}
```

### 4.5 CHUNK_ACK (0x06)
```json
{
  "transfer_id": "uuid-v4",
  "acked_chunks": [0, 1, 2, 3, 4, 5, 6, 7],
  "last_acked": 7
}
```

### 4.6 ERROR (0x09)
```json
{
  "transfer_id": "uuid-v4",
  "error_code": 1,
  "error_message": "Checksum mismatch on chunk 42"
}
```

### 4.7 RESUME_REQUEST (0x0B)
```json
{
  "transfer_id": "uuid-v4",
  "file_checksum": "a1b2c3d4e5f67890"
}
```

### 4.8 RESUME_RESPONSE (0x0C)
```json
{
  "transfer_id": "uuid-v4",
  "can_resume": true,
  "received_chunks": [0, 1, 2, 3, 4, 5],
  "missing_chunks": [6, 7, 8, 9]
}
```

---

## 5. Data Stream Format

Each data stream carries a single chunk with the following binary format:

```
┌──────────────┬───────────────┬────────────────┬─────────────────────┐
│ TransferID   │ ChunkIndex    │ ChunkChecksum  │ ChunkData           │
│ (16 bytes)   │ (4 bytes)     │ (8 bytes)      │ (up to 4MB)         │
└──────────────┴───────────────┴────────────────┴─────────────────────┘
```

| Field | Type | Size | Description |
|-------|------|------|-------------|
| `TransferID` | UUID | 16 bytes | Transfer identifier (binary) |
| `ChunkIndex` | u32 | 4 bytes | Chunk index (big-endian, 0-based) |
| `ChunkChecksum` | u64 | 8 bytes | xxHash64 of chunk data |
| `ChunkData` | bytes | ≤4MB | Raw file data |

### Chunk Size
- **Default:** 4 MB (4,194,304 bytes)
- **Minimum:** 1 MB
- **Maximum:** 16 MB
- **Last chunk:** May be smaller than chunk size

---

## 6. Transfer Flow

### 6.1 Normal Transfer
```
    SENDER                                      RECEIVER
       │                                            │
       │─────────── HELLO (0-RTT) ────────────────▶│
       │◀────────── HELLO_ACK ─────────────────────│
       │                                            │
       │─────────── FILE_OFFER ───────────────────▶│
       │◀────────── FILE_ACCEPT ───────────────────│
       │                                            │
       │═══════════ DATA STREAM 4 (Chunk 0) ══════▶│
       │═══════════ DATA STREAM 8 (Chunk 1) ══════▶│
       │═══════════ DATA STREAM 12 (Chunk 2) ═════▶│
       │═══════════ DATA STREAM 16 (Chunk 3) ═════▶│
       │                                            │
       │◀────────── CHUNK_ACK [0,1,2,3] ───────────│
       │                                            │
       │═══════════ DATA STREAM 20 (Chunk 4) ═════▶│
       │           ... (continues) ...              │
       │                                            │
       │─────────── TRANSFER_COMPLETE ────────────▶│
       │◀────────── TRANSFER_VERIFIED ─────────────│
       │                                            │
```

### 6.2 Parallel Stream Strategy
- **Default parallel streams:** 4
- **Maximum parallel streams:** 8
- **Strategy:** Sliding window — start new stream when one completes
- **Goal:** Saturate bandwidth without overwhelming receiver

### 6.3 Acknowledgment Strategy
- Receiver sends CHUNK_ACK after every N chunks (default: 8)
- Or after timeout (100ms with no new chunks)
- Sender tracks unacknowledged chunks for potential resend

---

## 7. Resume Capability

LocalStream supports resuming interrupted transfers using QUIC connection migration and persistent state.

### 7.1 Receiver State Persistence
Receiver stores partial transfer state:
```json
{
  "transfer_id": "uuid-v4",
  "file_name": "video.mp4",
  "file_size": 5368709120,
  "file_checksum": "a1b2c3d4e5f67890",
  "temp_path": "/storage/.localstream/video.mp4.partial",
  "completed_chunks": [0, 1, 2, 3, 4, 5],
  "created_at": "2026-02-08T12:00:00Z"
}
```

### 7.2 Resume Flow
```
    SENDER                                      RECEIVER
       │                                            │
       │─────────── HELLO ────────────────────────▶│
       │◀────────── HELLO_ACK ─────────────────────│
       │                                            │
       │─────────── RESUME_REQUEST ───────────────▶│
       │◀────────── RESUME_RESPONSE ───────────────│
       │            (missing_chunks: [6,7,8...])    │
       │                                            │
       │═══════════ DATA (Chunk 6) ═══════════════▶│
       │═══════════ DATA (Chunk 7) ═══════════════▶│
       │           ... continues with missing ...   │
```

---

## 8. Error Handling

### 8.1 Error Codes

| Code | Name | Description | Recovery |
|------|------|-------------|----------|
| `0x00` | OK | Success | — |
| `0x01` | CHECKSUM_MISMATCH | Chunk validation failed | Resend chunk |
| `0x02` | FILE_NOT_FOUND | Requested file missing | Cancel transfer |
| `0x03` | STORAGE_FULL | Receiver disk full | Free space, retry |
| `0x04` | CONNECTION_LOST | Network failure | Auto-reconnect, resume |
| `0x05` | TIMEOUT | Operation timed out | Retry or cancel |
| `0x06` | CANCELLED | User cancelled | Clean up |
| `0x07` | INVALID_MESSAGE | Protocol error | Disconnect |
| `0x08` | VERSION_MISMATCH | Incompatible protocol version | Notify user |
| `0x09` | TRANSFER_REJECTED | Receiver declined file | — |
| `0x0A` | RESUME_FAILED | Cannot resume transfer | Restart transfer |

### 8.2 Chunk Retransmission
1. Receiver detects checksum mismatch
2. Receiver sends ERROR with affected chunk index
3. Sender resends chunk on new data stream
4. Max retries: 3 per chunk

### 8.3 Connection Recovery
- QUIC handles connection migration automatically
- If connection drops, client reconnects within 30 seconds
- Use RESUME_REQUEST to continue transfer

---

## 9. TCP Fallback

When QUIC is unavailable (blocked by firewall, unsupported platform):

### 9.1 TCP Connection
- Single TCP connection to port 42424
- Same control message format
- Sequential chunk transfer (no parallel streams)

### 9.2 TCP Message Framing
```
┌──────────────┬─────────────────────────────────┐
│ FrameLength  │ FrameData                       │
│ (4 bytes)    │ (control msg or chunk data)     │
└──────────────┴─────────────────────────────────┘
```

### 9.3 TCP Chunk Format
Same as QUIC data stream format, but sent sequentially.

---

## 10. Performance Optimizations

### 10.1 QUIC Tuning for LAN
| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Initial RTT | 5ms | LAN has very low latency |
| Max stream data | 16 MB | Large buffer for throughput |
| Max connection data | 64 MB | Allow many parallel streams |
| Disable slow start | Yes | LAN doesn't need congestion ramp |

### 10.2 Chunk Size Recommendations
| Network Speed | Recommended Chunk Size |
|---------------|------------------------|
| 100 Mbps | 1 MB |
| 1 Gbps | 4 MB |
| 2.5+ Gbps | 8-16 MB |

### 10.3 Buffer Sizes
- **Send buffer:** 16 MB
- **Receive buffer:** 16 MB
- **File read ahead:** 32 MB

### 10.4 Checksum Choice
**xxHash64** chosen for:
- Speed: 10+ GB/s on modern CPUs
- Quality: Excellent collision resistance for data integrity
- Faster than: CRC32C, SHA-256, MD5

---

## 11. Security Considerations

### 11.1 QUIC TLS
- QUIC mandates TLS 1.3
- Self-signed certificates acceptable for local transfer
- Certificate fingerprint can be verified via QR code / discovery

### 11.2 Scope Limitations
- **No authentication** — Local network assumed trusted
- **No authorization** — User must accept each transfer
- **No end-to-end encryption** — TLS 1.3 is sufficient for LAN

---

## 12. Implementation Notes

### 12.1 Library Recommendations
| Platform | QUIC Library |
|----------|--------------|
| Android | cronet (Chrome's network stack) |
| Windows | quiche (Cloudflare) or msquic (Microsoft) |
| Cross-platform | quiche-ffi |

### 12.2 Port Assignment
| Protocol | Port | Purpose |
|----------|------|---------|
| QUIC/UDP | 42424 | Primary transfer |
| TCP | 42424 | Fallback transfer |
| UDP | 42425 | Discovery broadcast |

### 12.3 File Naming
- Preserve original filename
- Handle duplicates: `file.mp4` → `file (1).mp4`
- Sanitize invalid characters per platform

---

## Appendix A: Constants

```
PROTOCOL_VERSION     = "1.0.0"
DEFAULT_QUIC_PORT    = 42424
DEFAULT_TCP_PORT     = 42424
DISCOVERY_PORT       = 42425
DEFAULT_CHUNK_SIZE   = 4194304      // 4 MB
MIN_CHUNK_SIZE       = 1048576      // 1 MB  
MAX_CHUNK_SIZE       = 16777216     // 16 MB
PARALLEL_STREAMS     = 4
MAX_PARALLEL_STREAMS = 8
CHUNK_ACK_INTERVAL   = 8
ACK_TIMEOUT_MS       = 100
MAX_RETRY_COUNT      = 3
CONNECTION_TIMEOUT   = 30000        // 30 seconds
```

---

## Appendix B: Example Transfer Calculation

**File:** 5 GB video  
**Chunk size:** 4 MB  
**Chunks:** 1,280  
**Parallel streams:** 4  
**Network:** 1 Gbps  

**Theoretical max:** 125 MB/s  
**Expected actual:** 100-110 MB/s (accounting for overhead)  
**Transfer time:** ~45-50 seconds  

---

**END OF PROTOCOL SPECIFICATION**
