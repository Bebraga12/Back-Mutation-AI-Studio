# Mutation AI Studio

Plataforma local para geração automatizada de testes unitários em projetos Java, com foco em qualidade (cobertura e mutation testing) e arquitetura hexagonal.

> Status atual (CLI): `scan`, `select` (alias `s`), `status` e a base do fluxo `create test`.

---
 
## Visão Geral

O Mutation AI Studio foi projetado para:

- escanear classes Java de um projeto alvo
- selecionar e persistir alvos para futura geração de testes
- visualizar o estado atual da seleção
- (próximas etapas) gerar testes JUnit 5 + Mockito com IA local

Tudo com execução local, sem dependência de cloud.

---

## Stack

- Java 21
- Spring Boot 3.5.x
- Maven Wrapper (`./mvnw`)
- Arquitetura Hexagonal (Ports and Adapters)

---

## Como rodar

```bash
./mvnw clean install
```

## Instalacao

O projeto inclui um instalador interativo para preparar a CLI em uma maquina nova.

```bash
bash scripts/install.sh
```

O instalador faz o seguinte:

- compila o projeto e instala o launcher `mutation-ai`
- executa `mutation-ai scan .` na raiz do projeto antes de continuar
- instala o Ollama pela distribuicao oficial, se necessario
- pergunta qual modelo usar
- faz o download do modelo escolhido
- grava a configuracao em `~/.config/mutation-ai/config.env`

Se preferir, o instalador tambem pode criar um link em `/usr/local/bin` usando `sudo`.

## Usar a CLI

Depois da instalacao, use o comando de qualquer diretorio:

```bash
mutation-ai scan .
mutation-ai select .
mutation-ai status .
```

### Ajuste manual do PATH

Se quiser carregar o ambiente manualmente no terminal atual:

```bash
source scripts/env.sh
mutation-ai scan .
```

Para deixar permanente, adicione `source <caminho>/Mutation-AI-Studio/scripts/env.sh` no seu `~/.zshrc` ou `~/.bashrc`.

### Alterar o modelo da IA

O modelo usado pelo Ollama fica em:

```bash
~/.config/mutation-ai/config.env
```

Voce tambem pode reinstalar e escolher outro modelo com `bash scripts/install.sh`.

### Instalação no Windows

No Windows, o instalador local fica em `scripts/windows/install.ps1` e instala o app em `%LOCALAPPDATA%\Mutation AI Studio`.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/windows/install.ps1
```

Se quiser sobrescrever uma instalação anterior:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/windows/install.ps1 -Force
```

Desinstalação:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/windows/uninstall.ps1
```

O instalador adiciona `mutation-ai` ao `PATH` do usuário apontando para um launcher `.cmd`.

### Gerar instalador `.exe` no Windows

Se você estiver em um Windows com JDK 21 + WiX Toolset instalado, pode gerar um instalador nativo com `jpackage`:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/windows/package-installer.ps1
```

Ou, se preferir o wrapper `.cmd`:

```bat
scripts\windows\package-installer.cmd
```

Saída esperada:
- um instalador `.exe` em `target/windows-installer/`
- pacote com launcher do Spring Boot embutido

Se quiser recriar do zero:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/windows/package-installer.ps1 -Force
```

Observação:
- esse passo precisa ser executado no Windows
- o `jpackage` para `.exe` depende do WiX Toolset 3.11+ instalado

---

## CLI disponível

## 1) `scan`

Escaneia classes elegíveis em `src/main/java` do projeto alvo.

```bash
mutation-ai scan
mutation-ai scan .
mutation-ai scan /caminho/projeto-alvo
```

### Variações do scan

```bash
mutation-ai scan --focus testable
mutation-ai scan --verbose
mutation-ai scan service
mutation-ai scan controller
mutation-ai scan dto --verbose
mutation-ai scan /caminho/projeto-alvo service
```

Categorias suportadas em `scan <categoria>`:

- `service`
- `core`
- `controller`
- `repository`
- `entity`
- `dto`
- `config`
- `security`
- `other`

---

## 2) `select` e alias `s`

Seleciona alvos e persiste no projeto.

### Selecionar tudo

```bash
mutation-ai select .
mutation-ai s .
mutation-ai select /caminho/projeto-alvo
```

### Selecionar apenas uma classe

Por nome simples ou FQCN no projeto atual:

```bash
mutation-ai select UserService
mutation-ai select br.com.exemplo.service.UserService
```

Com projeto explícito:

```bash
mutation-ai select /caminho/projeto-alvo UserService
```

### Remover da seleção

Remover classe específica:

```bash
mutation-ai select --remove UserService
mutation-ai select --remove br.com.exemplo.service.UserService
```

Limpar toda a seleção:

```bash
mutation-ai select --remove .
```

---

## 3) `status`

Mostra o estado atual da seleção persistida.

```bash
mutation-ai status
mutation-ai status .
mutation-ai status /caminho/projeto-alvo
```

Saída:
- caminho do projeto
- total de classes selecionadas
- lista de classes selecionadas (verde via ANSI quando suportado)
- caminho do arquivo de seleção

Se não existir seleção, mostra mensagem amigável para executar `select`.

---

## 4) `create test` e alias `c t`

Lê a seleção atual, analisa a classe alvo, gera um teste candidato por classe selecionada e prepara o fluxo de validação real via Maven.

```bash
mutation-ai create test
mutation-ai c t
mutation-ai create test .
mutation-ai create test /caminho/projeto-alvo
```

Saída do comando:
- caminho do projeto
- total de classes selecionadas
- total de prompts gerados
- total de testes candidatos gerados
- total de execuções aprovadas e com falha no Maven
- caminho do lote gerado
- resultado por classe (APROVADO ou FALHOU, com erros e caminho do teste falho se houver)

Exemplo de saída:

```text
Projeto: /caminho/projeto
Classes selecionadas: 3
Prompts gerados: 3
Testes candidatos gerados: 3
Execuções aprovadas no Maven: 2
Execuções com falha no Maven: 1
Lote salvo em: /caminho/projeto/.mutation-ai/prompts/create-test-20260415-235655
Resultados por classe:
 - com.exemplo.service.UserService: APROVADO
 - com.exemplo.service.AuthService: APROVADO
 - com.exemplo.service.BillingService: FALHOU
   erro: [ERROR] cannot find symbol
   teste falho salvo em: /caminho/projeto/.mutation-ai/failed/BillingServiceTest.java
