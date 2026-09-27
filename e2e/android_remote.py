#!/usr/bin/env python3
"""Uses an Android device remotely the way the web client does, from end to end.

Registers users and an agent on the running server, starts the agent with its key, waits until the
device attached to this machine (e.g. an emulator) is online in the device center, then gets a
remote ticket and opens the agent's control and screen WebSockets with it, as
sonic-client-web/src/views/RemoteEmulator/AndroidRemote.vue does. Checks that:

- the agent refuses a connection without a valid ticket;
- the screen is streamed (scrcpy's H.264);
- the device is kept for that user meanwhile, and another user gets no ticket for it;
- the device is free again once the connections close.

Needs the gateway on 127.0.0.1:3000, adb with one device, and websocket-client.
Usage: android_remote.py <agent folder> <agent log file>
"""
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

import websocket

API = "http://127.0.0.1:3000/server/api"
AGENT = "ws://127.0.0.1:7777"
PASSWORD = "e2e-password"
REPO = Path(__file__).resolve().parents[1]


def call(method, path, body=None, token=None):
    request = urllib.request.Request(API + path, method=method,
                                     data=None if body is None else json.dumps(body).encode())
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("SonicToken", token)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def wait_for(what, seconds, check):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            result = check()
            if result:
                return result
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(3)
    sys.exit(f"FAILED: {what} (waited {seconds} s)")


def ok(what):
    print(f"ok: {what}", flush=True)


def login(user):
    result = call("POST", "/controller/users/login", {"userName": user, "password": PASSWORD})
    assert result["code"] == 2000, result
    return result["data"]


class Socket:
    """A WebSocket like the browser's, keeping what arrives."""

    def __init__(self, url, first_message=None):
        self.texts = []
        self.binary_bytes = 0
        self.close_code = None
        self.closed = threading.Event()

        def on_open(ws):
            if first_message:
                ws.send(json.dumps(first_message))

        def on_message(ws, message):
            if isinstance(message, bytes):
                self.binary_bytes += len(message)
            else:
                self.texts.append(message)

        def on_close(ws, code, reason):
            self.close_code = code
            self.closed.set()

        self.app = websocket.WebSocketApp(url, on_open=on_open, on_message=on_message, on_close=on_close)
        threading.Thread(target=self.app.run_forever, daemon=True).start()

    def close(self):
        self.app.close()


def agent_config(key):
    config = (REPO / "sonic-agent" / "config" / "application-sonic-agent.yml").read_text(encoding="utf-8")
    config = config.replace("    host: 192.168.1.1\n", "    host: 127.0.0.1\n")
    return config.replace("    key: 5aa13292-b9a8-408c-a091-d784d1f37472\n", f"    key: {key}\n")


def main():
    agent_folder, agent_log = Path(sys.argv[1]), Path(sys.argv[2])

    wait_for("the server is up", 600, lambda: call("GET", "/controller/users/loginConfig")["code"] == 2000)
    ok("the server is up")
    for user in ("sonic", "bob"):
        result = call("POST", "/controller/users/register", {"userName": user, "password": PASSWORD})
        assert result["code"] == 2000, result
    sonic, bob = login("sonic"), login("bob")

    result = call("PUT", "/controller/agents/update", {"id": 0, "name": "e2e", "highTemp": 45, "highTempTime": 15,
                                                        "robotType": -1, "robotToken": "", "robotSecret": "",
                                                        "alertRobotIds": None}, sonic)
    assert result["code"] == 2000, result
    agent = next(a for a in call("GET", "/controller/agents/list", token=sonic)["data"] if a["name"] == "e2e")
    (agent_folder / "config").mkdir(exist_ok=True)
    (agent_folder / "config" / "application-sonic-agent.yml").write_text(agent_config(agent["secretKey"]), encoding="utf-8")
    with agent_log.open("w") as log:
        subprocess.Popen(["java", "-Xmx768m", "-jar", "sonic-agent-linux-x86_64.jar"], cwd=agent_folder,
                         stdout=log, stderr=subprocess.STDOUT)
    wait_for("the agent is online", 300,
             lambda: next(a for a in call("GET", "/controller/agents/list", token=sonic)["data"] if a["name"] == "e2e")["status"] == 1)
    ok("the agent is online")

    def device_list():
        return call("GET", f"/controller/devices/listByAgentId?agentId={agent['id']}", token=sonic)["data"]

    device = wait_for("the device is online in the device center", 300,
                      lambda: next((d for d in device_list() if d["status"] == "ONLINE"), None))
    ok(f"device {device['udId']} ({device.get('model')}, Android {device.get('version')}) is online")
    udid = device["udId"]

    def status():
        return call("GET", f"/controller/devices?id={device['id']}", token=sonic)["data"]

    refused = Socket(f"{AGENT}/websockets/android/screen/not-a-ticket/{udid}")
    refused.closed.wait(30)
    assert refused.close_code == 1008, refused.close_code
    ok("the agent refuses a connection without a valid ticket")

    result = call("GET", f"/controller/devices/remoteTicket?id={device['id']}", token=sonic)
    assert result["code"] == 2000, result
    ticket = result["data"]["ticket"]
    control = Socket(f"{AGENT}/websockets/android/{ticket}/{udid}")
    screen = Socket(f"{AGENT}/websockets/android/screen/{ticket}/{udid}", {"type": "switch", "detail": "scrcpy"})
    wait_for("the screen is streamed", 240, lambda: screen.binary_bytes > 100_000 or screen.closed.is_set())
    assert not screen.closed.is_set(), f"the screen connection closed ({screen.close_code}): {screen.texts}"
    ok(f"the screen is streamed ({screen.binary_bytes} bytes so far)")

    held = wait_for("the device is in use by sonic", 60,
                    lambda: (lambda d: d if d["status"] == "DEBUGGING" and d["user"] == "sonic" else None)(status()))
    ok(f"the device is in use by {held['user']}")
    busy = call("GET", f"/controller/devices/remoteTicket?id={device['id']}", token=bob)
    assert busy["code"] != 2000, busy
    ok(f"another user gets no ticket meanwhile ({busy['code']})")
    assert not control.closed.is_set(), f"the control connection closed ({control.close_code}): {control.texts}"
    ok(f"the control connection is open ({len(control.texts)} messages)")

    control.close()
    screen.close()
    wait_for("the device is free again", 120, lambda: status()["status"] == "ONLINE")
    ok("the device is free again")
    print("Remote control works end to end.", flush=True)


if __name__ == "__main__":
    main()
