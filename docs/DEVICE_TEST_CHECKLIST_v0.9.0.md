# Device Test Checklist — v0.9.0 (Fase L: 2.5D — Estilo cozido + Profundidade & Câmara)

**Escopo:** Fase L (Opção A — bake-to-texture). ZERO shaders novos no caminho
de vídeo, ZERO dependências novas, Media3 1.4.1, áudio (P+Q) intocado.

Dispositivo de referência: realme RMX3624, Android 13. APK:
`OneVideoG-v0.9.0-release.apk`. Baseline JVM: **535 testes, 0 falhas**
(505 pré-existentes + 30 novos).

Contratos verificados por testes: v0.3.0 (duração = sequência de vídeo —
intocada), v0.3.1 (overlays fora da `effectiveSignature`; preview GL
video-only), `MAX_EFFECTS_PER_CLIP`, paridade de cantos ≤1 px
(`OverlayExportParityTest` estendido: câmara × profundidades × tempos ×
2 aspects + estilo via caminho bitmap).

---

## 1. Estilo (sombra/glow cozidos) — paridade preview ↔ export

**Implementação:** `OverlayStyleBake` puro JVM (sombra = alpha deslocada +
gaussiana separável; glow = alpha desfocada tintada) → bitmap cozido na
MESMA `LruCache` para preview e export (D1: `rendersViaBitmap(overlay) =
(overlay.style != null)`). O texto SEM estilo mantém o caminho Compose Text
(delta cosmético documentado).

Testes no dispositivo:

- [ ] Criar projeto 9:16, 1 clipe, overlay TEXT. Painel **Estilo** → preset
      «Sombra suave»: o preview mostra o texto com sombra difusa.
- [ ] EXPORTAR (P1080). A sombra exportada é IGUAL à do preview (mesma
      direção, difusão e intensidade) — nada de sombra duplicada nem
      ausente.
- [ ] Repetir com «Sombra dura» e «Glow neon» (o halo neon fica da cor
      ciano do preset).
- [ ] Voltar ao preset «Nenhum»: o overlay volta ao aspeto anterior ao
      estilo (sem restos de bake).
- [ ] Overlay de IMAGEM com «Sombra suave»: igual no export (o bake aplica
      ao bitmap decodificado).
- [ ] Ajuste fino: arrastar os sliders (Deslocamento X/Y, Desfoque,
      Opacidade) e re-exportar — o resultado segue os valores.
- [ ] Regressão: texto SEM estilo exporta exatamente como na v0.8.1 (o
      caminho Compose/bitmap antigo não mudou).

## 2. Profundidade (zDepth) e oclusão

- [ ] Criar 2 overlays sobrepostos (ex.: texto + imagem). No painel
      **Profundidade e câmara**, definir profundidade +0.5 (fundo) na
      imagem e −0.5 (frente) no texto: a IMAGEM passa a desenhar ATRÁS do
      texto independentemente do zOrder.
- [ ] Os botões «trazer para a frente/enviar para trás» continuam a operar
      o zOrder (desempate dentro da mesma profundidade).
- [ ] Exportar: a oclusão (quem fica na frente) é IDÊNTICA ao preview.

## 3. Câmara de profundidade (camadas) + keyframes

- [ ] No mesmo projeto, deslocar Pan X da câmara (ex. +0.6) e definir
      profundidades diferentes nos 2 overlays: no preview, a camada de
      fundo desloca-se MENOS que a da frente (parallax). O PLANO DE VÍDEO
      NÃO SE MOVE (limitação documentada v0.9.0 — a câmara é "de
      profundidade (camadas)").
- [ ] Zoom da câmara 1.5–2.0: a camada de frente cresce mais que a de
      fundo; o vídeo mantém o tamanho.
- [ ] Adicionar 2 keyframes de câmara (botão «Keyframe de câmara no
      cursor») em tempos diferentes com pans opostos e reproduzir: a
      câmara interpola (linear/suave conforme o chip escolhido) e o
      parallax acompanha ao longo do tempo.
- [ ] Mover/remover keyframes de câmara (±100 ms, apagar) — mesmo padrão
      dos keyframes de overlay.
- [ ] EXPORTAR com câmara + keyframes: as posições/tamanhos das camadas
      exportadas coincidem com o preview (o teste JVM fixa cantos ≤1 px
      para câmara × profundidade × tempo × 2 aspects).

## 4. Memória do bake (L4)

- [ ] Estilizar 3 overlays (sombra/glow) num projeto com C33 (clipes longos)
      e editar textos repetidamente: sem OOM, sem travamentos perceptíveis.
- [ ] Diagnóstico: o evento `OVERLAY_BAKE` mostra `px`, `bytes`,
      `elapsedMs`, `cacheHit` e `evictions` — após muitas edições, os
      `evictions` sobem apenas quando o teto dedicado (12 MB de bitmaps
      estilizados dentro da cache partilhada de 20 MB) é atingido; os
      bitmaps de conteúdo nunca são expulsos por bakes novos.
- [ ] O item de MEDIÇÃO em device continua pendente (apontado no resumo
      técnico): os números JVM locais são `px`/`bytes` por bake e o teto
      dedicado; a medição real de RSS/PSS com 3 estilos no C33 fica para o
      teste físico abaixo.

## 5. Regressão geral (a espinha dorsal não mexeu)

- [ ] Texto SEM estilo: render/gizmos/hit-test/export como na v0.8.1.
- [ ] Texto ANIMADO (entrada por palavra/letra) continua a funcionar; se
      tiver estilo, a ANIMAÇÃO vence (o painel Estilo avisa) e o export
      mostra `OVERLAY_STYLE_SKIPPED`.
- [ ] Áudio (P+Q): reprodução, fades, keyframes de volume, noise gate —
      intocado.
- [ ] Transições, curvas de cor, rampas de velocidade — intocadas.
- [ ] Duração do projeto = sequência de vídeo (contrato v0.3.0) — importar
      áudio longo não estica a linha temporal.
- [ ] Desfazer/refazer cobre as novas edições (profundidade, estilo,
      câmara, keyframes de câmara).
- [ ] pt/en: os dois painéis novos («Profundidade e câmara», «Estilo») têm
      strings completas nas duas línguas (LocalizationTest verde).
- [ ] Toolbar: os 2 botões novos (ViewInAr = Profundidade, AutoAwesome =
      Estilo) acessíveis por scroll (ToolBarCoverageTest verde).
- [ ] Schema: abrir um projeto gravado na v0.8.1 — tudo carrega igual
      (round-trip v5→v6 testado em JVM); gravar e reabrir preserva
      profundidade/estilo/câmara.
