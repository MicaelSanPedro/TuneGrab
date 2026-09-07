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

- **Cole o link e toque em “Baixar”** — é só isso. O app escolhe a melhor qualidade
  automaticamente (M4A de maior bitrate) e inicia o download na hora
- Compartilhe direto do YouTube (“Compartilhar → TuneGrab”) e o download começa sozinho
- Prefere escolher? Toque em **“Escolher qualidade (opcional)”** e selecione entre
  **M4A** (recomendado) ou **Opus/WebM**, conforme disponível
- Ícone de colar na área de transferência dentro do campo de link
- Download com **notificação de progresso**
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
| Rede | OkHttp |
| Imagens | Coil |
| Mínimo | Android 7.0 (API 24) |
| Alvo | Android 14 (API 34) |

## 🚀 Buildar você mesmo

Requisitos: JDK 17 e Android SDK 34.

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
(tag `v0.26.5-android1`) corrige exatamente esses três pontos, sem mudar
nenhum comportamento. Assim que o upstream corrigir, o fork pode ser
substituído pela versão oficial.

## 🗺️ Roadmap

- [x] **Fase 1** — App Android (APK)
- [ ] Conversão para MP3 (ffmpeg embutido)
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
