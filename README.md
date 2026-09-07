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
(tag `v0.26.5-android2`) corrige isso e adiciona duas proteções contra as
mudanças recentes do YouTube, sem alterar nada mais:

1. **Compatibilidade Android < 13** — overloads legacy de URLDecoder/URLEncoder
   e `isBlank` reimplementado.
2. **Client TVHTML5 como fonte extra de streams** — o YouTube passou a devolver
   **HTTP 403** nos streams de vídeo de clients sem PoToken (por isso o MP4 às
   vezes falhava). Os URLs do client de TV ainda funcionam sem PoToken para a
   maioria dos vídeos, e têm prioridade na hora de listar os itags.
3. **Fallback de bot-check** — quando o YouTube responde *"Sign in to confirm
   you're not a bot"* ao client Android, o extractor tenta automaticamente o
   visionOS e depois o TVHTML5. As buscas de fallback são opcionais: se todos
   falharem, o erro sobe e o app mostra uma mensagem clara (com retry
   automático).

Assim que o upstream corrigir/absorver essas questões, o fork pode ser
substituído pela versão oficial.

## 🗺️ Roadmap

- [x] **Fase 1** — App Android (APK)
- [x] Conversão para MP3 (LAME embutido, 320/256/192/128 kbps)
- [x] MP4 com áudio (até 720p) + seletor de formato/qualidade
- [x] Configurações de qualidade por formato
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
