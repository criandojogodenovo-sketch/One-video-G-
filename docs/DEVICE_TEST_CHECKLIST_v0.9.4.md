# Device Test Checklist — v0.9.4 (pacote de estabilidade: pegas de corte, sync de áudio, heartbeat OVERLAY_DRAW)

**Escopo:** FIX RELEASE v0.9.3 → v0.9.4 (versionCode 29) — sem features
novas. Três bugs reportados em dispositivo (realme RMX3624, Android 13,
app 0.9.3/28, 817 eventos) + verificação de não-regressão dos 7 fixes
anteriores.

- **BUG 1 (crítico):** arrastar uma pega de corte ("gaveta" estilo CapCut)
  seekava o player a CADA frame do gesto — o decoder ficava em BUFFERING
  permanente (40+ `ACTIVATION seek applied` num gesto, ecrã a piscar/preto,
  `OVERLAY_GATE` a piscar). Agora: preview CONGELADO no último frame
  durante o drag (o player é pausado UMA vez; a SurfaceView mantém o último
  frame — nunca limpa, nunca seekada), a moldura/pegas/chip de duração
  continuam a 60 fps (é só desenho Compose) e o gesto termina com UM
  commit + UM seek para a posição final. Undo/redo continua a ser 1 passo
  por gesto. Sem "peek" da borda durante o drag.
- **BUG 2:** drift de áudio até ~10 s após seek/scrub/trim — o player de
  áudio era criado UMA vez com a janela de corte fixa
  (`ClippingConfiguration`); editar o trim/mover a barra deixava a janela
  STALE (conteúdo deslocado pelo delta da edição) e o corretor só atuava
  no tick de 100 ms com tolerância de 250 ms. Agora: a janela é
  reconstruída (1 re-prepare por edição) quando a assinatura muda, a
  correção acontece com |drift| > 150 ms, e TODO commit de seek/scrub/trim
  sincroniza o áudio IMEDIATAMENTE (restart do loop de sync, sem esperar o
  tick). `AUDIO_SYNC` só registra com |drift| > 100 ms ou na correção.
- **BUG 3:** heartbeat `OVERLAY_DRAW` a ~1 Hz com estado idêntico — o gate
  change-only da v0.9.3 vivia num `remember { }` sem proteção: a recriação
  do slot de composição repunha `lastSig = null` e o mesmo estado voltava a
  registar. Agora o gate vive no opId da sessão (`OverlayDrawLogGates`) e a
  assinatura cobre TODOS os campos registados (id, seg, kind, box,
  transform, visibilidade e content) — reemissão de estado idêntico é
  impossível por construção. `PREVIEW_RENDER` também passou a change-only;
  `VIEWPORT_GEOMETRY` já era (verificado).

Media3 mantido em 1.4.1; zero dependências novas; contratos v0.3.0 (duração
= sequência de vídeo), v0.3.1 (overlays fora da effectiveSignature; preview
GL video-only), paridade preview↔export, MAX_EFFECTS_PER_CLIP e áudio P+Q
intocados (`AudioGainMath` continua a ÚNICA matemática de ganho).

APK: `OneVideoG-v0.9.4-release.apk`. Referência de diagnóstico:
`TRIM_DRAG_FREEZE` (novo, 1 por gesto), `ACTIVATION` (só 1 por gesto de
trim agora), `AUDIO_SYNC` (só correção/residual > 100 ms),
`OVERLAY_DRAW` (só mudança real), `OVERLAY_GATE`, `PREVIEW_RENDER`
(só mudança de geometria).

Testes que travam estas correções (589 no total, 0 vermelhos):
`TrimHandleDragTest` (drag de N movimentos = ZERO seeks intermédios, 1
commit + 1 seek, chip live, cancel sem rasto), `AudioPreviewSyncTest`
(seek 0→20 s = offset ≤ 150 ms imediatamente; drift de playback nunca
> 150 ms sem correção; rebuild da janela em edição; relógio injetado),
`OverlayDrawLogGateTest` (5 s parado = ZERO eventos, mesmo com recriação
de gate; cada mudança real regista exatamente 1 vez), `ThemeSchemeTest`
(trava do colorScheme completo).

---

## 1. Pegas de corte — vídeo (BUG 1)

Projeto com 2+ clipes de vídeo. Selecionar um clipe (moldura laranja +
pegas).

- [ ] Arrastar a pega ESQUERDA durante reprodução: o preview CONGELA no
      último frame (sem piscar, sem preto, sem recarregar), o áudio pausa,
      o clipe encolhe em tempo real, o chip de duração acompanha o dedo.
