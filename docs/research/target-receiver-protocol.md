# Target receiver protocol classification

Research date: 2026-08-20 UTC  
Target: `Living Room`, `192.168.0.139`  
Question: which sender path does this receiver accept, and is it FairPlay-gated RAOP or native AirPlay 2?

## Conclusion

The target is a **Denon Home 250 advertising both native AirPlay 2 and a RAOP-compatible endpoint on TCP 7000**. It is not the previously investigated FairPlay SAPv2 receiver.

- The RAOP advertisement is `et=0,4`. In sender implementations, `0` means unencrypted audio and `4` means MFiSAP `/auth-setup`; FairPlay SAPv2.5 is `5`, and classic RSA is `1`. The target advertises neither `1` nor `5`.[^pyatv-et]
- The AirPlay feature mask sets bits 38, 40, 41, 46, 47, and 48. Current sender implementations map these to unified/native AirPlay 2, buffered audio, PTP, HomeKit pairing, peer management, and CoreUtils pairing/encryption.[^owntone-features][^cli-routing]
- `/info` independently identifies `sdk=AirPlay;2.6.1`, `PTPInfo=PTP-AirPlay;2.6.1`, and `protocolVersion=1.1`. Denon's own manual identifies the Home 250 as an AirPlay 2 receiver.[^denon]
- The recorded CenturyPlay exchange sent `/fp-setup`, received `404`, then sent a clear RAOP `ANNOUNCE` and received `403`.[^map] That is consistent with omitting `/auth-setup`; OwnTone documents and implements `OPTIONS -> POST /auth-setup -> ANNOUNCE` for RAOP-compatible AirPlay 2 receivers because otherwise `ANNOUNCE` receives `403`.[^owntone-raop]

Therefore:

1. **FairPlay SAPv2 is ruled out for this target.** Do not implement `/fp-setup` or `a=fpaeskey` to fix the Denon.
2. **Native AirPlay 2 is positively advertised.** With no PIN/password flag set, the advertised native route is transient HAP pair-setup, encrypted RTSP, PTP, and potentially buffered type-103 audio.[^cli-native]
3. **RAOP compatibility is also advertised.** Its required preflight is a 33-byte `/auth-setup` request followed by the existing RAOP session, not FairPlay.[^pyatv-auth][^owntone-raop]
4. The advertisements prove capability, not which route Apple Music selects on this firmware. A successful Apple-sender capture is still required before claiming an exact Apple handshake. A controlled `/auth-setup` experiment or a successful open-source sender run is separately required to prove that the compatibility route works on this unit.

## Direct receiver evidence

The following probes were read-only. No app was installed or started and no streaming/authentication request was sent.

### Services and TXT records

Unicast mDNS queries sent directly to the target:

```bash
dig @192.168.0.139 -p 5353 _airplay._tcp.local PTR +noall +answer +additional
dig @192.168.0.139 -p 5353 _raop._tcp.local PTR +noall +answer +additional
```

Relevant response:

```text
Living Room._airplay._tcp.local. SRV 0 0 7000 Living-Room.local.
Living Room._airplay._tcp.local. TXT
  acl=0
  deviceid=37:56:2C:43:3A:F0
  features=0x445F8A00,0x4001C340
  flags=0x4
  model=Denon Home 250
  manufacturer=Denon
  protovers=1.1
  srcvers=366.0
  pi=37:56:2C:43:3A:F0
  pk=466a23ac2cb697145f4558997babd646797952b3b069df754e9190bf57b1e4ee

37562C433AF0@Living Room._raop._tcp.local. SRV 0 0 7000 Living-Room.local.
37562C433AF0@Living Room._raop._tcp.local. TXT
  cn=0,1
  da=true
  et=0,4
  ft=0x445F8A00,0x4001C340
  md=0,1,2
  am=Denon Home 250
  sf=0x4
  tp=UDP
  vn=65537
  vs=366.0
  pk=466a23ac2cb697145f4558997babd646797952b3b069df754e9190bf57b1e4ee
```

