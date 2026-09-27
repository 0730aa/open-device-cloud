#!/usr/bin/env python3
"""Assembles the Windows bundle for trying the platform out on one PC (see bundle/README.txt).

Run from the repository root after building the server, the agent (-Dplatform=windows-x86_64)
and the web client, and after download-tools.sh:

    python3 packaging/windows/assemble.py --tools <download dir> --db-check <dir with DbCheck.class> --out <dir>

The four server components are Spring Boot jars that share most of their libraries, so each one
is unpacked and the libraries are kept once, which makes the bundle about 150 MB smaller. A
component then runs from its classes and the libraries in the order its jar lists them
(java @apps/<name>/java.args), the unpacked layout Spring Boot documents.
"""
import argparse
import hashlib
import re
import shutil
import sys
import tarfile
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
SERVER_APPS = ["eureka", "controller", "folder", "gateway"]
AGENT_KEY_PLACEHOLDER = "PASTE-YOUR-AGENT-KEY-HERE"


def fail(message):
    sys.exit("assemble: " + message)


def one(directory, pattern):
    matches = list(directory.glob(pattern))
    if len(matches) != 1:
        fail(f"expected one {directory / pattern}, found {len(matches)}")
    return matches[0]


def extract(archive, target, strip_top):
    """Extracts a zip, leaving out its top folder when strip_top is set."""
    with zipfile.ZipFile(archive) as z:
        top = z.namelist()[0].split("/")[0] + "/" if strip_top else ""
        for info in z.infolist():
            relative = info.filename[len(top):]
            if info.is_dir() or not info.filename.startswith(top) or not relative:
                continue
            path = target / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(z.read(info))


def unpack_server(out):
    server = out / "server"
    lib = server / "lib"
    lib.mkdir(parents=True)
    digests = {}
    for app in SERVER_APPS:
        jar = REPO / "sonic-server" / f"sonic-server-{app}" / "target" / f"sonic-server-{app}.jar"
        classes = server / "apps" / app / "classes"
        with zipfile.ZipFile(jar) as z:
            manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8")
            start_class = re.search(r"^Start-Class: (\S+)", manifest, re.M)
            if not start_class:
                fail(f"{jar.name} has no Start-Class")
            order = re.findall(r'^- "BOOT-INF/lib/([^"/]+\.jar)"$', z.read("BOOT-INF/classpath.idx").decode("utf-8"), re.M)
            libs = set()
            for info in z.infolist():
                name = info.filename
                if info.is_dir():
                    continue
                if name.startswith("BOOT-INF/classes/"):
                    target = classes / name[len("BOOT-INF/classes/"):]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(z.read(info))
                elif name.startswith("BOOT-INF/lib/"):
                    jar_name = name[len("BOOT-INF/lib/"):]
                    data = z.read(info)
                    digest = hashlib.sha256(data).hexdigest()
                    if jar_name in digests:
                        if digests[jar_name] != digest:
                            fail(f"two different {jar_name} in the server jars")
                    else:
                        digests[jar_name] = digest
                        (lib / jar_name).write_bytes(data)
                    libs.add(jar_name)
            if set(order) != libs:
                fail(f"classpath.idx of {jar.name} does not list exactly its libraries")
        classpath = ";".join([f"apps/{app}/classes"] + [f"lib/{name}" for name in order])
        # Paths are relative to the server folder, which is the working directory of every component.
        (server / "apps" / app / "java.args").write_text(f"-cp {classpath}\n{start_class.group(1)}\n", encoding="ascii", newline="\r\n")
    return digests


def add_web_client(out, tools):
    nginx = out / "server" / "nginx"
    ours = (nginx / "conf" / "nginx.conf").read_bytes()
    extract(one(tools / "nginx", "nginx-*.zip"), nginx, strip_top=True)
    # The bundle's own configuration and the web client replace nginx's samples.
    (nginx / "conf" / "nginx.conf").write_bytes(ours)
    shutil.rmtree(nginx / "html")
    shutil.copytree(REPO / "sonic-client-web" / "dist", nginx / "html")


def add_agent(out, tools):
    agent = out / "agent"
    agent.mkdir()
    shutil.copy2(REPO / "sonic-agent" / "target" / "sonic-agent-windows-x86_64.jar", agent)
    shutil.copytree(REPO / "sonic-agent" / "mini", agent / "mini")
    shutil.copytree(REPO / "sonic-agent" / "plugins", agent / "plugins")

    # The repository's config, pointed at the server on this PC and without the sample key.
    config = (REPO / "sonic-agent" / "config" / "application-sonic-agent.yml").read_text(encoding="utf-8")
    for old, new, count in [("    host: 192.168.1.1\n", "    host: 127.0.0.1\n", 2),
                            ("    key: 5aa13292-b9a8-408c-a091-d784d1f37472\n", f"    key: {AGENT_KEY_PLACEHOLDER}\n", 1)]:
        if config.count(old) != count:
            fail(f"expected {count} x {old.strip()!r} in the agent config")
        config = config.replace(old, new)
    (agent / "config").mkdir()
    (agent / "config" / "application-sonic-agent.yml").write_text(config, encoding="utf-8", newline="\r\n")

    plugins = agent / "plugins"
    extract(one(tools / "adb", "platform-tools_*-windows.zip"), plugins, strip_top=True)
    for folder, binary, target in [("sas", "sas.exe", "sonic-android-supply.exe"),
                                   ("sib", "sib.exe", "sonic-ios-bridge.exe"),
                                   ("sgm", "sonic-go-mitmproxy.exe", "sonic-go-mitmproxy.exe")]:
        with tarfile.open(one(tools / folder, "*_windows_x86_64.tar.gz")) as t:
            members = [m for m in t.getmembers() if m.isfile() and Path(m.name).name == binary]
            if len(members) != 1:
                fail(f"expected one {binary} in the {folder} archive")
            (plugins / target).write_bytes(t.extractfile(members[0]).read())
    for required in ["adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "sonic-android-apk.apk", "sonic-android-scrcpy.jar",
                     "sonic-appium-uiautomator2-server.apk", "sonic-appium-uiautomator2-server-test.apk"]:
        if not (plugins / required).is_file():
            fail(f"agent/plugins/{required} is missing")


def add_runtime(out, tools):
    extract(one(tools / "jre", "OpenJDK17U-jre_x64_windows_*.zip"), out / "jre", strip_top=True)
    if not (out / "jre" / "bin" / "java.exe").is_file():
        fail("jre/bin/java.exe is missing")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tools", type=Path, required=True, help="directory download-tools.sh filled")
    parser.add_argument("--db-check", type=Path, required=True, help="directory with the compiled DbCheck.class")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        fail(f"{args.out} already exists")

    shutil.copytree(HERE / "bundle", args.out)
    digests = unpack_server(args.out)
    if not any(name.startswith("mysql-connector-j-") for name in digests):
        fail("the server has no MySQL driver for the database check")
    (args.out / "server" / "tools").mkdir()
    shutil.copy2(args.db_check / "DbCheck.class", args.out / "server" / "tools")
    add_web_client(args.out, args.tools)
    add_agent(args.out, args.tools)
    add_runtime(args.out, args.tools)

    total = sum(f.stat().st_size for f in args.out.rglob("*") if f.is_file())
    print(f"assembled {args.out}: {total / 1e6:.0f} MB, {len(digests)} shared server libraries")


if __name__ == "__main__":
    main()
