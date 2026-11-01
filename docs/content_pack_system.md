# Content Pack System — autoria, FLCP v1 e carregamento

Este documento descreve o comportamento implementado no repositório **FL-RPG**. Ele é a referência para criar as fontes TOML, entregá-las ao Compiler, gerar o pacote e configurar o servidor.

## 1. Fluxo e diretório de origem

O repositório privado **FL-RPG-Content** guarda as fontes editáveis. O repositório público **FL-RPG** recebe somente o arquivo binário compilado.

Estrutura esperada:

```text
development/
├── pack.toml
├── world.toml
└── exploration/
    └── areas/
        ├── spawn.toml
        └── outra-area.toml
```

O diretório passado ao Compiler deve ser **`development/`**, isto é, a pasta que contém diretamente `pack.toml`.

| Caminho | Função |
| --- | --- |
| `pack.toml` | Manifesto obrigatório: identidade, versões, seções, funcionalidades e extensões. |
| `world.toml` | Locais e conexões do mundo. |
| `exploration/areas/*.toml` | Um arquivo TOML por área de exploração. |

O Compiler procura os arquivos de área diretamente em `exploration/areas/`; não procura recursivamente em subpastas. Arquivos fora dos caminhos reconhecidos são ignorados. Para o pacote development recomendado neste projeto, declare `world` e `exploration` como `Mandatory`; com essa declaração, o Reader exige `world.toml` e pelo menos um arquivo de área. O mundo também precisa ter pelo menos um local.

## 2. Manifesto — `pack.toml`

Exemplo:

```toml
id = "development"
container_version = 1
schema_version = 1

[[sections]]
id = "world"
version = 1
requirement = "Mandatory"

[[sections]]
id = "exploration"
version = 1
requirement = "Mandatory"

[extensions]
author = "FL-RPG"
notes = "Development content pack"
```

| Campo | Tipo | Propósito |
| --- | --- | --- |
| `id` | string | Identificador do pacote. Não pode ficar vazio. |
| `container_version` | inteiro positivo | Versão lógica declarada pelo pacote. O servidor atual aceita `1`. |
| `schema_version` | inteiro positivo | Versão do schema de conteúdo. O servidor atual aceita `1`. |
| `sections` | lista de tabelas | Declara as seções fornecidas pelo pacote. |
| `features` | lista de tabelas, opcional | Declara metadados de funcionalidades e suas versões. |
| `extensions` | tabela de strings, opcional | Metadados adicionais no formato `chave = "valor"`. |

Cada entrada de `sections` e `features` possui:

- `id`: identificador não vazio.
- `version`: inteiro maior que zero.
- `requirement`: exatamente `"Mandatory"` ou `"Optional"`, respeitando maiúsculas e minúsculas.

**Seção** identifica um conjunto de dados do pacote, como `world` ou `exploration`. **Feature** declara uma funcionalidade que a aplicação pode ou não suportar. No servidor atual, somente as seções `world` e `exploration`, ambas na versão `1`, são suportadas. Nenhuma feature está registrada: uma feature `Mandatory` impede o carregamento; uma feature `Optional` desconhecida é aceita como metadado.

As extensões de autoria aceitam atualmente **somente valores string**. Não use arrays, números, booleanos, tabelas aninhadas ou objetos em `[extensions]`: o Compiler atual não os interpreta. Embora o modelo binário tenha uma representação genérica de valores, o parser TOML ainda não oferece esse formato genérico.

## 3. Mundo — `world.toml`

Cada local é declarado com `[[locations]]`. Cada conexão é declarada com `[[connections]]`.

```toml
[[locations]]
id = "spawn"
display_name = "Starting Clearing"

[[locations]]
id = "forest_path"
display_name = "Forest Path"

[[connections]]
id = "spawn_forest_path"
from = "spawn"
to = "forest_path"
distance = 10
bidirectional = true
```

### Locais

| Campo | Tipo | Propósito |
| --- | --- | --- |
| `id` | string | Identificador interno do local. Deve ser único no mundo. |
| `display_name` | string | Nome apresentado ao jogador. |

### Conexões

| Campo | Tipo | Propósito |
| --- | --- | --- |
| `id` | string | Identificador da conexão. Deve ser único. |
| `from` | string | ID do local de origem. |
| `to` | string | ID do local de destino. |
| `distance` | inteiro positivo | Distância usada pela movimentação do mundo. |
| `bidirectional` | boolean, opcional | Quando `true`, cria também a conexão inversa. O padrão é `false`. |

`from` e `to` precisam referenciar locais existentes e não podem ser iguais. Uma conexão bidirecional gera duas relações direcionadas: a original mantém o ID declarado e a inversa recebe o sufixo `_rev` (por exemplo, `spawn_forest_path_rev`). Esse ID gerado também precisa ser único. Não declare duas vezes a mesma direção.

