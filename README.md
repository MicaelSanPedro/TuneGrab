# ♪ TuneGrab

> Baixe o áudio de vídeos do YouTube direto no seu Android — simples, leve e open source.

![Build](https://github.com/MicaelSanPedro/TuneGrab/actions/workflows/build.yml/badge.svg)
![Release](https://img.shields.io/github/v/release/MicaelSanPedro/TuneGrab?include_prereleases)
![License](https://img.shields.io/github/license/MicaelSanPedro/TuneGrab)

TuneGrab é um app Android que extrai faixas de áudio de vídeos do YouTube e salva
os arquivos na pasta **Downloads/TuneGrab** do seu aparelho. Ele usa a mesma engine
do [NewPipe](https://github.com/TeamNewPipe/NewPipe) (NewPipeExtractor), o que
significa máxima compatibilidade e correções rápidas sempre que o YouTube muda algo.

## ✨ Funcionalidades

- **Cole o link e toque em “Baixar”** — o app pergunta **qual formato e qualidade**
  você quer e baixa na hora
- **3 formatos, qualidades configuradas separadamente** (em ⚙️ Configurações):
  - **MP3 320/256/192/128 kbps** — convertido **no próprio celular**
    (MediaCodec decodifica + LAME nativo codifica), com ID3 e nome da música
  - **M4A** — o áudio original do YouTube, sem reconversão (melhor fidelidade)
  - **MP4** — vídeo com áudio embutido (até 720p, resolução máxima que o YouTube
    serve com áudio junto)
- O app **lembra a última escolha** de formato e qualidade
- Compartilhe direto do YouTube (“Compartilhar → TuneGrab”) e o seletor abre na hora
- Ícone de colar da área de transferência no campo de link
- Download com **notificação de progresso em fases** (baixando → convertendo)
- Arquivos salvos em `Downloads/TuneGrab`
- Suporte a links `youtube.com`, `m.youtube.com`, `music.youtube.com` e `youtu.be`

## 📥 Instalação

1. Vá até a página de [**Releases**](https://github.com/MicaelSanPedro/TuneGrab/releases)
2. Baixe o `TuneGrab-x.y.z.apk` mais recente
3. Abra o APK (permita "instalar de fontes desconhecidas" se solicitado)
4. Pronto! Cole um link e baixe sua primeira música 🎵

> Alternativa: baixe o APK gerado em cada *run* do CI na aba
> [Actions](https://github.com/MicaelSanPedro/TuneGrab/actions) (artifact).

## 🔧 Como funciona

O app é **100% independente** — não existe servidor de download. A extração e o
download acontecem no próprio dispositivo:

```
YouTube ──▶ TuneGrab (no seu celular) ──▶ Downloads/TuneGrab
             │
             └─ NewPipeExtractor: resolve metadados e stream URLs
             └─ OkHttp: baixa a faixa com progresso
             └─ MediaStore: salva o arquivo (API 29+) ou File API (API 24–28)
```

Vantagens dessa arquitetura: **custo zero** de infraestrutura, **privacidade**
(nenhum servidor intermediário vê o que você baixa) e **sem limites** impostos
por terceiros.

## 🏗️ Stack

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin |
| UI | Material 3 (Views + ViewBinding) |
| Extração | [NewPipeExtractor v0.26.5](https://github.com/TeamNewPipe/NewPipeExtractor) — [fork compatível com Android antigo](https://github.com/MicaelSanPedro/NewPipeExtractor/tree/android-compat) |
| Conversão MP3 | LAME 3.100 (libmp3lame) via NDK + MediaCodec |
| Rede | OkHttp |
| Imagens | Coil |
| Mínimo | Android 7.0 (API 24) |
| Alvo | Android 14 (API 34) |

## 🎵 Sobre o MP3 (transparência)

O YouTube não serve MP3 — só **M4A (AAC)** e **Opus**. Por isso o TuneGrab
**converte no seu aparelho**: baixa a melhor faixa, decodifica com o MediaCodec
(hardwares do próprio telefone) e recodifica com o LAME 320 kbps. Detalhe técnico
honesto: a fonte tem até ~160 kbps, então um MP3 320 preserva (mas não adiciona)
qualidade — é o padrão que muita gente pede para compatibilidade com players,
pen drives e carros.

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

## 🧩 Sobre o fork do NewPipeExtractor

A v0.26.5 oficial do NewPipeExtractor usa `URLDecoder.decode(String, Charset)`,
`URLEncoder.encode(String, Charset)` (Java 10) e `String.isBlank()` (Java 11) —
APIs que só existem no **Android 13 (API 33)+**. Em aparelhos mais antigos isso
lança `NoSuchMethodError` (é o famoso crash/erro ao buscar). O
[fork `MicaelSanPedro/NewPipeExtractor`](https://github.com/MicaelSanPedro/NewPipeExtractor/tree/android-compat)
(tag `v0.26.5-android3`) corrige isso e adiciona proteções contra as mudanças
recentes do YouTube, sem alterar nada mais:

1. **Compatibilidade Android < 13** — overloads legacy de URLDecoder/URLEncoder
   e `isBlank` reimplementado.
2. **Clients extras de streams** — TVHTML5 e visionOS (android_vr), cujos URLs
   de vídeo ainda funcionam sem PoToken para a maioria dos vídeos.
3. **Fallback de bot-check em cascata** — quando o YouTube responde *"Sign in
   to confirm you're not a bot"* ao client Android, o extractor tenta o WEB
   com PoToken, depois visionOS e TVHTML5.
4. **Suporte completo a `PoTokenProvider`** — o app implementa a interface e
   alimenta o extractor com PoTokens reais (ver abaixo), incluindo um novo
   player request do client WEB com PoToken (client WEB+PoToken não existia na
   v0.26.5) e o client iOS com PoToken (URLs diretas de áudio/vídeo).

## 🔐 Como o TuneGrab vence o anti-bot do YouTube (PoToken/BotGuard)

Desde 2025/2026 o YouTube exige *proof-of-origin tokens* (PoTokens) e responde
com *"Sign in to confirm you're not a bot"* para clientes anônimos — inclusive
apps. O TuneGrab resolve isso **no próprio aparelho, sem servidor**:

1. Baixa a homepage do YouTube (OkHttp) e extrai o `ytcfg` + o desafio atual do
   BotGuard (`window.ytAtN`), que muda o tempo todo;
2. Baixa o *interpreter JavaScript* do desafio;
3. Roda a VM do BotGuard num **WebView fora da tela** (página local, sem
   acesso à rede) e tira um *snapshot* — exatamente como o site faz no
   navegador;
4. Troca o snapshot por um *integrity token* (válido ~12h) no endpoint
   `api/jnn/v1/GenerateIT` que o próprio player do YouTube usa;
5. Gera PoTokens "mintados" por vídeo e os envia nos player requests (clients
   WEB/ANDROID/iOS) e nos URLs de stream (parâmetro `pot`).

Com um PoToken válido, o YouTube trata a requisição como um navegador real e
devolve os streams. A sessão fica em cache (12h); se algo falhar, ela é
recriada automaticamente e o download é tentado de novo. Se o aparelho não
tiver WebView, o app volta para os clients anônimos do extractor.

Assim que o upstream corrigir/absorver essas questões, o fork pode ser
substituído pela versão oficial.

## 🗺️ Roadmap

- [x] **Fase 1** — App Android (APK)
- [x] Conversão para MP3 (LAME embutido, 320/256/192/128 kbps)
- [x] MP4 com áudio (até 720p) + seletor de formato/qualidade
- [x] Configurações de qualidade por formato
- [x] Bypass do anti-bot (PoToken/BotGuard via WebView)
- [ ] Fila de downloads / múltiplos links
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
A conversão MP3 usa [LAME 3.100](https://lame.sourceforge.io/) (LGPL), vendida
em `app/src/main/cpp/lame/` com direitos reservados aos autores do LAME.