Both service records resolve to `192.168.0.139:7000`. TCP 7000 accepted connections; TCP 5000 and 57000 refused them.

The feature halves combine as `(0x4001C340 << 32) | 0x445F8A00 = 0x4001C340445F8A00`. Set bits are:

```text
9, 11, 15, 16, 17, 18, 19, 20, 22, 26, 30, 38, 40, 41, 46, 47, 48, 62
```

The route-relevant bits are:

| Bit | Sender interpretation |
| --- | --- |
| 9 | AirPlay audio |
| 18, 19, 20 | PCM, ALAC, and AAC-LC receive formats |
| 26 | MFi authentication / `/auth-setup` |
| 38 | Unified media control; native AirPlay 2 signal |
| 40 | Buffered audio |
| 41 | PTP timing |
| 46 | HomeKit pairing and access control |
| 47 | Peer management |
| 48 | CoreUtils pairing and encryption; native AirPlay 2 signal |

These names are reverse-engineered rather than published by Apple, but independent sender and receiver code agrees on the route-relevant bits.[^owntone-features][^receiver-features][^cli-routing]

### `/info`

The target returned `RTSP/1.0 200 OK`, `Server: AirTunes/366.0`, and a binary plist for an unpaired `GET /info` request:

```json
{
  "OSInfo": "10.0.1",
  "PTPInfo": "PTP-AirPlay;2.6.1",
  "build": "76.0",
  "deviceID": "37:56:2C:43:3A:F0",
  "features": 4612182174196533760,
  "firmwareBuildDate": "Jun 27 2026",
  "firmwareRevision": "3.139.170",
  "hardwareRevision": "1.0.0",
  "manufacturer": "Denon",
  "model": "Denon Home 250",
  "name": "Living Room",
  "pi": "37:56:2C:43:3A:F0",
  "protocolVersion": "1.1",
  "sdk": "AirPlay;2.6.1",
  "sourceVersion": "366.0",
  "statusFlags": 4
}
```

`OPTIONS *` also returned `200 OK` and:

```text
Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, FLUSHBUFFERED, TEARDOWN, OPTIONS, POST, GET, PUT
```

This endpoint shape is compatible with both advertised routes. `/info` plus feature bits establishes native AirPlay 2 capability; it does not reveal the route a particular Apple sender will choose.

## Path distinction

### RAOP compatibility (`et=0,4`)

The candidate sequence is:

```text
TCP 7000
OPTIONS *
POST /auth-setup       33-byte body: 0x01 + 32-byte Curve25519 public key
ANNOUNCE               SDP/ALAC or PCM, no fpaeskey
SETUP                  classic RAOP transport negotiation
RECORD
RTP/UDP + RAOP control/timing
```

OwnTone takes this path when `_raop._tcp` contains `et=4`: it sends the unencrypted selector byte plus a Curve25519 public key, ignores the returned MFi material, and proceeds to `ANNOUNCE`.[^owntone-raop] pyatv uses the same 33-byte request.[^pyatv-auth]

This path is the smallest change relative to CenturyPlay's current RAOP pipeline, but it has not yet been proven against this target under the restrictions of this ticket.

### Native AirPlay 2/HAP

The advertised candidate sequence is:

```text
TCP 7000
GET /info
POST /pair-setup       transient HAP exchange because statusFlags has no PIN/password bit
encrypted RTSP begins
PTP clock setup        UDP 319/320
SETUP                  encrypted binary-plist session request
RECORD
SETUP                  encrypted binary-plist audio stream, type 96 or buffered type 103
SETPEERS                PTP peer list
ChaCha20-Poly1305-protected audio
```

