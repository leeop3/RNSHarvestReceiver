"""
rns_harvest.py — RNS/LXMF backend for RNS Harvest Receiver Android app.

Architecture:
  Kotlin BluetoothRNodeManager  ←→  BtTcpBridge (Kotlin)  ←→  TCP 127.0.0.1:7633
                                                                       ↕
                                                         RNodeInterface (Python RNS)
                                                                       ↕
                                                         LXMRouter / LXMF stack
                                                                       ↕
                                                         on_lxmf() → kotlin_callback
                                                                       ↕
                                                         Kotlin → CsvParser → Room DB

The Python side handles ALL RNS/LXMF protocol including:
  - KISS framing
  - RNS packet decoding
  - Encryption / decryption
  - LXMF message assembly
  - Ratchet key management (transparent)
"""

import os
import time
import threading
import RNS
import LXMF
from LXMF import LXMRouter, LXMessage
from RNS.Interfaces.Android.RNodeInterface import RNodeInterface
from RNS.Interfaces.Interface import Interface

# ── Global state ──────────────────────────────────────────────────────────────
router             = None
local_destination  = None
kotlin_callback    = None
_storage_path      = None
_started           = False
_start_lock        = threading.Lock()

# ── Startup ───────────────────────────────────────────────────────────────────

def start_rns(storage_path, callback_obj):
    """
    Initialise Reticulum and LXMF router.
    Called once from Kotlin after Chaquopy starts.
    Returns the 32-char hex LXMF delivery address to show in the UI.
    """
    global router, local_destination, kotlin_callback, _storage_path, _started

    with _start_lock:
        if _started:
            return RNS.hexrep(local_destination.hash, False) if local_destination else "already_started"
        _started = True

    kotlin_callback = callback_obj
    _storage_path   = str(storage_path)

    rns_dir  = os.path.join(_storage_path, ".reticulum")
    lxmf_dir = os.path.join(_storage_path, ".lxmf")
    for d in [rns_dir, lxmf_dir]:
        os.makedirs(d, exist_ok=True)

    # Initialise Reticulum (no interfaces yet — added by inject_rnode later)
    RNS.Reticulum(configdir=rns_dir, loglevel=RNS.LOG_DEBUG)

    # Load or create persistent identity
    id_path = os.path.join(rns_dir, "harvest_receiver_identity")
    if os.path.exists(id_path):
        local_id = RNS.Identity.from_file(id_path)
        RNS.log(f"Loaded identity: {RNS.hexrep(local_id.hash, False)}")
    else:
        local_id = RNS.Identity()
        local_id.to_file(id_path)
        RNS.log(f"Created identity: {RNS.hexrep(local_id.hash, False)}")

    # Start LXMF router
    router = LXMRouter(
        identity    = local_id,
        storagepath = lxmf_dir,
        autopeer    = False       # No ratchets — senders use plain delivery
    )
    local_destination = router.register_delivery_identity(
        local_id,
        display_name = "RNS Harvest Receiver"
    )
    router.register_delivery_callback(_on_lxmf)

    # Listen for announces from field harvesters
    RNS.Transport.register_announce_handler(_AnnounceHandler())

    # Announce ourselves so field harvesters can discover this receiver
    local_destination.announce()
    addr = RNS.hexrep(local_destination.hash, False)
    RNS.log(f"Harvest Receiver ready. LXMF address: {addr}")
    return addr


# ── RNode injection (called after BT bridge is up) ────────────────────────────

def inject_rnode(freq, bw, tx, sf, cr):
    """
    Attach an RNodeInterface that talks to the BT↔TCP bridge on port 7633.
    The Kotlin BtTcpBridge service must be running before calling this.
    Returns "ONLINE" on success or an error string.
    """
    try:
        ictx = {
            "name":              "HarvestRNode",
            "type":              "RNodeInterface",
            "interface_enabled": True,
            "outgoing":          True,
            "tcp_host":          "127.0.0.1",
            "tcp_port":          7633,
            "frequency":         int(freq),
            "bandwidth":         int(bw),
            "txpower":           int(tx),
            "spreadingfactor":   int(sf),
            "codingrate":        int(cr),
            "flow_control":      False,
        }
        ifac           = RNodeInterface(RNS.Transport, ictx)
        ifac.mode      = Interface.MODE_FULL
        ifac.IN        = True
        ifac.OUT       = True
        RNS.Transport.interfaces.append(ifac)
        time.sleep(1.0)

        # Re-announce now that we have a radio interface
        if local_destination:
            local_destination.announce()
            RNS.log("Re-announced after RNode inject")

        return "ONLINE"
    except Exception as e:
        RNS.log(f"inject_rnode error: {e}")
        return str(e)


# ── LXMF message received ─────────────────────────────────────────────────────

def _on_lxmf(lxm):
    """
    Called by LXMRouter whenever a complete LXMF message is received.
    Extracts content and forwards to Kotlin via callback.
    """
    try:
        sender = RNS.hexrep(lxm.source_hash, False)

        # Get message content
        content = ""
        try:
            content = lxm.content_as_string()
        except Exception:
            try:
                raw = lxm.content
                if isinstance(raw, (bytes, bytearray)):
                    content = raw.decode("utf-8", errors="replace")
                elif raw:
                    content = str(raw)
            except Exception:
                pass

        title = ""
        try:
            title = lxm.title_as_string() or ""
        except Exception:
            pass

        RNS.log(f"LXMF from {sender}: title='{title}' content={len(content)}ch")

        if not content and not title:
            return

        # Prefer content; fall back to title for short messages
        payload = content if content else title

        # Deliver to Kotlin
        if kotlin_callback:
            kotlin_callback.onCsvReceived(sender, payload)

    except Exception as e:
        RNS.log(f"_on_lxmf error: {e}")


# ── Announce handler ──────────────────────────────────────────────────────────

class _AnnounceHandler:
    aspect_filter = None   # catch all announces

    def received_announce(self, dest_hash, announced_identity, app_data):
        try:
            hash_str = RNS.hexrep(dest_hash, False)
            name = ""
            if app_data:
                try:
                    name = app_data.decode("utf-8", errors="replace")
                except Exception:
                    pass
            RNS.log(f"Announce from {hash_str} name='{name}'")
            if kotlin_callback:
                kotlin_callback.onAnnounceReceived(hash_str, name)
        except Exception as e:
            RNS.log(f"announce handler error: {e}")


# ── Utility ───────────────────────────────────────────────────────────────────

def get_address():
    """Return own LXMF delivery address (32-char hex)."""
    if local_destination:
        return RNS.hexrep(local_destination.hash, False)
    return ""

def announce():
    """Manually trigger a re-announce."""
    if local_destination:
        local_destination.announce()
        return "OK"
    return "Not ready"

def is_ready():
    return local_destination is not None
