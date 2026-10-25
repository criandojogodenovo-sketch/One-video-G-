# Device Test Checklist — v0.9.1 (correções: crash de blur no preview, diálogo/retry, gate IDLE)

**Escopo:** FIX RELEASE sobre a v0.9.0 — sem features novas. Três bugs
reportados em dispositivo (realme RMX3624 / Mali-G57, Android 13) com
relatório de diagnóstico completo:

- **BUG A (crítico):** crash determinístico do preview ao ativar Desfoque
  quando a câmara está na cadeia (NPE na thread GL →
  `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED` → player morto). Correção no
  caminho partilhado: o blur é agora UM `GlShaderProgram` com os dois
  passes separáveis dentro (padrão `SeparableConvolutionShaderProgram` do
  próprio Media3 1.4.1) — mesma shader, mesmos pesos, mesma ordem H→V.
  Além disso, degradação graciosa honesta se algum aparelho ainda falhar.
- **BUG B (UX):** `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED` mapeado por
  código → diálogo próprio («A pré-visualização falhou ao processar os
  efeitos») com ação **«Recarregar pré-visualização»** que re-prepara o
  player no playhead atual e re-arma o budget de recovery.
- **BUG C (menor):** o gate de overlays também suprime em
  `playerState == IDLE` (não desenhar geometria fantasma sobre um player
  morto).

Media3 mantido em 1.4.1; zero dependências novas; áudio (P+Q) intocado;
contratos v0.3.0/v0.3.1/paridade intocados.

APK: `OneVideoG-v0.9.1-release.apk`. Referência de diagnóstico: eventos
`PLAYER_ERROR`, `PLAYER_RECOVERED`, `PLAYER_RECOVERY_SKIPPED`,
`PREVIEW_GL_SUSPENDED`, `PREVIEW_RELOADED`, `EFFECT_ATTACH`
(`degraded=true`), `OVERLAY_GATE`.

---

## 1. Matriz de reprodução {câmara × efeitos} no preview (BUG A)

Cada linha = preview do mesmo clipe 9:16 (1080p), 30 s, sem tocar em mais
nada. Registar se o preview continua vivo (sem PLAYER_ERROR) e se o efeito
é visível.

- [ ] Câmara OFF + Desfoque 0.5 → preview vivo, blur visível (antes de
      v0.9.0 isto já funcionava — continua igual).
- [ ] Câmara ON (pan ou zoom) + Desfoque 0.5 → **o caso que crashava na
      v0.9.0** — preview vivo, blur visível, sem `PLAYER_ERROR`.
- [ ] Câmara ON + Vignette 0.5 → preview vivo, vinheta visível.
- [ ] Câmara ON + Sharpen 0.5 → preview vivo, nitidez visível.
- [ ] Câmara ON + Curvas (qualquer preset) → preview vivo (regressão do
      caso que já funcionava).
- [ ] Câmara ON + Desfoque + Vignette (2 efeitos) → preview vivo.
- [ ] Diagnóstico: `EFFECT_ATTACH` com `effects` consistente (blur = 1
      efeito desde a v0.9.1 — o par H/V vive DENTRO do programa único).

## 2. Export com blur (nunca exercitado no relatório do device)

- [ ] Exportar (P1080) projeto com Câmara ON + Desfoque 0.5 → export
      concluído, blur presente no ficheiro, igual ao preview do mesmo
      ponto (mesma lista de efeitos partilhada).
- [ ] Exportar com Câmara + Desfoque + Vignette → igual ao preview.

## 3. Diálogo + retry (BUG B)

- [ ] Diagnóstico: provocar (ou simular) `PLAYER_ERROR` com código
      `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED` → o diálogo mostra
      «A pré-visualização falhou» com a mensagem PRÓPRIA (não a de
      «ficheiro em falta/formato não descodificável») e o botão
      **«Recarregar pré-visualização»**.
- [ ] Tocar em «Recarregar pré-visualização» → o player re-prepara no
      playhead atual (`PREVIEW_RELOADED` no diagnóstico); se a cadeia
      ficar saudável, o preview volta.
- [ ] Erros de OUTRO código (ex.: ficheiro removido) continuam com a
      mensagem genérica antiga e SEM o botão de recarregar.
- [ ] Budget: após esgotar as 3 tentativas automáticas
      (`PLAYER_RECOVERY_SKIPPED`), um reload manual re-arma o budget —
      o editor NÃO fica morto até reabrir o projeto.

## 4. Degradação graciosa honesta (só se o aparelho continuar a falhar)

- [ ] Se após o esgotamento do budget + reload manual o processamento
      falhar outra vez: o preview cai para a lista degradada
      (velocidade + geometria) e mostra o banner fixo «Este aparelho não
      consegue pré-visualizar alguns efeitos de vídeo. A pré-visualização
      é mostrada sem eles; a exportação mantém-se correta.»
      (`PREVIEW_GL_SUSPENDED` + `EFFECT_ATTACH` com `degraded=true`).
- [ ] Com o banner ativo: o player está VIVO (dá play/pause/seek), o
      ritmo dos clips com velocidade/rampas mantém-se, a geometria
      (letterbox do formato) mantém-se.
- [ ] EXPORTAR com o banner ativo: o export mantém TODOS os efeitos
      (a lista degradada é EXCLUSIVA do preview).

## 5. Gate IDLE (BUG C)

- [ ] Diagnóstico: com o player morto/parado (IDLE), os overlays NÃO são
      desenhados no stage — `OVERLAY_GATE` com
      `drawing=false … playerState=IDLE`.
- [ ] Ao voltar a READY, `OVERLAY_GATE` `drawing=true` e os overlays
      reaparecem. Em `ENDED` (fim do clipe) os overlays CONTINUAM
      desenhados (o último frame real continua no ecrã).

## 6. Regressão rápida (nada disto devia ter mudado)

- [ ] Estilos de overlay (sombra/glow) — visual e export iguais à v0.9.0.
- [ ] Profundidade (zDepth) + câmara — parallax e oclusão iguais.
- [ ] Rampas de velocidade e curvas — preview e export iguais.
- [ ] Áudio (mult pista, fades, keyframes de ganho) — intocado.
- [ ] Duração do projeto = duração da sequência de vídeo (contrato v0.3.0).
- [ ] Overlays fora da assinatura efetiva: editar overlay NÃO re-prepara
      o decoder (contrato v0.3.1).
