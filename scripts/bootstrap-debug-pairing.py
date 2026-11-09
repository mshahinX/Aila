#!/usr/bin/env python3
"""Send an approved dedicated device token over stdin into this debug app's private directory.
No token in argv, logs, public phone storage, test actions, or APK assets. No Accessibility grant.
Use python3 -I. The user must have separately approved cloud consent before passing its flag.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys

p=argparse.ArgumentParser(description=__doc__)
p.add_argument("--serial",required=True)
p.add_argument("--token-file",type=pathlib.Path,required=True)
p.add_argument("--approved-cloud-consent",action="store_true")
a=p.parse_args()


def adb(*args, data=None):
    result=subprocess.run(["/usr/bin/adb","-s",a.serial,*args],input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=40)
    if result.returncode:
        raise RuntimeError("Private USB operation failed; complete setup manually")
    return result.stdout


try:
    token=a.token_file.read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}",token):
        raise RuntimeError("Dedicated device token format is invalid")
    # Static command only. Secret bytes cross an anonymous pipe and authenticated USB,
    # then enter a mode-0600 file owned by the app UID, not /sdcard or /data/local/tmp.
    payload=json.dumps({"token":token,"cloudConsent":a.approved_cloud_consent}).encode("utf-8")
    command="run-as dev.personal.phoneagent sh -c 'umask 077; mkdir -p files; test ! -e files/.usb-pairing.pending && test ! -L files/.usb-pairing.pending && cat > files/.usb-pairing.pending'"
    adb("shell","-T",command,data=payload)
    adb("reverse","tcp:8787","tcp:8787")
    adb("shell","am","start","-S","-W","-n","dev.personal.phoneagent/.ui.MainActivity")
    # Inspect encrypted-state presence and import deletion, never print their contents.
    raw=adb("exec-out","run-as","dev.personal.phoneagent","cat","shared_prefs/connection.xml")
    import xml.etree.ElementTree as ET
    root=ET.fromstring(raw)
    values={n.get("name"):n.text for n in root if n.tag=="string"}
    if not values.get("iv") or not values.get("token") or values.get("token")==token:
        raise RuntimeError("Encrypted pairing storage was not verified")
    if values.get("baseUrl")!="http://127.0.0.1:8787":
        raise RuntimeError("Debug backend URL was not verified")
    consent=root.find("boolean[@name='consent']")
    if consent is None or consent.get("value")!=str(a.approved_cloud_consent).lower():
        raise RuntimeError("Requested consent setting was not verified")
    adb("shell","run-as dev.personal.phoneagent test ! -e files/.usb-pairing.pending")
    print("Private USB pairing imported; encrypted storage and temporary-file removal verified.")
    print("Gateway credentials untouched. Live reasoning remains off. Accessibility was not changed.")
except Exception as exc:
    print(str(exc) if isinstance(exc,RuntimeError) else "Pairing paused; private details withheld",file=sys.stderr)
    raise SystemExit(1)