- [ ] Arrastar a pega DIREITA durante reprodução: mesmo comportamento —
      congelado e fluido, sem flicker.
- [ ] Largar a pega: UM commit (moldura assenta, chip desaparece) e o
      preview salta UMA vez para o frame da borda cortada. Sem bandas
      pretas intermédias, sem "ecrã travado".
- [ ] Arrastar a pega até um limite (0 s, fim da fonte, mínimo de 100 ms):
      haptic leve no limite, sem crash, valor clampado.
- [ ] Desfazer (⟲) após um gesto: volta EXATAMENTE ao estado anterior ao
      gesto (1 passo, não volta ao início do drag).
- [ ] Refazer (⟳): reaplica o trim.
- [ ] No log: 1 gesto = 1 `TRIM_DRAG_FREEZE`, ZERO `ACTIVATION` intermédios
      durante o drag, 1 `ACTIVATION` no fim.

## 2. Pegas de corte — áudio e overlay (mesmo padrão)

- [ ] Arrastar o corpo/pega de uma faixa de áudio durante reprodução: sem
      stutter, sem dessincronização; ao largar, o áudio segue a nova
      posição IMEDIATAMENTE (sem esperar ~10 s ou o próximo tick).
- [ ] Cortar o início/fim de uma faixa de áudio e reproduzir: o conteúdo
      tocado é o da nova janela (não o antigo — era o bug do drift de
      ~10 s).
- [ ] Mover/cortar overlay de texto/imagem: commit único, sem seeks do
      player de vídeo, chip de duração live.

## 3. Sync de áudio (BUG 2)

- [ ] Seek agressivo 0 → 20 s (toque na régua) com faixa de áudio ativa:
      o áudio acompanha de imediato (sem offset percetível).
- [ ] Scrub longo (arrastar o playhead de 0 → 20 s) e largar: no fim do
      gesto o áudio está em sync de imediato.
- [ ] Reprodução longa com áudio: sem drift acumulado (correção a
      |drift| > 150 ms, impercetível).
- [ ] No log: `AUDIO_SYNC` só em correções ou drift residual > 100 ms —
      sem repetição a cada tick com valores iguais.

## 4. Heartbeat de log (BUG 3)

- [ ] Abrir o editor, NÃO tocar em nada durante 5+ segundos (playhead
      parado): o relatório não cresce com `OVERLAY_DRAW` repetidos.
- [ ] Mover/rodar/escalar um overlay: cada gesto gera evidência
      `OVERLAY_DRAW` (a mudança real continua a registar).
- [ ] Entrar/sair de ecrãs (Projetos ↔ Editor) com o mesmo estado: sem
      rajada de `OVERLAY_DRAW` duplicados com valores idênticos.
- [ ] `PREVIEW_RENDER` só aparece quando a geometria do decoder muda de
      facto (troca de clipe, etc.).

## 5. Regressão rápida (os 7 fixes anteriores)

- [ ] **v0.8.1 — paridade de overlay rodado/escalado:** criar texto,
      rodar ~30° e escalar, exportar → no MP4 os cantos batem certo com o
      preview (≤ 1 px), nos 2 aspects (9:16 e 16:9).
- [ ] **v0.8.1 — toolbar com scroll:** todas as ferramentas acessíveis por
      scroll horizontal no ecrã estreito (nenhuma fora do ecrã).
- [ ] **v0.9.1 — blur:** ativar Desfoque num clipe → preview não crasha;
      no export o blur aparece (1 shader program, 2 passes internos).
- [ ] **v0.9.1 — diálogo por código:** (se ocorrer falha de processamento)
      diálogo próprio com «Recarregar pré-visualização» — o botão re-arma o
      preview.
- [ ] **v0.9.1 — gate IDLE/BUFFERING:** sem overlays-fantasma sobre ecrã
      preto/morto.
- [ ] **v0.9.3 — recovery:** (se ocorrer erro de efeitos) 1 retry rápido →
      suspensão com banner honesto «Pré-visualização sem efeitos; a
      exportação continua a aplicá-los.»; o export NUNCA usa a lista
      degradada.
- [ ] **v0.9.3 — tema:** nenhuns radios/switches/chips roxos em Definições,
      exportação, Projetos, Presets, Perfil, diálogos, bottom sheets,
      menus, snackbars; ecrã inicial sem retângulo castanho no "OG".
- [ ] **Export completo (intocado):** exportar um projeto com vídeo + áudio
      + overlay do início ao fim — MP4 gerado, duração certa (contrato
      v0.3.0), sem regressões.