A distância é um valor do sistema de mundo; não representa coordenadas ou distância euclidiana. A conversão para as unidades usadas pelo domínio é responsabilidade da integração com o FL-RPG.

## 4. Área de exploração — `exploration/areas/<arquivo>.toml`

Cada arquivo nesse diretório representa exatamente uma área. O nome do arquivo é livre; a identidade da área vem do campo `id`.

```toml
id = "spawn_area"
name = "Starting Clearing"
location_id = "spawn"
description = "A quiet clearing surrounded by trees."

initial_elements = ["tree", "stones"]

[[elements]]
id = "tree"
name = "Tree"
observation_description = "A tall tree stands near the clearing."
examination_description = "Thick roots emerge from the soil."

[[elements]]
id = "stones"
name = "Stones"
observation_description = "A cluster of moss-covered stones."
examination_description = "A narrow opening is visible between the stones."

[[elements]]
id = "exposed_roots"
name = "Exposed Roots"
observation_description = "Several roots cross the ground."
examination_description = "The roots conceal a small opening beneath them."

[[elements]]
id = "hidden_opening"
name = "Hidden Opening"
observation_description = "A dark gap can be seen between the stones."
examination_description = "The gap leads into a narrow space below the surface."

[[discoveries]]
from = "tree"
to = "exposed_roots"

[[discoveries]]
from = "stones"
to = "hidden_opening"
```

| Campo | Tipo | Propósito |
| --- | --- | --- |
| `id` | string | Identificador único da área. |
| `name` | string | Nome da área. |
| `location_id` | string | ID do local do mundo ao qual a área pertence. |
| `description` | string | Descrição da área. |
| `initial_elements` | lista de strings | IDs dos elementos disponíveis desde o início. |
| `elements` | lista de tabelas | Elementos que podem ser observados e examinados. |
| `discoveries` | lista de tabelas | Relações direcionadas que liberam novas descobertas. |

Cada entrada de `elements` exige:

- `id`: identificador do elemento.
- `name`: nome apresentado.
- `observation_description`: texto apresentado ao observar.
- `examination_description`: texto apresentado ao examinar.

Cada entrada de `discoveries` exige `from` e `to`, ambos IDs de elementos da **mesma área**. A relação é direcionada: `from = "tree"` e `to = "exposed_roots"` significa que a descoberta de `tree` libera `exposed_roots`; não cria a relação inversa.

### Regras obrigatórias das áreas

- `location_id` deve existir em `world.toml`.
- Cada área deve apontar para um local diferente.
- IDs de áreas devem ser únicos em todo o pacote.
- IDs de elementos devem ser únicos em todo o pacote, não apenas dentro do arquivo.
- Todo ID em `initial_elements` deve existir em `elements`.
- Uma área que tenha elementos precisa declarar pelo menos um elemento inicial.
- Uma descoberta não pode apontar para si mesma, usar IDs inexistentes ou repetir a mesma relação direcionada.
- **Todo elemento precisa ser alcançável a partir de pelo menos um elemento inicial**, seguindo as relações de descoberta. Essa última regra é validada pelo domínio ao construir a área, durante a criação do `Nucleus`.

Os textos devem ser strings TOML não vazias. Aspas internas devem ser escapadas (`\"`) e quebras de linha podem ser representadas por `\n`.

## 5. Validação e compatibilidade

O parser rejeita campos desconhecidos em `pack.toml`, `world.toml` e nos arquivos de área. Isso evita que erros de escrita sejam aceitos silenciosamente. Valores TOML nulos também não são aceitos.

As responsabilidades são separadas:

| Componente | O que valida |
| --- | --- |
| Compiler | Sintaxe TOML, tipos, campos, IDs duplicados, valores inválidos e referências entre os arquivos de origem. |
| Reader | Cabeçalho, criptografia, CBOR, versões, seções, funcionalidades e estrutura do pacote recuperado. |
| ContentNucleusFactory / domínio | Conversão para os modelos de gameplay e invariantes próprias do domínio, incluindo alcançabilidade dos elementos. |

O Reader aplica estas regras:

- Seção ou feature desconhecida/incompatível marcada como `Mandatory`: rejeita o pacote.
- Seção ou feature desconhecida/incompatível marcada como `Optional`: permite o carregamento.
- Versões de contêiner e schema não suportadas: rejeita o pacote.
- Dados obrigatórios ausentes, referências inválidas ou estrutura inconsistente: rejeita o pacote.
- Não há carregamento parcial.

Para a configuração atual do servidor, use `container_version = 1`, `schema_version = 1`, e declare as seções `world` e `exploration` como `Mandatory`, ambas na versão `1`. O servidor não registra features.

## 6. Contêiner binário FLCP v1

