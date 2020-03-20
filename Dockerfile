FROM clojure:openjdk-11-tools-deps-1.10.1.502 AS BASE

# Setup GraalVM
RUN apt-get update
RUN apt-get install --no-install-recommends -yy curl unzip build-essential zlib1g-dev
WORKDIR "/opt"
RUN curl -sLO https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-19.3.1/graalvm-ce-java11-linux-amd64-19.3.1.tar.gz
RUN tar -xzf graalvm-ce-java11-linux-amd64-19.3.1.tar.gz
ENV GRAALVM_HOME="/opt/graalvm-ce-java11-19.3.1"
RUN $GRAALVM_HOME/bin/gu install native-image

# Cache dependencies
COPY ./deps.edn ./deps.edn
RUN clojure -R:test:native-image -e ""
COPY . .

# Run tests
RUN clojure -Atest

# Build binary
ARG GIT_REF
RUN clojure -Anative-image -Dversion=$(echo $GIT_REF | cut -d/ -f3-)


# Create minimal image
FROM frolvlad/alpine-glibc:alpine-3.11_glibc-2.30
COPY --from=BASE /opt/prometheus_pushgateway_cleaner /usr/bin/prometheus_pushgateway_cleaner
ENTRYPOINT ["/usr/bin/prometheus_pushgateway_cleaner"]
