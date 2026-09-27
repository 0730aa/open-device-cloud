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
# The files these versions had when the bundle was first built; a changed file stops the build.
sha256sum -c - <<'SUMS'
db8c7a529f84c819702bd1c50926b27d961a48b4f72fc7c46b30314fc2bbfd7c  nginx/nginx-1.28.0.zip
067637886611209e8347bf4f25592b4ac537198214ec67dc569c649a7361c63c  adb/platform-tools_r34.0.3-windows.zip
811dc868a2680764ddff9d78b8293484b909a72da489dd7b8abb85d4da678f6a  sas/sonic-android-supply_0.1.12_windows_x86_64.tar.gz
4828a27119786c698f8dfe4e0cd2ff4a04dcfd1717b695420ea87c9e9307a250  sib/sonic-ios-bridge_1.3.20_windows_x86_64.tar.gz
9091dbcc03838c75b056e623d71ca4cde8bbb538dd625200b2f1756c530a1858  sgm/sonic-go-mitmproxy_1.3.4_windows_x86_64.tar.gz
SUMS

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
