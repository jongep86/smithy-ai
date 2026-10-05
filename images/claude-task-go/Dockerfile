ARG BASE_IMAGE=claude-task-base:latest
FROM ${BASE_IMAGE}

# Go toolchain, golangci-lint, and gcc for cgo and -race tests
ARG TARGETARCH
ARG GO_VERSION=1.26.4

RUN curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-${TARGETARCH}.tar.gz" \
      | tar -C /usr/local -xz
ENV PATH="/usr/local/go/bin:/root/go/bin:${PATH}"

RUN curl -fsSL https://raw.githubusercontent.com/golangci/golangci-lint/HEAD/install.sh \
      | sh -s -- -b /root/go/bin

RUN apt-get update && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
CMD ["sleep", "infinity"]
