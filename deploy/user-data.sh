#!/bin/bash
# EC2(Amazon Linux 2023) 최초 부팅 시 Docker + Docker Compose plugin을 자동 설치한다.
# 매번 수동 설치하지 않아도 재현 가능하도록 user-data로 넘긴다.
set -eux

dnf update -y
dnf install -y docker java-17-amazon-corretto-headless

systemctl enable docker
systemctl start docker

usermod -aG docker ec2-user

# Docker Compose v2 plugin (AL2023 dnf 저장소에 없어 GitHub 릴리스 바이너리로 설치)
COMPOSE_VERSION="v2.29.7"
mkdir -p /usr/libexec/docker/cli-plugins
curl -SL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-x86_64" \
  -o /usr/libexec/docker/cli-plugins/docker-compose
chmod +x /usr/libexec/docker/cli-plugins/docker-compose

docker --version
docker compose version
