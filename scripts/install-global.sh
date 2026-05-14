#!/bin/bash

# Mutation AI Studio - Instalador Global para Fedora/Linux
# Este script cria um symlink em /usr/local/bin para usar 'mutation-ai' de qualquer lugar

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
JAR_FILE="$PROJECT_ROOT/target/mutation-ai-studio-0.0.1-SNAPSHOT.jar"
BIN_WRAPPER="$PROJECT_ROOT/bin/mutation-ai"

echo "🚀 Instalando Mutation AI Studio globalmente..."
echo ""

# Verifica se está no diretório correto
if [ ! -f "$PROJECT_ROOT/pom.xml" ]; then
    echo "❌ Erro: Não está no diretório raiz do Mutation AI Studio"
    exit 1
fi

# Compila o projeto se ainda não foi feito
if [ ! -f "$JAR_FILE" ]; then
    echo "📦 Compilando projeto..."
    cd "$PROJECT_ROOT"
    ./mvnw clean package -DskipTests
fi

# Cria o diretório bin se não existir
mkdir -p "$PROJECT_ROOT/bin"

# Cria o script wrapper
cat > "$BIN_WRAPPER" << 'EOF'
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
JAR_FILE="$PROJECT_ROOT/target/mutation-ai-studio-0.0.1-SNAPSHOT.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "❌ Erro: JAR não encontrado em $JAR_FILE"
    echo "Execute: cd $PROJECT_ROOT && ./mvnw clean package"
    exit 1
fi

java -jar "$JAR_FILE" "$@"
EOF

chmod +x "$BIN_WRAPPER"

# Cria symlink global (requer sudo)
echo "🔐 Criando symlink em /usr/local/bin (pode pedir sua senha)..."
sudo ln -sf "$BIN_WRAPPER" /usr/local/bin/mutation-ai

echo ""
echo "✅ Instalação completa!"
echo ""
echo "Agora você pode usar em qualquer lugar:"
echo "  $ mutation-ai scan ."
echo "  $ mutation-ai select ."
echo "  $ mutation-ai status"
echo ""
echo "Para desinstalar:"
echo "  sudo rm /usr/local/bin/mutation-ai"