O Compiler transforma o modelo em CBOR, comprime com Zstandard e criptografa com AES-256-GCM:

`TOML → modelos intermediários → CBOR → Zstandard → AES-256-GCM → arquivo .flcp`

O Reader executa o caminho inverso. O CBOR é mapeado por serializers explícitos no `ContentPackCodec`; os modelos não dependem de anotações de serialização.

### Cabeçalho

Todos os inteiros usam **big-endian**. Os offsets abaixo são relativos ao início do arquivo; o campo de ID da chave tem tamanho variável.

| Ordem / offset | Tamanho | Campo | Valor no v1 |
| --- | ---: | --- | --- |
| 1 / 0 | 4 bytes | Magic | ASCII `FLCP`. |
| 2 / 4 | 2 bytes | Container version | Inteiro de 16 bits; `1`. |
| 3 / 6 | 1 byte | Serialization ID | `1` = CBOR. |
| 4 / 7 | 1 byte | Compression ID | `1` = Zstandard. |
| 5 / 8 | 1 byte | Encryption ID | `1` = AES-256-GCM. |
| 6 / 9 | 2 bytes | Key ID length | Inteiro de 16 bits; quantidade de bytes UTF-8. |
| 7 / 11 | 1–255 bytes | Key ID | Identificador não secreto da chave. |
| 8 | 12 bytes | Nonce | 96 bits aleatórios. |
| 9 | 8 bytes | Original CBOR size | Tamanho original do CBOR em bytes. |
| 10 | 8 bytes | Payload size | Tamanho do texto cifrado **incluindo** a tag GCM. |
| 11 | Variável | Payload | Texto cifrado seguido pela tag de autenticação de 16 bytes. |

O tamanho do cabeçalho é `39 + tamanho do Key ID`. O payload é o resultado de criptografar os bytes comprimidos; a tag GCM de 128 bits vem anexada ao resultado da criptografia.

### Autenticação e limites

- O AAD é composto por **todos os bytes do cabeçalho**, do primeiro byte de `FLCP` até o último byte de `Payload size`. O payload não faz parte do AAD.
- Qualquer alteração no cabeçalho autenticado ou no payload invalida a autenticação AES-GCM.
- O nonce é gerado com `SecureRandom` para cada codificação. Não reutilize um nonce com a mesma chave.
- O tamanho do Key ID deve ser de 1 a 255 bytes UTF-8.
- O arquivo FLCP não pode exceder 64 MiB.
- O CBOR descomprimido não pode exceder 32 MiB na leitura.
- O tamanho real do arquivo deve corresponder ao tamanho do cabeçalho, somado ao payload declarado. O tamanho descomprimido deve corresponder exatamente a `Original CBOR size`.
- O algoritmo de criptografia é obrigatório. Não existe fallback para conteúdo em texto puro.

O campo `container_version` de `pack.toml` é metadado do modelo serializado e também é validado pelo Reader. A versão binária do cabeçalho identifica o formato externo do contêiner. Ambos são `1` na implementação atual.

## 7. Chaves e variáveis de ambiente

A chave nunca deve ser armazenada nos repositórios, nos arquivos TOML, em argumentos de linha de comando ou em logs. Gere 32 bytes aleatórios com um gerador criptograficamente seguro, codifique-os em Base64 e guarde o valor em um gerenciador de segredos. O valor Base64 deve ficar em uma única linha, sem espaços ou quebras de linha. **Base64 é apenas a representação da chave, não uma forma de criptografia.**

| Variável | Usada por | Propósito e validação |
| --- | --- | --- |
| `FL_RPG_CONTENT_KEY_ID` | Compiler e servidor | Identificador não secreto da chave. Deve ser não vazio e corresponder exatamente ao ID gravado no cabeçalho. O contêiner aceita de 1 a 255 bytes UTF-8. |
| `FL_RPG_CONTENT_KEY` | Compiler e servidor | Chave AES-256 em Base64. Após decodificar, deve conter exatamente 32 bytes. |
| `FL_RPG_CONTENT_PACK_PATH` | Servidor | Caminho do arquivo `.flcp` que será carregado. Caminhos relativos são resolvidos a partir do diretório de trabalho do processo. |

Use os mesmos valores de `FL_RPG_CONTENT_KEY_ID` e `FL_RPG_CONTENT_KEY` ao compilar e ao iniciar o servidor. O ID diferencia maiúsculas de minúsculas; não use espaços no início ou no fim. Se perder a chave ou usar outra, os pacotes já criptografados não poderão ser lidos.

Exemplo no PowerShell:

```powershell
$env:FL_RPG_CONTENT_KEY_ID = "development-key-1"
$env:FL_RPG_CONTENT_KEY = "<BASE64_DA_CHAVE_DE_32_BYTES>"
```

Exemplo no Bash:

