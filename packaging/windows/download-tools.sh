#!/usr/bin/env bash
# Downloads the third-party Windows programs the bundle includes into the given directory: nginx,
# Android's adb, Sonic's helper programs at the versions the agent checks for (sonic.sas / sib / sgm
# in sonic-agent/src/main/resources/application.yml) and a Java 17 runtime. Needs `gh` with a token.
set -euo pipefail

NGINX_VERSION=1.28.0
ADB_RELEASE=r34.0.3
SAS_RELEASE=v0.1.12
SIB_RELEASE=v1.3.20
SGM_RELEASE=v1.3.4

out=$1
mkdir -p "$out"/{nginx,adb,sas,sib,sgm,jre}
cd "$out"

curl -fsSL --retry 3 -o "nginx/nginx-$NGINX_VERSION.zip" "https://nginx.org/download/nginx-$NGINX_VERSION.zip"
gh release download "$ADB_RELEASE" -R SonicCloudOrg/sonic-adb-binary -p "platform-tools_$ADB_RELEASE-windows.zip" -D adb
gh release download "$SAS_RELEASE" -R SonicCloudOrg/sonic-android-supply -p '*_windows_x86_64.tar.gz' -D sas
gh release download "$SIB_RELEASE" -R SonicCloudOrg/sonic-ios-bridge -p '*_windows_x86_64.tar.gz' -D sib
gh release download "$SGM_RELEASE" -R SonicCloudOrg/sonic-go-mitmproxy -p '*_windows_x86_64.tar.gz' -D sgm
sha256sum nginx/* adb/* sas/* sib/* sgm/*

# The latest Java 17 update, checked against the checksum Adoptium publishes for it.
curl -fsSL --retry 3 -o jre/assets.json \
  'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse'
read -r name link checksum < <(python3 -c '
import json
package = json.load(open("jre/assets.json"))[0]["binary"]["package"]
print(package["name"], package["link"], package["checksum"])')
rm jre/assets.json
curl -fsSL --retry 3 -o "jre/$name" "$link"
echo "$checksum  jre/$name" | sha256sum -c -
