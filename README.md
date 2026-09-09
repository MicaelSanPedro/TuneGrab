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
- [x] **Anti-bot-check de verdade: yt-dlp atualiza pelo canal NIGHTLY (correções diárias) + rotação de client android_vr nas re-tentativas + --sleep-requests + botão de copiar detalhes do erro na Central** (v0.10.5)
- [x] **Barra da Central vira VERDE ao processar/salvar + labels de fase em maiúsculas (Baixando/Processando arquivo/Salvando arquivo)** (v0.10.6)
- [x] **Fase 1 do update automático: card "nova versão disponível" na Central com changelog + link da release (consulta o GitHub, throttle 6h, falha silenciosa)** (v0.10.7)
- [x] **Biblioteca enxerga a pasta do PRÓPRIO app mesmo quando o MediaStore falha (listagem direta Download/Music/TuneGrab) + verificação dupla ao apagar arquivo** (v0.10.8)
- [x] **Card de atualização consulta a cada abertura do app (a janela de 6h escondia releases novas; agora: gap mínimo 10 min + 1 consulta/hora por processo)** (v0.10.9)
- [x] **Fase 2 do update automático: baixar o APK dentro do app com barra de progresso + "Instalar agora" via instalador do sistema + confirmação antes de gastar rede móvel** (v0.11.0)
- [x] **Reproduzir sem baixar: botão ▶ ao lado do Baixar toca o vídeo do link direto no player + estado vazio da Central de downloads com cara de app** (v0.12.0)
- [x] **Downloader de PLAYLIST (formato/qualidade 1×, vídeos entram na fila um a um) + Biblioteca em seções com destaque pro que foi baixado pelo TuneGrab + estado vazio da Central de verdade (o peso do layout engolia a mensagem)** (v0.13.0)
- [x] **Seletor Vídeo/Playlist no topo do Início (modo Playlist aceita watch?v&list e puxa a lista inteira) + fila da playlist à prova de bloqueio: item que falha na extração entra via yt-dlp direto (plano A) em vez de somar falha** (v0.14.0)
- [x] **Loader de abertura: "Bem-vindo" em cursiva (Great Vibes) + assinatura "feito por micaelsan" discreta com o nome brilhando em efeito de led passando** (v0.15.0)
- [x] **Ajuste da splash: "bem-vindo" em minúsculas (o B maiúsculo da cursiva tem um swash que parecia um "o" solto na tela) + espaço pro rabinho do "o" final não cortar** (v0.15.1)
- [x] **"bem-vindo" na ASTON SCRIPT (fonte do autor, em TTF) com autoSize (nunca mais corta "bem vin…" em nenhuma tela/escala de fonte) + nome do app no Início virou LETREIRO DE LED ROLANTE (texto desliza e acende na faixa de luz)** (v0.16.0)
- [x] **FIM do pisca-pisca: o "TuneGrab" do Início voltou a ser igual à assinatura do splash (texto PARADO, a luz varre — o letreiro rolante movia o texto por uma faixa fixa e apagava fora dela) + "bem-vindo" com altura FIXA (o autoSize com wrap_content deixava a view mais baixa que a linha da fonte e cortava o texto ao meio)** (v0.16.1)
- [x] **Botão de play em 1080p (era 360p!) — o play do Início tocava a faixa muxada (vídeo+áudio juntos) que o YouTube só entrega até 360p; agora o ExoPlayer/Media3 junta a faixa DASH de vídeo (H.264/avc1 até 1080p, VP9 de plano B) com o áudio de maior bitrate, em streaming, sem baixar nada — e se o YouTube bloquear as faixas DASH, cai pro muxed de sempre** (v0.17.0)
- [x] **Play em 720p + FIM do erro "confere a internet" — o teto de qualidade voltou pra 720p (a 1080p o YouTube anda bloqueando as faixas DASH sem aviso: o teste de URL passa, mas o request completo leva 403) e, quando isso acontece, o player CAI SOZINHO pro muxed de sempre em vez de morrer no erro — a mensagem enganosa virou "tenta de novo em instantes" e só aparece se TUDO falhar** (v0.17.1)
- [x] **MINIPLAYER: saiu do app com vídeo ou música tocando? O som continua no cartão de mídia do sistema (lá embaixo no shade e na tela de bloqueio) — vídeo remoto DASH, vídeo muxado e vídeo baixado entregam o áudio pro serviço de reprodução NA POSIÇÃO exata (handoff) e, ao voltar pro app, o vídeo retoma de onde o som estava; músicas baixadas e áudio do botão de play já seguem no serviço e agora com a barra de progresso no cartão (duração nos metadados da sessão)** (v0.18.0)
- [x] **CONSERTOS do miniplayer + FIM do áudio fantasma — o handoff do vídeo morria porque o MediaPlayer do serviço pedia o stream SEM o User-Agent que o googlevideo exige (o vídeo na tela usava o UA certo; o serviço, não) e porque o áudio escolhido era Opus (que o MediaPlayer de muitos aparelhos recusa): agora o serviço usa o MESMO UA da extração e o handoff pega M4A; o handoff também subiu pro onUserLeaveHint (app ainda em foreground, início de serviço inbloqueável no Android 12+) com o onStop de rede de segurança e prova de crash; E O ÁUDIO FANTASMA ACABOU: fechar o app dos recentes PARA a música (onTaskRemoved) e arrastar o cartão de mídia pra fora TAMBÉM (deleteIntent) — nunca mais forçar parada** (v0.18.1)
- [x] **MINIPLAYER DE VÍDEO IGUAL YOUTUBE PREMIUM (PiP) — saiu do app com o vídeo tocando? O vídeo segue numa JANELINHA FLUTUANTE de verdade (picture-in-picture), no MESMO player e na MESMA posição (nada de recomeçar do zero), com proporção real do vídeo (retrato vira janelinha em pé); fechar a janelinha PARA tudo (sem áudio fantasma), expandir volta pro player e o fim do vídeo fecha a janelinha sozinho; sem PiP (Android < 8), o cartão de áudio da v0.18.1 segue como plano B; E o bug do "recomeça do zero" morreu: tocar no cartão de mídia não recria mais a faixa — a tela só conecta no que já está tocando** (v0.18.2)
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
