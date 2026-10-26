# Source this file in cloud tasks; keeps tool caches outside the checkout.
export JAVA_HOME=/workspace/tools/jdk21
export PATH="$JAVA_HOME/bin:/workspace/tools/apache-maven-3.10.0/bin:$PATH"
export XDG_DATA_HOME=/workspace/.local/share
export XDG_CACHE_HOME=/workspace/.cache
export DOCKER_CONFIG=/workspace/.docker
export MAVEN_ARGS='-s /workspace/tools/maven-settings.xml -Dmaven.repo.local=/workspace/.m2/repository'
# Use the platform-maintained CA store, including the cloud proxy trust anchor.
export MAVEN_OPTS='-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'
