<div align="center">

<img src="assets/icon.png" width="128" alt="Ícone do TuneGrab"/>

# TuneGrab

**Baixe músicas e vídeos do YouTube no seu Android — direto no aparelho,
sem servidor, sem anúncios, sem complicação.**

![Build](https://github.com/MicaelSanPedro/TuneGrab/actions/workflows/build.yml/badge.svg)
![Release](https://img.shields.io/github/v/release/MicaelSanPedro/TuneGrab)
![License](https://img.shields.io/github/license/MicaelSanPedro/TuneGrab)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)

</div>

---

TuneGrab é um app Android que baixa **áudio (MP3, M4A, OPUS)** e **vídeo (MP4)**
do YouTube e salva tudo em **Downloads/TuneGrab**. A partir da **v0.4.0**, o app
vem com um **motor yt-dlp completo embutido no APK** (Python + yt-dlp + ffmpeg):
ele funciona de fábrica, roda tudo em silêncio no seu celular e se
**auto-atualiza sozinho** sempre que o YouTube mudar alguma coisa — sem root,
sem servidor, sem depender de nada instalado no aparelho.

## ✨ Funcionalidades

- **Cole o link e toque em "Baixar"** — funciona de primeira, sem configurar nada
- **Player embutido** — toque no ▶ na aba Biblioteca para ouvir (com barra de
  progresso e play/pause) ou assistir ao vídeo dentro do próprio app, em tela
  cheia **sem deformar** (proporção real preservada em qualquer tela)
- **Música em segundo plano** — a música continua tocando com o app fechado
  ou a tela apagada, com **notificação de mídia** (play/pause/fechar) e botão
  nas configurações de som do aparelho
- **Vídeo em tela cheia de verdade** — botão de tela cheia no player **gira o
  app** para paisagem (dos dois lados), esconde as barras do sistema e mantém
  a proporção do vídeo (nada de imagem esticada)
- **Downloads rodam em segundo plano** — serviço em primeiro plano com
  notificação de progresso: pode fechar o app e deixar baixando
- **Compartilhar em 1 toque** — botão de compartilhar na Central de Downloads
  (concluídos) e na aba Biblioteca
- **Menu de navegação inferior com 4 abas** (troca instantânea, sem recarregar):
  - **Início** — cole o link e baixe
  - **Downloads** — central em tempo real (fase, % e **velocidade**), com **pausar,
    continuar e cancelar** no meio do download — na notificação também. Músicas
    e vídeos separados por filtro (Tudo / Músicas / Vídeos)
  - **Biblioteca** — músicas (áudio) e VÍDEOS em abas separadas, com contagem,
    abrir / compartilhar / apagar. Sobrevive a atualizar e até a
    desinstalar/reinstalar: pastas TuneGrab são reencontradas pelo MediaStore,
    e o banner de permissão traz TODAS as músicas do aparelho (mesmo as de
    pasta personalizada)
  - **Config.** — qualidades e pasta de download
- **Escolha onde salvar** — qualquer pasta do aparelho em ⚙️ Configurações → Pasta
  de download (padrão: `Downloads/TuneGrab`, com fallback automático)
- **4 formatos, qualidades configuradas separadamente** (em ⚙️ Configurações):
  - **MP3 320/256/192/128 kbps** — convertido no próprio celular com o ffmpeg
    embutido, com tags ID3 e nome da música
  - **M4A** — o áudio original do YouTube, sem reconversão (melhor fidelidade)
  - **OPUS** — o áudio original em WebM, máxima qualidade por bit
  - **Vídeo (MP4/MKV)** — até **4K (2160p)**, com **todas as qualidades sempre
    liberadas**: acima de 720p o YouTube separa vídeo e áudio, então o app
    baixa os dois e junta com o ffmpeg embutido **sem re-codificar**. Pediu
    4K num vídeo de 1080p? Baixa em 1080p — o máximo que o vídeo tem — e a
    notificação final **confirma a resolução do arquivo salvo** (motor
    reserva: até 720p, com aviso). Até 1080p o arquivo sai em **MP4/H.264**
    (abre em qualquer lugar); **1440p/4K saem em MKV** — o contêiner nativo
    do YouTube nessas resoluções (VP9/AV1 dentro de MP4 o Android lê como
    arquivo corrompido)
- **Motor yt-dlp embutido e auto-atualizável** — o app atualiza o motor sozinho
  (canal estável, 1 checagem por versão), então quando o YouTube mudar algo o
  TuneGrab "se conserta" na próxima abertura, sem precisar de versão nova
- **Fallback duplo em silêncio**: se o yt-dlp falhar por qualquer motivo, um
  segundo motor (NewPipeExtractor + PoToken/BotGuard) assume e tenta de novo
  automaticamente — você só vê o arquivo (ou um erro honesto, com o motivo real)
- Compartilhe direto do YouTube (**"Compartilhar → TuneGrab"**) e o seletor abre
  na hora
- Download com **notificação de progresso por fases** e **retomada** de
  downloads interrompidos (continua de onde parou)
- O app **lembra a última escolha** de formato e qualidade
- Ícone de colar da área de transferência no campo de link
- Suporte a links `youtube.com`, `m.youtube.com`, `music.youtube.com`, `youtu.be`
  e Shorts

## 📥 Instalação

1. Vá até a página de [**Releases**](https://github.com/MicaelSanPedro/TuneGrab/releases)
2. Baixe o `TuneGrab-x.y.z.apk` da release **Latest** (estável)
3. Abra o APK (permita "instalar de fontes desconhecidas" se solicitado)
4. Pronto! Cole um link e baixe sua primeira música 🎵

> 📦 **Por que o APK tem ~100 MB?** Porque ele carrega um motor completo dentro
> de si: interpretador **Python**, o **yt-dlp** e o **ffmpeg** empacotados para
> rodar 100% no seu aparelho. É o preço de "vir funcionando": nada de servidor,
> nada de instalar dependências, nada quebrando quando o YouTube muda. Na
> primeira abertura o app prepara o motor em segundo plano; depois disso os
> downloads saem na hora.

## 🔧 Como funciona

O app é **100% independente** — não existe servidor de download. Tudo acontece
no próprio dispositivo, em silêncio:

```
YouTube ──▶ TuneGrab (no seu celular) ──▶ Downloads/TuneGrab
             │
             ├─ 🥇 PLANO A · yt-dlp embutido (Python + yt-dlp + ffmpeg no APK)
             │     resolve extração E download; escolhe o melhor stream,
             │     baixa com retries e se auto-atualiza contra o YouTube
             │
             ├─ 🥈 PLANO B · NewPipeExtractor + PoToken/BotGuard (fallback
             │     automático, só entra se o Plano A falhar): WebView roda o
             │     desafio anti-bot e minta PoTokens; cada URL é testado com
             │     um probe HTTP antes de baixar; retomada com Range/.part
             │
             └─ 💾 ffmpeg embutido: converte MP3/OPUS e junta vídeo+áudio do MP4
```

### 🥇 Plano A — yt-dlp embutido (motor principal)

O yt-dlp é o downloader de YouTube mais maduro que existe, e agora mora dentro
do APK. Ele faz todo o trabalho pesado: resolve os streams, baixa com até 5
tentativas, converte com o ffmpeg e entrega o arquivo pronto em
`Downloads/TuneGrab`. O app checa atualizações do motor **uma vez por versão do
app** (canal estável) — se o YouTube mudar algo amanhã, o TuneGrab se conserta
sozinho, sem você precisar baixar versão nova. A preparação do motor acontece
em segundo plano logo na abertura do app, para o primeiro download sair sem
espera.

### 🥈 Plano B — NewPipeExtractor + PoToken (fallback)

Se — e somente se — o Plano A falhar (ex.: rede instável), o plano B assume em
silêncio: o NewPipeExtractor (engine do NewPipe) resolve os streams com
**PoTokens gerados localmente** num WebView fora da tela, cada URL é validado
com um *probe* HTTP (Range 0–1) antes de entrar na lista de opções, e o
download usa OkHttp com retomada de arquivo `.part`. O arquivo só chega na sua
pasta se passar na validação de tamanho — nada de MP3 corrompido ou de 2 KB.

Vantagens dessa arquitetura: **custo zero** de infraestrutura, **privacidade**
(nenhum servidor intermediário vê o que você baixa) e **resiliência** (dois
motores independentes; se um falhar, o outro assume).

## 🏗️ Stack

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin |
| UI | Material 3 (Views + ViewBinding) |
| Motor principal | [yt-dlp](https://github.com/yt-dlp/yt-dlp) embutido via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) — Python + QuickJS no APK |
| Conversão/Merge | ffmpeg embutido (MP3, OPUS, MP4) |
| Fallback | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — [fork compatível com Android antigo](https://github.com/MicaelSanPedro/NewPipeExtractor) + PoToken/BotGuard |
| Conversão legada | LAME 3.100 (libmp3lame) via NDK + MediaCodec |
| Rede | OkHttp |
| Imagens | Coil |
| Mínimo | Android 7.0 (API 24) |
| Alvo | Android 14 (API 34) |

## 🎵 Sobre o MP3 (transparência)

O YouTube não serve MP3 — só **M4A (AAC)** e **Opus**. Por isso o TuneGrab
**converte no seu aparelho**: o ffmpeg embutido baixa a melhor faixa de áudio
disponível e recodifica para o bitrate escolhido (320/256/192/128 kbps), com
tags ID3 incluídas. Detalhe técnico honesto: a fonte do YouTube tem até
~160 kbps, então um MP3 320 preserva (mas não adiciona) qualidade — é o padrão
que muita gente pede para compatibilidade com players, pen drives e carros. Se
você quer máxima fidelidade sem reconversão, escolha **M4A** ou **OPUS**.

## 🚀 Buildar você mesmo

Requisitos: JDK 17, Android SDK 34 e NDK/CMake (o Gradle baixa o que faltar).

```bash
./gradlew assembleRelease   # APK assinado em app/build/outputs/apk/release/
./gradlew assembleDebug     # APK de debug
```

O GitHub Actions já compila e publica automaticamente a cada tag `v*`.

> ⚠️ **Nota sobre a assinatura:** o keystore de release (`app/keystore/`) está
> commitado com senha pública propositalmente, para garantir builds reprodutíveis
> que se atualizam entre versões. É uma escolha aceitável para um app gratuito e
> sem dados sensíveis — para uso comercial, mova o keystore para *GitHub Secrets*.

## 🧩 Sobre o fork do NewPipeExtractor (motor de fallback)

O fallback do TuneGrab usa um fork próprio do NewPipeExtractor, mantido neste
perfil. A v0.26.5 oficial usa `URLDecoder.decode(String, Charset)`,
`URLEncoder.encode(String, Charset)` (Java 10) e `String.isBlank()` (Java 11) —
APIs que só existem no **Android 13 (API 33)+**. Em aparelhos mais antigos isso
lança `NoSuchMethodError`. O
[fork `MicaelSanPedro/NewPipeExtractor`](https://github.com/MicaelSanPedro/NewPipeExtractor)
corrige isso e adiciona proteções contra as mudanças recentes do YouTube:

1. **Compatibilidade Android < 13** — overloads legacy de URLDecoder/URLEncoder
   e `isBlank` reimplementado.
2. **Clients extras de streams** — iOS e visionOS (android_vr) como rotas
   anônimas de streams diretos.
3. **Fallback de bot-check em cascata** — quando o YouTube responde *"Sign in
   to confirm you're not a bot"* ao client Android, o extractor tenta WEB com
   PoToken, depois iOS e visionOS.
4. **Suporte completo a `PoTokenProvider`** — inclui player requests dos
   clients WEB, iOS e Android com PoToken (inexistentes na v0.26.5 oficial).

Assim que o upstream corrigir/absorver essas questões, o fork pode ser
substituído pela versão oficial.

## 🔐 Como o fallback vence o anti-bot do YouTube (PoToken/BotGuard)

Desde 2025/2026 o YouTube exige *proof-of-origin tokens* (PoTokens) e responde
com *"Sign in to confirm you're not a bot"* para clientes anônimos — inclusive
apps. O plano B resolve isso **no próprio aparelho, sem servidor**:

1. Baixa a homepage do YouTube (OkHttp) e extrai o `ytcfg` + o desafio atual do
   BotGuard (`window.ytAtN`), que muda o tempo todo;
2. Baixa o *interpreter JavaScript* do desafio;
3. Roda a VM do BotGuard num **WebView fora da tela** (página local, sem
   acesso à rede) e tira um *snapshot* — exatamente como o site faz no
   navegador;
4. Troca o snapshot por um *integrity token* (válido ~12h) no endpoint
   `api/jnn/v1/GenerateIT` que o próprio player do YouTube usa;
5. Gera PoTokens "mintados" por vídeo e os envia nos player requests e nos
   URLs de stream (parâmetro `pot`).

Com um PoToken válido, o YouTube trata a requisição como um navegador real e
devolve os streams. A sessão fica em cache (12h) e é recriada com *cooldown*
respeitando o rate limit do YouTube — o app nunca "martela" a renovação, para
não cair em 429. Se o aparelho não tiver WebView, o fallback degrada para os
clients anônimos com iOS à frente.

> 💡 Na prática, esse mecanismo raramente é necessário: o plano A (yt-dlp
> embutido) já resolve quase tudo sozinho.

## 🗺️ Roadmap

- [x] **Fase 1** — App Android (APK)
- [x] Conversão para MP3 (320/256/192/128 kbps)
- [x] MP4 com áudio + seletor de formato/qualidade
- [x] Configurações de qualidade por formato
- [x] Bypass do anti-bot (PoToken/BotGuard via WebView)
- [x] **Motor yt-dlp embutido, auto-atualizável, com fallback duplo** (v0.4.0)
- [x] **Menu de navegação inferior + central de downloads + biblioteca de músicas + pasta de download escolhível** (v0.5.0)
- [x] **MP4 até 1080p de verdade (merge vídeo+áudio) e qualidades honestas** (v0.6.0)
- [x] **Player embutido (áudio/vídeo) + compartilhar nos baixados + navegação instantânea em 1 activity** (v0.7.0)
- [x] **MP4 até 4K (2160p) com resolução verificada e confirmada no arquivo salvo** (v0.8.0)
- [x] **Música em segundo plano (notificação de mídia) + vídeo em tela cheia girando o app** (v0.9.0)
- [x] **4K/1440p em MKV (arquivos que abrem de verdade) + teto 4K** (v0.9.1)
- [x] **Pausar/continuar/cancelar download + velocidade na notificação e na Central + fila** (v0.10.0)
- [x] **Biblioteca reencontra músicas baixadas após atualizar ou reinstalar o app** (v0.10.0)
- [x] **Biblioteca separa Músicas e Vídeos (com contagem) + padrões 1080p/320 kbps no seletor + tela cheia sem deformar o vídeo** (v0.10.1)
- [x] **Identidade visual: fonte Poppins em toda a interface + ícones Material Symbols Rounded (navegação com contorno/preenchido)** (v0.10.2)
- [x] **Motor de download tolerante ao bot-check do YouTube: re-tentativa automática com processo novo + mantém fallback PoToken** (v0.10.4)
- [ ] Busca integrada (digitar nome da música)
- [ ] **Fase 2** — Versão Windows (Tauri + yt-dlp)

## ⚖️ Aviso legal

TuneGrab é uma ferramenta técnica neutra. **Baixar vídeos do YouTube viola os
Termos de Serviço da plataforma.** Use apenas para:

- conteúdo de sua própria autoria;
- material com licença Creative Commons ou domínio público;
- situações em que você possui autorização do detentor dos direitos.

Este projeto não se responsabiliza pelo uso indevido da ferramenta. Respeite os
direitos autorais e a legislação do seu país.

## 📄 Licença

[GPL-3.0](LICENSE) — mesmo modelo de licença do NewPipe e do NewPipeExtractor.
O TuneGrab embute ferramentas de terceiros, cada uma sob sua licença:
[yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense/domínio público),
[ffmpeg](https://ffmpeg.org) (LGPL/GPL) e
[LAME 3.100](https://lame.sourceforge.io/) (LGPL, vendida em
`app/src/main/cpp/lame/` com direitos reservados aos autores do LAME).