```bash
export FL_RPG_CONTENT_KEY_ID="development-key-1"
export FL_RPG_CONTENT_KEY="<BASE64_DA_CHAVE_DE_32_BYTES>"
```

Para gerar uma nova chave com OpenSSL, execute `openssl rand -base64 32` uma única vez e armazene o resultado com segurança. Não gere uma chave nova a cada compilação ou inicialização: isso impediria a leitura de pacotes produzidos com a chave anterior.

Ao iniciar o servidor, configure também o caminho do pacote. PowerShell:

```powershell
$env:FL_RPG_CONTENT_PACK_PATH = "C:\caminho\para\FL-RPG\content\packs\development.flcp"
```

Bash:

```bash
export FL_RPG_CONTENT_PACK_PATH="/caminho/para/FL-RPG/content/packs/development.flcp"
```

As variáveis precisam estar disponíveis no ambiente do processo que executa o Gradle ou o servidor. Configurá-las em um terminal não as configura automaticamente em uma IDE já aberta.

## 8. Compilar as fontes TOML

A tarefa Gradle disponível é `compileDevelopmentContentPack`, no módulo `content-compiler`.

| Propriedade Gradle | Obrigatória? | Propósito |
| --- | --- | --- |
| `-PcontentSourceDir` | Sim | Caminho do diretório privado que contém `pack.toml`. |
| `-PcontentPackOutput` | Não | Caminho do arquivo `.flcp` de saída. |

Com as variáveis criptográficas já definidas no mesmo terminal, execute na raiz do repositório **FL-RPG**:

```bash
./gradlew :content-compiler:compileDevelopmentContentPack -PcontentSourceDir="/caminho/para/FL-RPG-Content/development"
```

No Windows:

```powershell
.\gradlew.bat :content-compiler:compileDevelopmentContentPack -PcontentSourceDir="C:\caminho\para\FL-RPG-Content\development"
```

Por padrão, o arquivo será criado em:

`FL-RPG/content/packs/development.flcp`

Para escolher outro destino, acrescente `-PcontentPackOutput`:

```bash
./gradlew :content-compiler:compileDevelopmentContentPack -PcontentSourceDir="/caminho/para/FL-RPG-Content/development" -PcontentPackOutput="/caminho/para/FL-RPG/content/packs/development.flcp"
```

Use um caminho absoluto para evitar ambiguidades sobre o diretório de trabalho. A tarefa encaminha as duas variáveis criptográficas ao processo do Compiler pelo ambiente; a chave não é passada como argumento.

O diretório de origem deve conter `pack.toml`. Não passe o caminho de um arquivo TOML individual nem a raiz do repositório privado quando o pacote estiver dentro de uma subpasta.

## 9. Distribuir e carregar o pacote

1. Edite e versione os TOML somente no repositório privado `FL-RPG-Content`.
2. Compile o pacote a partir da pasta que contém `pack.toml`.
3. Gere o artefato em `FL-RPG/content/packs/development.flcp`.
4. Versione no repositório público somente o `.flcp`; nunca copie as fontes TOML ou a chave para ele.
5. Configure `FL_RPG_CONTENT_PACK_PATH`, `FL_RPG_CONTENT_KEY_ID` e `FL_RPG_CONTENT_KEY` no ambiente do servidor.
6. Inicie o servidor nesse ambiente.

O `.gitignore` atual contém a regra `*.flcp`. Portanto, para adicionar intencionalmente o pacote criptografado ao Git, use:

```bash
git add -f content/packs/development.flcp
```

O servidor lê o pacote, valida o contêiner, autentica e descriptografa o payload, descomprime o CBOR, valida compatibilidade e estrutura e, por fim, usa `ContentNucleusFactory` para construir o `Nucleus`. Os IDs do `Nucleus` e do `RealityNucleus` não são definidos nos TOML: a Factory atual usa `main_nucleus` e `main_reality`. Se o pacote, a chave ou a configuração forem inválidos, o carregamento padrão falha e a inicialização não prossegue.

Quando `Application.module()` recebe um `actionProcessor` externo, esse processador é utilizado diretamente e o carregamento padrão do pacote não é executado.

## 10. Limites da implementação atual

- O Compiler lê somente `pack.toml`, `world.toml` e arquivos `*.toml` diretamente em `exploration/areas/`.
- O formato de autoria atual não oferece conteúdo de features nem extensões TOML com tipos arbitrários; `features` e `extensions` são apenas os metadados descritos neste documento.
- O servidor suporta contêiner v1, schema v1 e as seções `world` e `exploration` v1. Não registra features.
- Não há hot reload, seleção dinâmica de vários pacotes, editor de conteúdo ou gerenciamento de chaves.
- A criptografia protege o pacote armazenado e distribuído. Ela não oculta do jogador os dados que o próprio jogo apresentar durante a execução.
