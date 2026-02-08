# LocalStream Discovery Protocol Specification

> **Version:** 1.0.0  
> **Author:** Agent A  
> **Status:** Draft  
> **Goal:** Device discovery in <2 seconds on local network

---

## 1. Overview

LocalStream uses **UDP broadcast** for automatic device discovery on local Wi-Fi networks. This enables:

- **Zero-configuration** — Devices find each other automatically
- **Fast discovery** — Target: <2 seconds to find all peers
- **Offline operation** — No internet or central server required
- **Manual fallback** — Direct IP entry when broadcast fails

---

## 2. Network Configuration

### Ports
| Protocol | Port | Purpose |
|----------|------|---------|
| UDP | 42425 | Discovery broadcast/listen |
| QUIC/UDP | 42424 | File transfer |
| TCP | 42424 | Transfer fallback |

### Broadcast Address
- IPv4: `255.255.255.255` (limited broadcast)
- Alternative: Subnet broadcast (e.g., `192.168.1.255`)

> **Note:** IPv6 multicast (`ff02::1`) may be added in future versions.

---

## 3. Discovery Message Format

All discovery messages use JSON for simplicity and debugging:

```json
{
  "magic": "LOCALSTREAM",
  "version": "1.0.0",
  "type": "announce|query|response",
  "device_id": "uuid-v4",
  "device_name": "My Phone",
  "platform": "android|windows",
  "transfer_port": 42424,
  "capabilities": ["quic", "tcp", "resume"],
  "timestamp": 1707379200000
}
```

### Field Descriptions

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `magic` | string | ✅ | Always "LOCALSTREAM" - identifies our protocol |
| `version` | string | ✅ | Protocol version for compatibility |
| `type` | string | ✅ | Message type: announce, query, or response |
| `device_id` | string | ✅ | Unique device identifier (UUID v4) |
| `device_name` | string | ✅ | Human-readable device name |
| `platform` | string | ✅ | Platform identifier |
| `transfer_port` | number | ✅ | Port for file transfer connections |
| `capabilities` | array | ✅ | Supported features |
| `timestamp` | number | ✅ | Unix timestamp in milliseconds |

### Maximum Message Size
- **Limit:** 1024 bytes
- **Rationale:** Fits in single UDP datagram, no fragmentation

---

## 4. Message Types

### 4.1 ANNOUNCE
Broadcast when device becomes available or periodically.

```json
{
  "magic": "LOCALSTREAM",
  "version": "1.0.0",
  "type": "announce",
  "device_id": "550e8400-e29b-41d4-a716-446655440000",
  "device_name": "Pixel 8 Pro",
  "platform": "android",
  "transfer_port": 42424,
  "capabilities": ["quic", "tcp", "resume"],
  "timestamp": 1707379200000
}
```

**When to send:**
- App startup/foreground
- Every 5 seconds while discoverable
- Network change detected

### 4.2 QUERY
Request all devices to respond (active discovery).

```json
{
  "magic": "LOCALSTREAM",
  "version": "1.0.0",
  "type": "query",
  "device_id": "660e8400-e29b-41d4-a716-446655440001",
  "device_name": "Gaming PC",
  "platform": "windows",
  "transfer_port": 42424,
  "capabilities": ["quic", "tcp", "resume"],
  "timestamp": 1707379200500
}
```

**When to send:**
- User opens device list
- Pull-to-refresh
- Initial app launch

