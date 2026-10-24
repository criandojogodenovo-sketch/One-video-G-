# Device Test Checklist — v0.8.1 (FIX RELEASE)

**Scope:** dois bugs de dispositivo (realme RMX3624, Android 13) + uma confirmação de layout.
Nada de funcionalidades novas. Código de áudio intocado.

Dispositivo de referência: realme RMX3624, Android 13. APK: `OneVideoG-v0.8.1-release.apk`.

---

## 1. BUG A — texto rodado exporta igual ao preview (crítico)

**Era:** overlay de TEXT rodado (ex.: −55°) exportava como uma linha fina diagonal
(~2 px) na direção da rotação; o preview mostrava a caixa correta.
**Causa raiz:** a semântica real do `OverlaySettings.setScale` do Media3 1.4.1 é
`px_desenhados = px_do_bitmap × scale` (provado contra as imagens de referência
do próprio Media3); a fórmula antiga assumia que o quad ocupava o frame inteiro
e colapsava a altura (1203×192 px desenhados como 757×10.8 px).
**Correção:** escala ÚNICA partilhada (`OverlayLayout.overlayExportScale`) —
`scale = widthFrac × largura_do_frame / largura_do_bitmap` — consumida por
preview e export (uma implementação, dois consumidores). A rotação, posição e
opacidade não mudaram.

Testes no dispositivo:

- [ ] Criar projeto 9:16 (SOURCE), 1 clipe, overlay TEXT de 1 linha (ex. «teste
      de áudio»), rodar −55° com o gizmo, escalar ~0.6.
- [ ] PREVIEW: o texto aparece como caixa rodada legível (gizmo certo).
- [ ] EXPORTAR (P1080). O texto exportado é LEGÍVEL, com o MESMO tamanho,
      ângulo e posição do preview — sem linha fina diagonal.
- [ ] Repetir com rotações 0°, 45° e 90°: em todas o export casa com o preview
      (o novo teste JVM `OverlayExportParityTest` fixa os 4 cantos com
      tolerância de 1 px para exatamente estas rotações).
- [ ] Overlay de IMAGEM rodado também exporta sem distorção (proporção
      preservada em qualquer rotação).
- [ ] Diagnóstico: o evento `ANIM_TEXT_FRAME` (texto animado) mostra agora
      `boxPx` com altura = largura/proporção_do_bloco (antes colapsada);
      `bgWidthPx` novo campo para correlação.

## 2. BUG B — aba de FORMATO presente e funcional

**Era:** o botão de formato (SOURCE/16:9/9:16/1:1/4:3/3:4) não era alcançável na
toolbar. **Causa raiz:** a toolbar cresceu para 15 botões × 44 dp = 660 dp numa
linha SEM scroll — o botão ASPECT era o 9.º, exatamente o primeiro cortado no
limite do ecrã. O enum e o painel nunca saíram do código.
**Correção:** a linha da toolbar faz scroll horizontal e `TOOLBAR_TOOLS` é uma
lista de topo fixada por `ToolBarCoverageTest` (toda a entrada do enum Tool tem
ícone mapeado — a cobertura de painéis é garantida em compilação pelo `when`
exaustivo).

- [ ] A toolbar mostra as ferramentas e FAZ SCROLL horizontal até ao fim.
- [ ] O ícone de FORMATO (AspectRatio) abre o painel com as 6 opções
      (SOURCE/16:9/9:16/1:1/4:3/3:4).
- [ ] Escolher 9:16 num vídeo 16:9: o preview aplica pillarbox E o export
      sai 9:16 (ProjectAspect funciona nas duas pontas).
- [ ] Voltar a SOURCE: o preview volta ao aspecto original do clipe.

## 3. Regressão rápida de áudio (intacto no v0.8.1)

- [ ] Importar 1 clipe de áudio, dar PLAY: ouve-se, sem drift.
- [ ] Fade in/out + keyframes de volume: o preview respeita.
- [ ] EXPORTAR: o áudio exportado mantém fades/volume; a duração do projeto
      continua a ser a do VÍDEO (contrato v0.3.0 — áudio nunca estende).

## 4. CHECK C — pillarbox do preview (fechado, sem ação)

O log `VIEWPORT_GEOMETRY` (container 720 px, viewport 330 px) é o comportamento
PRETENDIDO: `OverlayLayout.fitRect` ajusta o conteúdo 9:16 dentro do palco largo
da janela de edição e a viewport é exatamente o retângulo do vídeo — a mesma
área contra a qual os overlays são posicionados e que o export produz. Não é
regressão; nenhum código alterado.

## 5. Notas técnicas (para o relatório)

- Testes JVM novos: `OverlayExportParityTest` (âncoras douradas do Media3 +
  paridade de cantos preview↔export a −55°/45°/90°/0° em retrato e paisagem),
  `ToolBarCoverageTest`, contratos atualizados em `OverlayLayoutTest`.
- `overlayScaleSettings` (fórmula antiga, semântica errada) foi REMOVIDA —
  `overlayExportScale` é a única fonte da matemática de escala de overlay.
- Se um Media3 futuro mudar a semântica de OverlaySettings, os 3 testes de
  âncora dourada do `OverlayExportParityTest` falham ANTES do release.
