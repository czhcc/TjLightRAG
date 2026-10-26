#!/usr/bin/env bash
set -euo pipefail
mkdir -p /workspace/tools /workspace/.docker
export DOCKER_CONFIG=/workspace/.docker
if [ ! -x /workspace/tools/jdk21/bin/javac ]; then
  docker pull maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320
  task_container=$(docker create maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320)
  trap 'docker rm "$task_container" >/dev/null' EXIT
  docker cp "$task_container":/opt/java/openjdk /workspace/tools/jdk21
  docker rm "$task_container" >/dev/null
  trap - EXIT
fi
python - <<'PY'
import os, urllib.request, urllib.parse, hashlib, io, tarfile
from pathlib import Path
from xml.sax.saxutils import escape
if not Path('/workspace/tools/apache-maven-3.10.0/bin/mvn').exists():
    url='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.tar.gz'
    data=urllib.request.urlopen(url).read()
    assert hashlib.sha512(data).hexdigest()==urllib.request.urlopen(url+'.sha512').read().decode().strip().split()[0]
    tarfile.open(fileobj=io.BytesIO(data),mode='r:gz').extractall('/workspace/tools',filter='data')
proxy=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY',''))
settings='<settings>'
if proxy.hostname:
    settings+='<proxies><proxy><id>cloud</id><active>true</active><protocol>http</protocol><host>'+escape(proxy.hostname)+'</host><port>'+str(proxy.port or 80)+'</port></proxy></proxies>'
Path('/workspace/tools/maven-settings.xml').write_text(settings+'</settings>')
PY
source /workspace/TjLightRAG/docs/scripts/cloud-env.sh
cd /workspace/TjLightRAG/frontend
pnpm install --frozen-lockfile --store-dir /workspace/.pnpm-store
pnpm test
pnpm build
cd ../backend
mvn -B -DskipTests install
