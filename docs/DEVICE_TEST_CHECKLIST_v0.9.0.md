# Device Test Checklist — v0.9.0 (Fase L — Profundidade, Sombras e Câmara 2.5D)

**Escopo:** Opção A "2.5D bake-to-texture" do estudo de viabilidade aprovado.
Estilos de overlay (sombra/glow cozidos), plano de profundidade por overlay
(zDepth) e câmara virtual do projeto (pan/zoom + keyframes). Zero shaders
novos no caminho de vídeo, zero dependências novas, Media3 mantido em 1.4.1,
áudio (P+Q) intocado.

Dispositivo de referência: realme RMX3624 (realme C33, 4 GB), Android 13.
APK: `OneVideoG-v0.9.0-release.apk`.

---

## 1. Estilo de overlay (sombra/glow) — paridade preview↔export

O estilo é **cozido num bitmap** (`OverlayStyleBake`, matemática pura JVM) que
o preview e o export consomem da MESMA cache — a paridade é por construção;
o que se valida no dispositivo é o resultado visual e a memória.

- [ ] Criar projeto 9:16, 1 clipe, overlay TEXT (1 linha). Abrir a ferramenta
      ESTILO (ícone ✦ na toolbar): escolher «Drop suave» → a sombra aparece no
      preview deslocada para baixo/direita, suave.
- [ ] Escolher «Dura» → sombra sólida sem desfoque (estilo autocolante).
- [ ] Escolher «Glow neon» → halo cian around do texto.
- [ ] «Nenhum» limpa o estilo (o overlay volta exatamente ao visual anterior).
- [ ] EXPORTAR (P1080) com cada preset: o export mostra a MESMA sombra/glow —
      mesma direção, extensão e intensidade que o preview (mesmo bitmap
      cozido; o conteúdo NÃO encolhe — a caixa do conteúdo mantém o tamanho
      do caso sem estilo, o estilo só acrescenta píxeis à volta).
- [ ] Estilo em overlay de IMAGEM: sombra/glow idênticos no export.
- [ ] Texto com ANIMAÇÃO de entrada + estilo: o painel avisa que o estilo é
      ignorado enquanto a animação estiver ativa (limitação documentada v1).
- [ ] Diagnóstico: eventos `OVERLAY_BAKE` (ms, srcPx/outPx, cache=miss) no
      primeiro uso de cada estilo; segundo uso do mesmo estilo = hit da cache
      (sem novo bake).

## 2. Profundidade (zDepth) — oclusão por ordem de desenho

- [ ] Criar 2 overlays TEXT sobrepostos. Ferramenta PROFUND. (ícone cubo 3D):
      slider −1..+1 por overlay. Fundo (−1) desenha ATRÁS do (+1) — mesmo com
      zOrder maior no fundo (a regra D2: profundidade define a ordem quando
      difere; zOrder só desempata).
- [ ] zDepth 0 (default) = plano do vídeo — comportamento idêntico ao v0.8.1.
- [ ] EXPORTAR: a oclusão exportada igual ao preview (a ordem de texturas do
      Media3 segue a mesma comparação profundidade→zOrder).

## 3. Câmara 2.5D — pan/zoom + parallax + keyframes

- [ ] Ferramenta PROFUND., secção «Câmara»: arrastar Pan X/Pan Y → o vídeo E
      os overlays movem-se JUNTOS (plano zDepth 0 acompanha exatamente o
      vídeo); zoom 2× → tudo amplia centrado.
- [ ] Overlay com zDepth −1: move-se MENOS que o vídeo (parallax); com +1:
      move-se MAIS. O movimento relativo é o efeito de profundidade.
- [ ] Keyframe de câmara no cursor (botão): mover o playhead, mudar pan/zoom,
      adicionar outro keyframe → o preview anima a câmara entre eles;
      interpolação Linear/Smooth muda a curva.
- [ ] EXPORTAR: o export segue a MESMA animação de câmara (zoom corta as
      bordas — recorte de câmara; pan mostra bordas pretas onde o vídeo sai —
      comportamento documentado).
- [ ] Nota de UX: cada commit da câmara re-prepara o preview por um momento
      (a câmara é input GL — mesma classe que cor/efeitos; documentado).
- [ ] Sem câmara (default): ZERO instâncias de efeito — o v0.9.0 renderiza
      byte-idêntico ao v0.8.1 (regressão coberta pela suite JVM).

## 4. Memória (C33 — 4 GB, item de medição L4)

- [ ] Projeto com 3 overlays estilizados em simultâneo (1 texto glow, 1 texto
      sombra, 1 imagem sombra): preview fluido, sem crash/OOM.
- [ ] Diagnóstico: `OVERLAY_BAKE` com outPx plausível (ex.: bloco 1203×192 →
      ~1225×216 com drop suave) e evictions quando muitos estilos convivem
      (teto dedicado de 12 MB).
- [ ] Export dos 3 estilos em simultâneo: bitmaps corretos, tempos de export
      comparáveis ao v0.8.1 (o bake é one-off, não por frame).

## 5. Regressão (intocado no v0.9.0)

- [ ] Texto SEM estilo: renderização Compose normal (sem mudança — delta
      cosmético documentado desde v0.5.9).
- [ ] Áudio (P+Q): play + fades + keyframes + gate; export mantém tudo
      (contrato v0.8.0 intacto).
- [ ] Transições (H) e curvas/rampas (J/K): preview e export inalterados.
- [ ] Duração do projeto = sequência de vídeo (contrato v0.3.0 — a câmara e
      os estilos nunca estendem).
- [ ] Toolbar: as 15 ferramentas fazem scroll; PROFUND. e ESTILO presentes
      com ícones distintos (`ToolBarCoverageTest` fixa a cobertura).

## 6. Notas técnicas (para o relatório)

- Spike L2b (portão D4): INTEGRADO — mas com veículo corrigido pelas fontes
  reais: `ScaleAndRotateTransformation` REDIMENSIONA o frame de saída (zoom
  uniforme = no-op visual a jusante do Presentation SCALE_TO_FIT; sem pan), por
  isso a câmara do plano de vídeo usa `MatrixTransformation` (matriz por frame,
  tamanho fixo, corte fora do NDC). `VideoPlaneCameraParityTest` mede os 4
  cantos de um quad de prova: preview↔export ≤ 1 px em 9:16 e 16:9, incluindo
  poses com keyframes.
- A câmara entra na `effectiveSignature` (input GL); overlays (zDepth/estilo)
  continuam FORA (contrato v0.3.1 intacto — `ClipEffectsFactoryTest`).
- Paridade de estilo: mesma instância de bitmap da mesma cache (`styledKey` =
  contentKey + estilo) + `boxScale` partilhado — o conteúdo mantém a caixa
  exata do caso sem estilo (`OverlayExportParityTest` estendido).
- Backup do gate: se um Media3 futuro mudar a cadeia de transformação do
  vertex shader, os cantos do `VideoPlaneCameraParityTest`/`OverlayExportParityTest`
  falham ANTES do release.