```

### O que cada prompt e execução devem considerar

Cada classe selecionada deve gerar um fluxo próprio com:
- fully qualified name da classe alvo
- caminho relativo do arquivo fonte
- dependências colaboradoras identificadas, priorizando construtor
- código fonte completo da classe alvo com limpeza de ruídos simples
- análise estrutural da classe para reduzir alucinação
- instruções estritas para geração de um único arquivo `*Test.java`
- espaço para refinamento orientado por erro real de compilação e execução

### Regras do prompt gerado

O template foi preparado para automação com IA local e pede explicitamente:
- retorno com apenas código Java
- sem markdown
- sem blocos como ` ```java ` ou ` ``` `
- sem explicações fora do código
- sem comentários explicando o código gerado
- uso de JUnit 5 e Mockito
- uso de `@Mock`, `@InjectMocks` e preferencialmente `@ExtendWith(MockitoExtension.class)` quando fizer sentido
- classe de teste no formato `NomeDaClasseTest`
- package compatível com a classe alvo
- foco em comportamento observável
- cobertura de caminho feliz, falhas relevantes, bordas, `null`, `Optional.empty()` e exceções quando aplicável
- nomes de testes descritivos e legíveis

### Validação real e aprovação

A direção arquitetural do projeto é:
- gerar um teste candidato
- salvar temporariamente o teste no projeto alvo
- executar `./mvnw -Dtest=<NomeDoTeste> test`
- capturar feedback real do Maven/JUnit
- refinar o teste quando houver falha
- considerar aprovado apenas o teste que passar no projeto real
- medir cobertura e mutation score após a aprovação funcional

### Organização dos arquivos gerados

Cada execução cria uma pasta própria para evitar mistura entre lotes diferentes.

Exemplo:

```text
.mutation-ai/prompts/
  create-test-20260415-235655/
    UserService.md
    AuthService.md
    BillingService.md
```

Se não existir seleção, o comando orienta executar `mutation-ai select .` antes.

---

## Persistência

Arquivos salvos no projeto alvo:

```text
<projectRoot>/.mutation-ai/selection.json
<projectRoot>/.mutation-ai/prompts/create-test-<timestamp>/<Classe>.md
```

Se a pasta/arquivo não existir, é criado automaticamente.

Exemplo simplificado:

```json
{
  "projectRoot": "/caminho/projeto",
  "selectedAt": "2026-03-26T00:00:00Z",
  "totalSelected": 2,
  "classes": [
    {
      "className": "UserService",
      "fullyQualifiedName": "com.exemplo.UserService",
      "relativePath": "com/exemplo/UserService.java"
    }
  ]
}
```

---

## Regras do scan

A lógica de descoberta permanece:

- busca em `src/main/java`
- inclui `.java`
- ignora:
  - `*Test.java`
  - `package-info.java`
  - `module-info.java`

---

## Estrutura arquitetural (resumo)

```text
adapters/in/cli          -> comandos scan/select/status/create test
application/usecase      -> casos de uso
application/port/in      -> contratos de entrada
application/port/out     -> contratos de saída
adapters/out/filesystem  -> scan e persistência/leituras locais
domain/model             -> modelos de domínio
```

---

## Observações

- Modos CLI (`scan`, `select`, `s`, `status`, `create`, `c`) rodam sem web e encerram ao final.
- Prefira sempre `./mvnw` para build/execução local.
