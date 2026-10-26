"""Build with the cloud proxy and trusted CA mounted only for build steps."""
import os
import socket
import subprocess
from urllib.parse import urlparse

args = ['docker', 'build', '--network', 'host', '--build-arg', 'HTTPS_PROXY', '--build-arg', 'HTTP_PROXY']
proxy = urlparse(os.environ.get('HTTPS_PROXY', ''))
if proxy.hostname:
    args += ['--add-host', f'{proxy.hostname}:{socket.gethostbyname(proxy.hostname)}']
args += ['--secret', 'id=proxy_ca,src=/usr/local/share/ca-certificates/environment-proxy-ca.crt',
         '--secret', 'id=maven_settings,src=/workspace/tools/maven-settings.xml',
         '--secret', 'id=java_cacerts,src=/etc/ssl/certs/java/cacerts',
         '-f', 'docker/Dockerfile', '-t', 'tjlightrag:local', '.']
subprocess.run(args, cwd='/workspace/TjLightRAG', check=True)
