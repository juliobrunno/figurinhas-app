# Fábrica de Figurinhas — contexto para o Claude Code

O dono do projeto (juliobrunno) é leigo em informática e terminal: explique tudo passo a passo, em português, sem jargão.

## O que é
App para criar figurinhas e GIFs e mandar no WhatsApp. Duas partes que usam a mesma tela:
- `www/index.html`: o app inteiro (HTML + CSS + JS num arquivo só). Funções: foto → figurinha (512x512 WebP, até 100 KB), vídeo → GIF (biblioteca gifenc via jsdelivr), galeria (IndexedDB), busca no Giphy (chave grátis salva em localStorage), botão "Enviar no WhatsApp".
- `app/`: app Android (Java, sem Kotlin) que abre o `www` dentro de um WebView (WebViewAssetLoader, endereço `https://appassets.androidplatform.net/assets/www/index.html`).
  - `MainActivity.java`: ponte JavaScript `window.Android` com `shareFile`, `saveFile` e `addPack`.
  - `StickerProvider.java`: ContentProvider no formato oficial do WhatsApp para pacotes de terceiros (`/metadata`, `/stickers/<id>`, `/stickers_asset/<id>/<arquivo>`). Pacotes ficam em `filesDir/packs/<id>/` com `info.json`, `tray.png` e `1.webp`, `2.webp`...

## Regras do WhatsApp já tratadas
- Pacote precisa de 3 a 30 figurinhas: o app aceita a partir de 1 e repete as escolhidas até chegar a 3.
- Pacote não mistura parada com animada: se tiver algum GIF, tudo vira WebP animado (as paradas viram 2 quadros iguais). O WebP animado é montado em JS (`muxWebp`), com GIF lido quadro a quadro pelo `ImageDecoder`; limite 512x512, até 500 KB, até 10 s.
- Ícone do pacote: PNG 96x96.

## Como o APK é gerado
Não há Android Studio na máquina. O GitHub Actions (`.github/workflows/build.yml`) gera o APK a cada push na `main`: copia `www` para `web-assets/www`, roda `gradle :app:assembleRelease` (Gradle 8.9, AGP 8.5.2, Java 17) e publica `Figurinhas.apk` em Releases.
- Link fixo do último APK: https://github.com/juliobrunno/figurinhas-app/releases/latest/download/Figurinhas.apk
- Assinatura: `app/figurinhas.jks` (senha `figurinhas123`). Não trocar a chave, senão o celular não atualiza por cima.
- O job `site` (GitHub Pages) falha até o usuário ativar Settings → Pages → Source: GitHub Actions. Isso não impede o APK.

## Situação em 25/11/2026
- Última versão: 1.0.4 (GIF no pacote e mínimo de 1). Ainda não confirmado num celular com WhatsApp de verdade.
- O usuário pediu foco no APK; o app web (link no navegador) fica para depois.