### 4.3 RESPONSE
Unicast reply to a query (sent directly to querier's IP).

```json
{
  "magic": "LOCALSTREAM",
  "version": "1.0.0",
  "type": "response",
  "device_id": "550e8400-e29b-41d4-a716-446655440000",
  "device_name": "Pixel 8 Pro",
  "platform": "android",
  "transfer_port": 42424,
  "capabilities": ["quic", "tcp", "resume"],
  "timestamp": 1707379200600
}
```

---

## 5. Discovery Flow

### 5.1 Passive Discovery (Listen for Announces)

```
Device A                              Device B
   │                                      │
   │◀───── ANNOUNCE (broadcast) ──────────│
   │                                      │
   │  [Add Device B to peer list]         │
   │                                      │
```

### 5.2 Active Discovery (Query + Response)

```
Device A                              Device B
   │                                      │
   │─────── QUERY (broadcast) ───────────▶│
   │                                      │
   │◀────── RESPONSE (unicast) ───────────│
   │                                      │
   │  [Add Device B to peer list]         │
   │                                      │
```

### 5.3 Complete Startup Sequence

```
1. Bind UDP socket to port 42425
2. Send QUERY broadcast
3. Start listening for messages
4. Send ANNOUNCE broadcast
5. Set up periodic ANNOUNCE (every 5s)
6. Process incoming messages:
   - ANNOUNCE → Add/update peer
   - QUERY → Send RESPONSE to sender
   - RESPONSE → Add/update peer
```

---

## 6. Device Naming Rules

### 6.1 Default Names
| Platform | Default Name Pattern |
|----------|---------------------|
| Android | Device model (e.g., "Pixel 8 Pro") |
| Windows | Computer name (e.g., "DESKTOP-ABC123") |

### 6.2 Custom Names
- Users can set custom device names
- Stored locally, persisted across sessions
- Max length: 32 characters
- Allowed: alphanumeric, spaces, hyphens, underscores

### 6.3 Display Format
```
[Device Name] ([Platform])
Example: "John's Phone (Android)"
```

---

## 7. Collision Handling

### 7.1 Duplicate Device IDs
Should not happen (UUID v4), but if detected:
1. Log warning
2. Keep the most recent entry (by timestamp)
3. Display both if names differ

### 7.2 Duplicate Device Names
- Append platform to disambiguate: "MyDevice (Android)" vs "MyDevice (Windows)"
- If same platform, append partial device_id: "MyDevice (abc123)"

### 7.3 Stale Entries
- Remove peer if no message received for 30 seconds
- Mark as "offline" after 15 seconds without update
- Immediate removal on explicit disconnect message (future)

---

## 8. Timeout Behavior

| Event | Timeout | Action |
|-------|---------|--------|
| Discovery query | 2 seconds | Stop waiting, show results |
| Peer staleness | 15 seconds | Mark peer as "possibly offline" |
| Peer removal | 30 seconds | Remove from peer list |
| Announce interval | 5 seconds | Send periodic announce |
| Socket bind retry | 1 second | Retry binding to port |

---

## 9. Error Handling

### 9.1 Port Already in Use
1. Try ports 42425, 42426, 42427
2. If all fail, show error to user
3. Suggest closing other LocalStream instances

### 9.2 Network Unavailable
1. Detect no Wi-Fi/network connection
2. Show "No network available" message
3. Offer manual IP entry option
4. Retry when network becomes available

### 9.3 Broadcast Blocked
Some networks block broadcast. Fallback options:
1. Subnet scan (ping sweep) - expensive but works
2. Manual IP entry
3. QR code with IP address

---

## 10. Manual IP Fallback

When automatic discovery fails:

### 10.1 UI Flow
1. User taps "Enter IP manually"
2. Enter IP address: `192.168.1.100`
3. Enter port (default 42424)
4. App sends direct QUERY to that IP
5. If response received, add to peer list

### 10.2 Direct Query Format
Same as broadcast QUERY, but sent directly to specific IP.

---

## 11. Platform-Specific Notes

### 11.1 Android
- Requires `ACCESS_WIFI_STATE` permission
- Requires `CHANGE_WIFI_MULTICAST_STATE` for multicast
- Use `WifiManager.MulticastLock` to receive broadcasts
- Handle Doze mode: may miss broadcasts when screen off

### 11.2 Windows
- No special permissions needed
- May need Windows Firewall exception
- Handle multiple network interfaces (prefer Wi-Fi)

---

## 12. Security Considerations

### 12.1 Local Network Only
- Discovery operates only on local subnet
- No internet exposure
- Broadcasts don't leave the LAN

### 12.2 No Authentication
- Discovery is open (anyone on network can see devices)
- Authentication happens at transfer time (user approval)

### 12.3 Spoofing Risk
- Device names/IDs can be spoofed
- Mitigation: User verifies device before accepting transfer

---

## Appendix A: Constants

```
DISCOVERY_PORT       = 42425
TRANSFER_PORT        = 42424
MAGIC_STRING         = "LOCALSTREAM"
PROTOCOL_VERSION     = "1.0.0"
ANNOUNCE_INTERVAL_MS = 5000
QUERY_TIMEOUT_MS     = 2000
PEER_STALE_MS        = 15000
PEER_REMOVE_MS       = 30000
MAX_MESSAGE_SIZE     = 1024
MAX_DEVICE_NAME_LEN  = 32
```

---

## Appendix B: Example Implementation (Pseudocode)

```python
def start_discovery():
    socket = udp_socket(port=42425)
    
    # Send initial query
    broadcast(QUERY_MESSAGE)
    
    # Send announce
    broadcast(ANNOUNCE_MESSAGE)
    
    # Start periodic announce
    every(5_seconds):
        broadcast(ANNOUNCE_MESSAGE)
    
    # Listen for messages
    while running:
        msg, sender_ip = socket.receive()
        
        if msg.magic != "LOCALSTREAM":
            continue
        
        if msg.type == "announce" or msg.type == "response":
            add_or_update_peer(msg, sender_ip)
        
        elif msg.type == "query":
            send_response(sender_ip)
```

---

**END OF DISCOVERY SPECIFICATION**
