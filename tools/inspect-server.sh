#!/usr/bin/env bash
# Read-only deployment inventory. Does not inspect credentials, environments or application data.
set -u
printf '%s\n' '=== OS ==='
uname -srmo
if [ -r /etc/os-release ]; then
  sed -n '/^PRETTY_NAME=/p' /etc/os-release
fi
printf '%s\n' '=== Memory and swap (MiB) ==='
free -m
printf '%s\n' '=== Disk ==='
df -h / /opt /var 2>/dev/null
printf '%s\n' '=== Listening TCP ports ==='
ss -ltn
printf '%s\n' '=== Largest processes: no command arguments ==='
ps -eo pid,comm,rss --sort=-rss | head -n 16
printf '%s\n' '=== Running service names ==='
systemctl list-units --type=service --state=running --no-pager --plain
if command -v docker >/dev/null 2>&1; then
  printf '%s\n' '=== Docker containers: names, images, ports only ==='
  docker ps --format '{{.Names}} | {{.Image}} | {{.Status}} | {{.Ports}}'
  printf '%s\n' '=== Container usage ==='
  docker stats --no-stream --format '{{.Name}} | {{.MemUsage}} | {{.CPUPerc}} | {{.PIDs}}'
fi
printf '%s\n' '=== Runtime commands ==='
for program in java nginx certbot ffmpeg ffprobe; do
  command -v "$program" || true
done