This is the route selected by the current `airplay-cli` sender for the target's feature and status masks: bits 38/48 classify AirPlay 2, bits 46/48 permit pairing, the absence of PIN/password flags selects transient pair-setup, bit 41 selects PTP, and bit 40 makes buffered audio eligible.[^cli-routing] Its native implementation then performs `GET /info`, HAP pairing, encrypted session `SETUP`, `RECORD`, stream `SETUP`, and `SETPEERS`.[^cli-native]

This is evidence of a viable implementation contract, not evidence that Apple Music used that exact sequence against this physical receiver.

### FairPlay SAPv2

A FairPlay-gated RAOP session would advertise `et=5`, accept `POST /fp-setup`, and place `a=fpaeskey` in `ANNOUNCE`. The target does none of those in the available evidence: it advertises `et=0,4`, and CenturyPlay's earlier `/fp-setup` received `404`.[^map] FairPlay implementation work is therefore unrelated to making this Denon work.

## Required Apple-sender capture

Use a Mac as both the Apple sender and capture host so switched Wi-Fi does not hide unicast traffic.

1. Ensure no other sender is connected to `Living Room`. Do not create a speaker group.
2. Resolve the services immediately before capture:

   ```bash
   dns-sd -L 'Living Room' _airplay._tcp local.
   dns-sd -L '37562C433AF0@Living Room' _raop._tcp local.
   ```

3. Determine the interface with `route get 192.168.0.139`; use the value shown after `interface:` below in place of `en0`.
4. Start packet and unified-log capture before opening the AirPlay picker:

   ```bash
   sudo tcpdump -i en0 -n -s 0 -U \
     -w living-room-apple-success.pcap \
     'host 192.168.0.139 or udp port 5353 or udp port 319 or udp port 320'
   ```

   In a second terminal:

   ```bash
   log stream --style compact --info --debug \
     --predicate 'process CONTAINS[c] "AirPlay" OR process CONTAINS[c] "airtunes" OR subsystem CONTAINS[c] "AirPlay" OR eventMessage CONTAINS[c] "RAOP" OR eventMessage CONTAINS[c] "pair-setup" OR eventMessage CONTAINS[c] "pair-verify" OR eventMessage CONTAINS[c] "auth-setup" OR eventMessage CONTAINS[c] "fp-setup"'
   ```

5. In macOS Music, select only `Living Room`, play audible audio for at least 30 seconds, change volume once, stop playback, wait five seconds, then stop both collectors.
6. Record the macOS version, Music version, receiver firmware, sender IP/MAC, and whether the speaker had previously been paired with that Mac.

The capture resolves the route if it contains one of these signatures:

| Signature | Classification |
| --- | --- |
| `/auth-setup` then plaintext `ANNOUNCE -> SETUP -> RECORD`, without `/pair-*` | RAOP compatibility |
| `/pair-setup` or `/pair-verify`, then length-prefixed encrypted control frames; PTP on UDP 319/320 | native AirPlay 2/HAP |
| `/fp-setup` phase 1/2 then `ANNOUNCE` with `a=fpaeskey` | FairPlay-gated RAOP |

For the implementation ticket, retain:

- ordered request methods, paths, status codes, and TCP connection boundaries;
- all plaintext headers and request/response bodies, with binary bodies saved intact;
- pairing mode and `X-Apple-HKP` values;
- whether post-pairing control becomes encrypted and the first encrypted-frame sizes;
- PTP packet presence, direction, clock identity, and UDP ports;
- session and stream `SETUP` plists if logs expose them;
- stream type, codec, audio transport, negotiated data/control ports, and shutdown sequence.

A native HAP packet capture cannot reveal encrypted `SETUP` bodies without session keys. If the unified log does not expose them, the follow-up proof must run an instrumented open-source sender against this target and save its decoded request/response plists. Do not infer those bodies from ciphertext.

## Implication for the next decision

The next ticket should compare two target-specific implementation routes, not FairPlay:

- minimal RAOP compatibility: replace the incorrect `/fp-setup` probe with `/auth-setup` for `et=4`, then validate the existing RAOP pipeline;
- native AirPlay 2: transient HAP, encrypted RTSP, PTP, binary-plist setup, and protected audio.

