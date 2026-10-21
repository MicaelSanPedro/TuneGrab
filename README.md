<div align="center">

<img src="assets/icon.png" width="160" alt="Logo do TuneGrab: ícone dourado com nota musical branca"/>

# TuneGrab

**Baixe músicas e vídeos do YouTube no Android. O download roda inteiro no
próprio aparelho, sem servidor e sem anúncios.**

[![Build](https://github.com/micsxnn/TuneGrab/actions/workflows/build.yml/badge.svg)](https://github.com/micsxnn/TuneGrab/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/micsxnn/TuneGrab)](https://github.com/micsxnn/TuneGrab/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/micsxnn/TuneGrab/total)](https://github.com/micsxnn/TuneGrab/releases)
[![License](https://img.shields.io/github/license/micsxnn/TuneGrab)](LICENSE)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)

[**Baixar a última versão (APK)**](https://github.com/micsxnn/TuneGrab/releases/latest) ·
[**Instalação**](#instalação) ·
[**Como funciona**](#como-funciona) ·
[**Roadmap**](#roadmap)

</div>

---

## O que é o TuneGrab

TuneGrab é um app Android que baixa áudio (MP3, M4A, OPUS) e vídeo (MP4/MKV)
do YouTube e salva em `Downloads/TuneGrab`. O APK carrega um motor yt-dlp
completo (Python, yt-dlp e ffmpeg embutidos), então o download funciona de
fábrica e acontece inteiro no seu celular. Quando o YouTube muda alguma coisa,
o motor se atualiza sozinho na próxima abertura. Você não precisa de root, de
servidor ou de qualquer ferramenta instalada além do app.

Dá para ouvir sem sair do TuneGrab: o player tem espectro de áudio real, letra
sincronizada e fila da Biblioteca. As músicas preferidas ficam nos favoritos,
e o resto se organiza em pastas.

## Funcionalidades

### Downloads

- **Cole o link e toque em "Baixar"**: funciona de primeira, sem configurar nada
- **Downloads em segundo plano**: serviço em primeiro plano com notificação de
  progresso por fases (baixando, processando, salvando). Você pode fechar o app
  e deixar baixando; download interrompido retoma de onde parou
- **Playlist inteira**: link com `list=` no modo Playlist. Você escolhe formato
  e qualidade uma vez, e os vídeos entram na fila um a um (até 100 por vez)
- **4 formatos, qualidades configuradas separadamente** (em Config.):
  - **MP3 320/256/192/128 kbps**, convertido no próprio celular com o ffmpeg
    embutido, com tags ID3 e nome da música
  - **M4A**: o áudio original do YouTube, sem reconversão (melhor fidelidade)
  - **OPUS**: o áudio original em WebM, máxima qualidade por bit
  - **Vídeo (MP4/MKV)** até **4K (2160p)**, com **todas as qualidades
    liberadas**. Acima de 720p o YouTube separa vídeo e áudio, então o app
    baixa os dois e junta com o ffmpeg embutido **sem recodificar**. Até 1080p
    o arquivo sai em **MP4/H.264**, que abre em qualquer lugar; **1440p e 4K
    saem em MKV**, o contêiner nativo do YouTube nessas resoluções. A
    notificação final confirma a resolução do arquivo salvo
- **Escolha onde salvar**: qualquer pasta do aparelho em Config. → Pasta de
  download (padrão: `Downloads/TuneGrab`, com fallback automático)
- **Motor yt-dlp embutido e auto-atualizável** (canal estável): quando o
  YouTube mudar algo, o TuneGrab se conserta na próxima abertura, sem
  precisar de versão nova
- **Fallback duplo em silêncio**: se o yt-dlp falhar, um segundo motor
  (NewPipeExtractor + PoToken/BotGuard) assume e tenta de novo automaticamente
- **Compartilhe direto do YouTube** ("Compartilhar → TuneGrab") e o download
  começa na hora
- Suporte a links `youtube.com`, `m.youtube.com`, `music.youtube.com`,
  `youtu.be`, Shorts e playlists

### Player

- **Player embutido** com barra de progresso e **espectro de áudio real**
  (barras que acompanham o som, sem permissão extra). A thumbnail entra
  **inteira** num retângulo 16:9 de largura cheia, sem barra sobrando, e o
  vídeo roda em tela cheia **sem deformar**
- **Letra sincronizada**: o botão "Letra" busca a letra em tempo real na
  [LRCLIB](https://lrclib.net), fonte aberta. A linha acende no tempo do som e
  a tela rola sozinha, estilo Spotify/YouTube Music. Nos testes com músicas
  reais, a cobertura passou de 89%
- **Música em segundo plano**: o som continua com o app fechado ou a tela
  apagada, com **notificação de mídia** (play, pause, fechar). O vídeo cabe em
  uma **janelinha picture-in-picture**, igual YouTube Premium
- **Fila anterior/próxima** da Biblioteca direto no player, com **reprodução
  automática** por música (você liga e desliga nas Configurações)
- **Favoritos**: coração disponível em cada música, no canto do player e no
  card da Biblioteca. Os salvos aparecem numa aba própria na barra de
  navegação. Toque no card para **ouvir de novo**: favorito do YouTube refaz o
  fluxo de sempre; favorito de arquivo toca **na hora, direto do aparelho**

### Biblioteca e navegação

**6 abas na barra inferior**, com troca instantânea (a aba ativa cresce sobre
as vizinhas):

| Aba | O que faz |
|---|---|
| **Início** | Cole o link e baixe, ou toque no botão de play para ouvir sem baixar |
| **YouTube** | Navegador do YouTube dentro do app, com botão de download flutuante |
| **Downloads** | Central em tempo real (fase, porcentagem e **velocidade**), com **pausar, continuar e cancelar** no meio do download, na tela e na notificação. Filtro Tudo / Músicas / Vídeos |
| **Favoritos** | As músicas que você guardou com o coração (player ou Biblioteca) |
| **Biblioteca** | Músicas e vídeos com **pastas de verdade** (criar, renomear, mover estilo gerenciador de arquivos), **busca**, seleção múltipla para apagar e **menu de três pontinhos** em cada música (Favoritar, Compartilhar, Mover pra pasta, Apagar) |
| **Config.** | Formatos, qualidades, pasta de download, visualizador e mais |

A Biblioteca **sobrevive a atualizações e até à reinstalação do app**: as
pastas TuneGrab são reencontradas pelo MediaStore.

### Controle e segurança

- **Trava de acesso por convite**: o app abre atrás de senha. Cada senha nasce
  de um convite e **fica presa ao aparelho** que ativou; passar a senha adiante
  não dá acesso em outro celular
- **Atualização automática**: o card de versão nova aparece no Início, com o
  changelog em texto leigo; baixar e instalar acontecem **dentro do app**, com
  barra de progresso e confirmação final do sistema. Se a consulta à API do
  GitHub falhar (rede instável ou limite de requisições), o app tenta um
  **segundo canal** (o feed de releases) antes de desistir

## Instalação

1. Abra a página de [**Releases**](https://github.com/micsxnn/TuneGrab/releases)
2. Baixe o `TuneGrab-x.y.z.apk` da release mais recente (estável)
3. Abra o APK e permita a instalação de fontes desconhecidas, se o Android pedir
4. Cole um link e baixe a primeira música

> **Por que o APK tem cerca de 100 MB?** Ele carrega um motor completo dentro
> de si: interpretador **Python**, o **yt-dlp** e o **ffmpeg** empacotados para
> rodar no seu aparelho. É o preço de vir funcionando: nada de servidor, nada
> de dependência externa, nada de quebrar quando o YouTube muda. Na primeira
> abertura o app prepara o motor em segundo plano; depois disso os downloads
> saem na hora.

## Como funciona

O app é independente: não existe servidor de download no meio do caminho.
Tudo acontece no dispositivo, em silêncio:

```
YouTube → TuneGrab (no seu celular) → Downloads/TuneGrab
           │
           ├─ PLANO A · yt-dlp embutido (Python + yt-dlp + ffmpeg no APK)
           │     resolve extração E download; escolhe o melhor stream,
           │     baixa com retries e se auto-atualiza contra o YouTube
           │
           ├─ PLANO B · NewPipeExtractor + PoToken/BotGuard (fallback
           │     automático, só entra se o Plano A falhar): WebView roda o
           │     desafio anti-bot e minta PoTokens; cada URL é testado com
           │     um probe HTTP antes de baixar; retomada com Range/.part
           │
           ├─ RESGATE · se o anti-bot travar a extração, o yt-dlp lê os
           │     metadados (título, canal, qualidades) e o seletor abre
           │     com a mesma cara de sempre; o download sai pelo Plano A
           │
           ├─ LETRAS · LRCLIB: busca em escada (exato → artista+faixa →
           │     solto → só faixa) escolhe o melhor candidato por duração
           │     e sincronia; cache local
           │
           └─ ffmpeg embutido: converte MP3/OPUS e junta vídeo+áudio do MP4
```

### Plano A: yt-dlp embutido (motor principal)

O yt-dlp é o downloader de YouTube mais maduro que existe, e mora dentro do
APK. Ele faz o trabalho pesado: resolve os streams, baixa com até 5 tentativas,
converte com o ffmpeg e entrega o arquivo pronto em `Downloads/TuneGrab`. O app
checa atualizações do motor **uma vez por versão do app** (canal estável): se o
YouTube mudar algo amanhã, o TuneGrab se conserta sozinho, sem você baixar
versão nova. Quando o anti-bot aperta, você pode **logar sua conta do YouTube**
(WebView no app ou import de `cookies.txt`); os cookies ficam guardados só no
aparelho.

### Plano B: NewPipeExtractor + PoToken (fallback)

Se o Plano A falhar (rede instável, por exemplo), o Plano B assume em silêncio.
O NewPipeExtractor (engine do NewPipe) resolve os streams com **PoTokens
gerados localmente** num WebView fora da tela. Cada URL é validada com um
*probe* HTTP (Range 0–1) antes de entrar na lista de opções, e o download usa
OkHttp com retomada de arquivo `.part`. O arquivo só chega na sua pasta se
passar na validação de tamanho; você não vai encontrar MP3 corrompido ou de
2 KB por causa de uma falha silenciosa.

A arquitetura tem custo zero de infraestrutura e protege sua privacidade:
nenhum servidor intermediário vê o que você baixa. E como são dois motores
independentes, a falha de um não derruba o download.

## Stack

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin |
| UI | Material 3 (Views + ViewBinding), paleta dourada própria |
| Motor principal | [yt-dlp](https://github.com/yt-dlp/yt-dlp) embutido via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android): Python + QuickJS no APK |
| Conversão/Merge | ffmpeg embutido (MP3, OPUS, MP4) |
| Fallback | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) com [fork compatível com Android antigo](https://github.com/micsxnn/NewPipeExtractor) + PoToken/BotGuard |
| Letras sincronizadas | [LRCLIB](https://lrclib.net) (API aberta, sem chave) |
| Player | MediaPlayer (local/muxed) + Media3/ExoPlayer (DASH HD) + PiP |
| Espectro de áudio | FFT próprio (2048 pontos, 28 bandas log) espiando o PCM no ExoPlayer |
| Conversão legada | LAME 3.100 (libmp3lame) via NDK + MediaCodec |
| Rede | OkHttp |
| Imagens | Coil |
| Mínimo | Android 7.0 (API 24) |
| Alvo | Android 14 (API 34) |

## Sobre o MP3 (transparência)

O YouTube não entrega MP3: a fonte só tem **M4A (AAC)** e **Opus**. Por isso o
TuneGrab **converte no seu aparelho**. O ffmpeg embutido baixa a melhor faixa
de áudio disponível e recodifica para o bitrate escolhido (320/256/192/128
kbps), com tags ID3. Um detalhe técnico honesto: a fonte do YouTube tem até
cerca de 160 kbps, então um MP3 320 preserva qualidade, mas não adiciona. É o
padrão que muita gente pede pela compatibilidade com players, pen drives e som
de carro. Para máxima fidelidade sem reconversão, escolha **M4A** ou **OPUS**.

## Compilar a partir do código-fonte

Requisitos: JDK 17, Android SDK 34 e NDK/CMake (o Gradle baixa o que faltar).

```bash
./gradlew assembleRelease   # APK assinado em app/build/outputs/apk/release/
./gradlew assembleDebug     # APK de debug
```

O GitHub Actions compila e publica automaticamente a cada tag `v*`.

> **Nota sobre a assinatura:** o keystore de release (`app/keystore/`) está
> commitado com senha pública de propósito, para garantir builds reprodutíveis
> que se atualizam entre versões. É uma escolha aceitável para um app gratuito
> e sem dados sensíveis; para uso comercial, mova o keystore para *GitHub
> Secrets*.

## Sobre o fork do NewPipeExtractor (motor de fallback)

O fallback do TuneGrab usa um fork próprio do NewPipeExtractor, mantido neste
perfil. A v0.26.5 oficial usa `URLDecoder.decode(String, Charset)`,
`URLEncoder.encode(String, Charset)` (Java 10) e `String.isBlank()` (Java 11),
APIs que só existem no **Android 13 (API 33) ou mais novo**. Em aparelhos mais
antigos isso lança `NoSuchMethodError`. O
[fork `micsxnn/NewPipeExtractor`](https://github.com/micsxnn/NewPipeExtractor)
corrige isso e adiciona proteções contra as mudanças recentes do YouTube:

1. **Compatibilidade com Android anterior ao 13**: overloads legacy de
   URLDecoder/URLEncoder e `isBlank` reimplementado.
2. **Clients extras de streams**: iOS e visionOS (android_vr) como rotas
   anônimas de streams diretos.
3. **Fallback de bot-check em cascata**: quando o YouTube responde *"Sign in
   to confirm you're not a bot"* ao client Android, o extractor tenta WEB com
   PoToken, depois iOS e visionOS.
4. **Suporte completo a `PoTokenProvider`**: inclui player requests dos
   clients WEB, iOS e Android com PoToken, inexistentes na v0.26.5 oficial.

Assim que o upstream corrigir essas questões, o fork pode ser substituído pela
versão oficial.

## Como o fallback lida com o anti-bot do YouTube (PoToken/BotGuard)

O YouTube exige *proof-of-origin tokens* (PoTokens) e responde *"Sign in to
confirm you're not a bot"* para clientes anônimos, inclusive apps. O Plano B
resolve isso **no próprio aparelho, sem servidor**:

1. Baixa a homepage do YouTube (OkHttp) e extrai o `ytcfg` e o desafio atual
   do BotGuard (`window.ytAtN`), que muda o tempo todo.
2. Baixa o *interpretador JavaScript* do desafio.
3. Roda a VM do BotGuard num **WebView fora da tela** (página local, sem
   acesso à rede) e tira um *snapshot*, exatamente como o site faz no
   navegador.
4. Troca o snapshot por um *integrity token* (válido por cerca de 12 horas) no
   endpoint `api/jnn/v1/GenerateIT`, o mesmo que o player do YouTube usa.
5. Gera PoTokens "mintados" por vídeo e os envia nos player requests e nos
   URLs de stream (parâmetro `pot`).

Com um PoToken válido, o YouTube trata a requisição como um navegador real e
devolve os streams. A sessão fica em cache por 12 horas e é recriada com
*cooldown*, respeitando o rate limit do YouTube; o app não martela a renovação
para não cair em 429. Sem WebView no aparelho, o fallback degrada para os
clients anônimos com iOS à frente.

O Plano A (yt-dlp embutido) resolve quase tudo sozinho; esse mecanismo é a
reserva para quando a coisa aperta.

## Roadmap

- [x] **Fase 1: app Android (APK)**
- [x] Conversão para MP3 (320/256/192/128 kbps)
- [x] MP4 com áudio e seletor de formato/qualidade
- [x] Configurações de qualidade por formato
- [x] Bypass do anti-bot (PoToken/BotGuard via WebView)
- [x] **Motor yt-dlp embutido, auto-atualizável, com fallback duplo** (v0.4.0)
- [x] **Navegação inferior, central de downloads, biblioteca de músicas e pasta de download escolhível** (v0.5.0)
- [x] **MP4 até 1080p de verdade (merge vídeo+áudio) e qualidades honestas** (v0.6.0)
- [x] **Player embutido (áudio/vídeo), compartilhar nos baixados e navegação instantânea em 1 activity** (v0.7.0)
- [x] **MP4 até 4K (2160p) com resolução verificada e confirmada no arquivo salvo** (v0.8.0)
- [x] **Música em segundo plano (notificação de mídia) e vídeo em tela cheia girando o app** (v0.9.0)
- [x] **4K/1440p em MKV e teto 4K** (v0.9.1)
- [x] **Pausar/continuar/cancelar download, velocidade na notificação e na Central, fila** (v0.10.0)
- [x] **Biblioteca reencontra músicas baixadas após atualizar ou reinstalar o app** (v0.10.0)
- [x] **Biblioteca separa Músicas e Vídeos (com contagem), padrões 1080p/320 kbps no seletor e tela cheia sem deformar o vídeo** (v0.10.1)
- [x] **Identidade visual: fonte Poppins e ícones Material Symbols Rounded** (v0.10.2)
- [x] **Motor tolerante ao bot-check: nova tentativa com processo novo, rotação de clients (visionOS/tv_embedded) e yt-dlp nightly** (v0.10.4–0.10.5)
- [x] **Update automático: card de versão nova, download do APK dentro do app com barra de progresso e instalação pelo sistema** (v0.10.7–0.11.0)
- [x] **Reproduzir sem baixar (play no Início), downloader de playlist com fila à prova de bloqueio e seletor Vídeo/Playlist** (v0.12.0–0.14.0)
- [x] **Splash de boas-vindas cursiva com assinatura em efeito LED e letreiro LED no Início** (v0.15.0–0.16.1)
- [x] **Play em 720p/1080p via ExoPlayer (DASH) com queda silenciosa pro muxed quando o YouTube bloqueia as faixas** (v0.17.0–0.17.1)
- [x] **Miniplayer e fim do áudio fantasma: som no cartão de mídia do sistema, fechar o app para a música, retomada exata ao voltar** (v0.18.0–0.18.1)
- [x] **Vídeo em janelinha PiP igual YouTube Premium, com play/pause dentro da janelinha** (v0.18.2–0.18.3)
- [x] **Modo de resgate anti-bot, orientação honesta (trocar rede) quando tudo bloqueia e login por cookies do YouTube (WebView ou cookies.txt)** (v0.18.4–0.18.6)
- [x] **Player minimalista com espectro real sem permissão (FFT próprio espiando o PCM no ExoPlayer), arte grande e waveform** (v0.18.8)
- [x] **Fila anterior/próxima da Biblioteca no player e capa embutida dos áudios** (v0.19.0)
- [x] **Aba do YouTube dentro do app (navegador com botão de download), fade entre abas e download direto pra Central** (v0.19.2)
- [x] **Barra que empurra: a aba clicada cresce (ícone e nome) deslizando os vizinhos, e auto-detecção música/playlist** (v0.19.3)
- [x] **Ícones flutuantes na barra e tutorial de primeira abertura** (v0.19.6)
- [x] **Barra de download full-width, login automático do YouTube e seleção múltipla para apagar na Biblioteca** (v0.19.7–0.19.8)
- [x] **Configurações em pastas (estilo YouTube) e aviso de update no Início** (v0.19.9)
- [x] **Reprodução automática por música (estilo YouTube) com regente nas Configurações** (v0.19.20–0.19.21)
- [x] **Trava de acesso por convite: senha de uso único derivada do nome e presa no aparelho que ativou** (v0.20.0–0.21.0)
- [x] **Pastas de verdade na Biblioteca: criar, navegar, mover estilo gerenciador de arquivos, renomear e apagar, além de busca** (v0.22.0–0.22.6)
- [x] **Letra sincronizada: linha acende no tempo do som e a tela anda sozinha (LRCLIB, busca em escada com 89% de cobertura real)** (v0.22.7–0.22.8)
- [x] **Paleta dourada: o app sai do violeta e veste ouro (contrastes AA conferidos dígito a dígito)** (v0.22.9)
- [x] **Favoritos: coração no player e na Biblioteca, aba própria na barra de navegação e favorito local tocando direto do aparelho** (v0.22.11–0.22.12)
- [x] **Biblioteca limpa: menu de três pontinhos (⋮) no lugar da fileira de botões e thumbnail inteira no player** (v0.22.13)
- [x] **Moldura perfeita: thumbnail em retângulo 16:9 de largura cheia (zero barra sobrando) e coração que segue a fila da Biblioteca** (v0.22.14)
- [x] **Update sempre atento: endpoint no endereço novo do projeto e canal reserva (feed de releases) quando a API do GitHub falha** (v0.22.15)
- [ ] Busca integrada (digitar o nome da música)
- [ ] **Fase 2: versão Windows (Tauri + yt-dlp)**

## Aviso legal

TuneGrab é uma ferramenta técnica neutra. **Baixar vídeos do YouTube viola os
Termos de Serviço da plataforma.** Use apenas para:

- conteúdo de sua própria autoria;
- material com licença Creative Commons ou domínio público;
- situações em que você tem autorização do detentor dos direitos.

Este projeto não se responsabiliza pelo uso indevido da ferramenta. Respeite
os direitos autorais e a legislação do seu país.

## Licença

[GPL-3.0](LICENSE), o mesmo modelo de licença do NewPipe e do
NewPipeExtractor. O TuneGrab embute ferramentas de terceiros, cada uma sob sua
licença: [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense/domínio
público), [ffmpeg](https://ffmpeg.org) (LGPL/GPL) e
[LAME 3.100](https://lame.sourceforge.io/) (LGPL, vendida em
`app/src/main/cpp/lame/` com direitos reservados aos autores do LAME).
