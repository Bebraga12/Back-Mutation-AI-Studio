#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

BIN_DIR="${MUTATION_AI_BIN_DIR:-$HOME/.local/bin}"
DATA_DIR="${MUTATION_AI_DATA_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/mutation-ai-studio}"
CONFIG_DIR="${MUTATION_AI_CONFIG_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/mutation-ai}"
CONFIG_FILE="$CONFIG_DIR/config.env"
JAR_TARGET="$DATA_DIR/mutation-ai-studio.jar"
LAUNCHER_TARGET="$BIN_DIR/mutation-ai"
SYSTEM_LAUNCHER="/usr/local/bin/mutation-ai"
DEFAULT_MODEL="${MUTATION_AI_OLLAMA_MODEL:-qwen2.5-coder:7b}"

log() {
  printf '%s\n' "$*"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

confirm() {
  local prompt="${1:-Continuar}"
  local answer
  if [ "${MUTATION_AI_ASSUME_NO:-0}" = "1" ]; then
    return 1
  fi
  if [ "${MUTATION_AI_ASSUME_YES:-0}" = "1" ]; then
    return 0
  fi
  if [ ! -t 0 ]; then
    return 1
  fi
  printf '%s [S/n] ' "$prompt"
  read -r answer
  case "${answer:-S}" in
    n|N|no|No|NO) return 1 ;;
    *) return 0 ;;
  esac
}

choose_model() {
  if [ -n "${MUTATION_AI_OLLAMA_MODEL:-}" ]; then
    printf '%s\n' "$MUTATION_AI_OLLAMA_MODEL"
    return 0
  fi

  if [ ! -t 0 ]; then
    printf '%s\n' "$DEFAULT_MODEL"
    return 0
  fi

  log "Choose the Ollama model for Mutation AI Studio."
  log "1) qwen2.5-coder:7b"
  log "2) deepseek-coder:6.7b"
  log "3) custom"
  printf 'Option [1-3] (default 1): '
  local choice model
  read -r choice
  case "${choice:-1}" in
    1) model="qwen2.5-coder:7b" ;;
    2) model="deepseek-coder:6.7b" ;;
    3)
      printf 'Model name: '
      read -r model
      [ -n "$model" ] || model="$DEFAULT_MODEL"
      ;;
    *) model="$DEFAULT_MODEL" ;;
  esac
  printf '%s\n' "$model"
}

ensure_java() {
  command -v java >/dev/null 2>&1 || die "java was not found in PATH."
}

ensure_curl() {
  command -v curl >/dev/null 2>&1 || die "curl was not found in PATH."
}

install_ollama_if_needed() {
  if command -v ollama >/dev/null 2>&1; then
    log "Ollama already installed."
    return 0
  fi

  log "Ollama not found."
  if ! confirm "Install Ollama from the official installer"; then
    die "Ollama is required to run Mutation AI Studio."
  fi

  log "Installing Ollama from the official installer."
  curl -fsSL https://ollama.com/install.sh | sh

  command -v ollama >/dev/null 2>&1 || die "Ollama installation finished, but the ollama command is still unavailable."
}

start_ollama_server_if_needed() {
  if ollama list >/dev/null 2>&1; then
    return 0
  fi

  log "Starting Ollama server in the background."
  nohup ollama serve >/tmp/mutation-ai-ollama.log 2>&1 &

  local attempt
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    if ollama list >/dev/null 2>&1; then
      return 0
    fi
  done

  die "Could not reach Ollama server. Start it manually and rerun the installer."
}

build_project() {
  if [ "${MUTATION_AI_SKIP_BUILD:-0}" = "1" ]; then
    log "Skipping project build."
    return 0
  fi
  log "Building Mutation AI Studio."
  (cd "$REPO_ROOT" && ./mvnw -q -DskipTests package)
}

locate_jar() {
  if [ -n "${MUTATION_AI_JAR_SOURCE:-}" ]; then
    [ -f "$MUTATION_AI_JAR_SOURCE" ] || die "MUTATION_AI_JAR_SOURCE points to a missing file."
    printf '%s\n' "$MUTATION_AI_JAR_SOURCE"
    return 0
  fi

  local jar
  jar="$(find "$REPO_ROOT/target" -maxdepth 1 -type f -name 'mutation-ai-studio-*.jar' ! -name 'original-*' | sort | tail -n 1)"
  [ -n "$jar" ] || die "Build finished, but the jar was not found in target/."
  printf '%s\n' "$jar"
}

install_launcher() {
  mkdir -p "$BIN_DIR" "$DATA_DIR" "$CONFIG_DIR"

  cp "$1" "$JAR_TARGET"

  cat > "$LAUNCHER_TARGET" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${MUTATION_AI_CONFIG_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/mutation-ai/config.env}"
if [ -f "$CONFIG_FILE" ]; then
  # shellcheck disable=SC1090
  . "$CONFIG_FILE"
fi

JAR_PATH="${MUTATION_AI_JAR:-${XDG_DATA_HOME:-$HOME/.local/share}/mutation-ai-studio/mutation-ai-studio.jar}"
exec java -jar "$JAR_PATH" "$@"
EOF

  chmod +x "$LAUNCHER_TARGET"

  if command -v sudo >/dev/null 2>&1 && ( [ "${MUTATION_AI_INSTALL_SYSTEM:-0}" = "1" ] || confirm "Create a system-wide link in /usr/local/bin" ); then
    sudo ln -sf "$LAUNCHER_TARGET" "$SYSTEM_LAUNCHER"
  fi
}

prompt_initial_scan() {
  if [ ! -t 0 ]; then
    return 0
  fi

  log "Primeiro passo: rode o scan do projeto atual antes de continuar."
  log "Comando:"
  log "  mutation-ai scan ."
  log "Se o PATH ainda nao estiver atualizado, use:"
  log "  $LAUNCHER_TARGET scan ."
  printf 'Depois de executar o scan, pressione Enter para continuar... '
  read -r _
}

write_config() {
  local model="$1"
  mkdir -p "$CONFIG_DIR"
  cat > "$CONFIG_FILE" <<EOF
MUTATION_AI_OLLAMA_BASE_URL=http://localhost:11434
MUTATION_AI_OLLAMA_MODEL=$model
EOF
}

main() {
  ensure_java
  ensure_curl

  local jar model
  build_project
  jar="$(locate_jar)"

  install_launcher "$jar"
  prompt_initial_scan

  install_ollama_if_needed
  start_ollama_server_if_needed

  model="$(choose_model)"

  log "Pulling model: $model"
  ollama pull "$model"

  write_config "$model"

  log "Installation complete."
  log "Launcher: $LAUNCHER_TARGET"
  log "Config: $CONFIG_FILE"
  log "Data jar: $JAR_TARGET"
  log "Selected model: $model"
  log "Run: mutation-ai scan ."
}

main "$@"