The Apple capture decides which route reproduces Apple's behavior. A controlled `/auth-setup` validation decides whether the smaller compatibility route is sufficient for the product destination.

## Sources

[^map]: [Wayfinder map: current target evidence](../../.scratch/target-airplay-receiver/map.md).
[^denon]: Denon, [“AirPlay function — DENON HOME 250”](https://manuals.denon.com/denonhome250/EU/en/DRDZSYtarpvkoi.php), stating that the model supports AirPlay 2.
[^pyatv-et]: pyatv source, [`get_encryption_types`: `0` unencrypted, `1` RSA, `3` FairPlay, `4` MFiSAP, `5` FairPlay SAPv2.5](https://github.com/postlund/pyatv/blob/b277a4c8222ecdcbaab8a24e3e713ca44765adb4/pyatv/protocols/raop/parsers.py#L49-L71).
[^pyatv-auth]: pyatv source, [`auth_setup`: unencrypted selector plus Curve25519 public key](https://github.com/postlund/pyatv/blob/b277a4c8222ecdcbaab8a24e3e713ca44765adb4/pyatv/support/rtsp.py#L112-L123), and [protocol trace for the 33-byte request](https://github.com/postlund/pyatv/blob/b277a4c8222ecdcbaab8a24e3e713ca44765adb4/docs/documentation/protocols.md#L1468-L1504).
[^owntone-raop]: OwnTone source: [`et=4` enables `/auth-setup`](https://github.com/owntone/owntone-server/blob/e58a9bc968162096ad68e7bb52eeaa8de2f3e953/src/outputs/raop.c#L4441-L4451), [the 33-byte request](https://github.com/owntone/owntone-server/blob/e58a9bc968162096ad68e7bb52eeaa8de2f3e953/src/outputs/raop.c#L1641-L1697), and [`OPTIONS -> /auth-setup -> ANNOUNCE`](https://github.com/owntone/owntone-server/blob/e58a9bc968162096ad68e7bb52eeaa8de2f3e953/src/outputs/raop.c#L3646-L3661).
[^owntone-features]: OwnTone source, [AirPlay 2 feature-bit map](https://github.com/owntone/owntone-server/blob/e58a9bc968162096ad68e7bb52eeaa8de2f3e953/src/outputs/airplay.c#L391-L430) and [native-route requirements](https://github.com/owntone/owntone-server/blob/e58a9bc968162096ad68e7bb52eeaa8de2f3e953/src/outputs/airplay.c#L4035-L4070).
[^receiver-features]: openairplay receiver source, [feature-bit definitions](https://github.com/openairplay/airplay2-receiver/blob/6c343d3679ddb561c61566985acaaf587d0a3bd3/ap2/bitflags.py#L59-L147).
[^cli-routing]: Music Assistant `airplay-cli` source, [feature parsing and route selection](https://github.com/music-assistant/airplay-cli/blob/43e4c33c833c1756a22feb3a5167d3112f82cce2/src/ap2_client.c#L1194-L1238) and [native/transient/PTP decision](https://github.com/music-assistant/airplay-cli/blob/43e4c33c833c1756a22feb3a5167d3112f82cce2/src/ap2_client.c#L1345-L1424).
[^cli-native]: Music Assistant `airplay-cli` source, [native `GET /info`, HAP, and timing sequence](https://github.com/music-assistant/airplay-cli/blob/43e4c33c833c1756a22feb3a5167d3112f82cce2/src/ap2_client.c#L1800-L1931), [session/stream setup](https://github.com/music-assistant/airplay-cli/blob/43e4c33c833c1756a22feb3a5167d3112f82cce2/src/ap2_client.c#L1931-L2128), and [PTP peer setup](https://github.com/music-assistant/airplay-cli/blob/43e4c33c833c1756a22feb3a5167d3112f82cce2/src/ap2_client.c#L2260-L2284).
