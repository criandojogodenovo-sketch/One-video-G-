# Device test checklist — One Video G v0.5.2 (fluxo "Novo projeto" + botão de vídeo + projeto vazio fim-a-fim)

Physical verification for the five v0.5.2 fixes. NOT run automatically —
nothing below may be claimed as verified until executed on a real device
(same class as the v0.3.x–v0.5.1 test device: Android 13, 4 GB RAM).

## Setup

1. Install `OneVideoG-v0.5.2-release.apk` over the existing install
   (signature must be accepted — same keystore identity as v0.2.x–v0.5.1).
2. Keep at least one project with video + overlays created in v0.5.1 for
   the backward-compatibility sweep.

## REGRA GERAL — "Projeto vazio" nunca exige vídeo

- [ ] "Projeto vazio" (home) abre o editor DIRECTAMENTE, sem nenhum
      seletor de vídeo e sem pedidos.
- [ ] No projeto vazio: adicionar texto, imagem e áudio funciona SEM
      qualquer vídeo (cada um vira clipe, fica selecionado).
- [ ] O botão "Adicionar vídeo" (câmera, barra inferior do editor) está
      SEMPRE ativo — nunca desligado — mas nunca é obrigatório.

## 1) Fallback do "Novo projeto"

- [ ] "Novo projeto" (home): nome → ABRIR seletor de vídeo → escolher um
      vídeo → o projeto é criado JÁ com o vídeo (o clipe aparece na
      timeline, duração = duração do vídeo).
- [ ] Cancelar o seletor de vídeo: VOLTA à lista sem criar nenhum
      projeto (verifique que a lista não ganhou entradas).
- [ ] Não existe mais nenhum caminho onde "Novo projeto" termine num
      projeto sem vídeo.

## 2) Botão de vídeo no projeto vazio

- [ ] A câmera riscada (laranja) da barra inferior do editor foi
      substituída por "Adicionar vídeo" (câmera normal) e abre o seletor
      imediatamente.
- [ ] Importar o vídeo num projeto vazio: o vídeo vira a BASE do projeto,
      a duração passa a ser a dele, e os overlays (texto/imagem) e áudios
      já adicionados são MANTIDOS e continuam visíveis/audíveis.
- [ ] Projectos COM vídeo: "Adicionar vídeo" acrescenta o clipe ao final
      (comportamento v0.5.1 inalterado).

## 3) Anti-duplicação

- [ ] Toque duplo rápido em "Projeto vazio" (home): cria EXATAMENTE um
      projeto e navega UMA vez (a lista não ganha duplicados).
- [ ] Toque duplo rápido em "Novo projeto" + confirmação: nenhum projeto
      extra é criado.
- [ ] Nada é criado ao apenas ENTRAR numa tela (navegar home → editor →
      voltar sem tocar em nada não cria projetos).
- [ ] Sair do editor (seta ← ou gesto/garra do sistema) com um projeto
      COM conteúdo: o projeto continua na lista.
- [ ] Sair do editor com um projeto 100% vazio (0 clipes, 0 overlays,
      0 áudios): o projeto é apagado automaticamente da lista.

## 4) Preview do projeto vazio

- [ ] Projeto vazio: o preview mostra FUNDO PRETO com a duração do
      projeto (5 s iniciais).
- [ ] Texto/imagem ativos no tempo atual são desenhados sobre o fundo
      preto, com ou sem seleção (a moldura/✕/handle só no selecionado).
- [ ] A mensagem "A linha temporal está vazia" NÃO aparece quando há
      overlays (só num projeto totalmente vazio).
- [ ] PLAY avança o playhead (relógio próprio, sem player de vídeo) e,
      quando estacionado no fim, o play REINICIA do início.
- [ ] Áudio importado toca em sincronia com a timeline (play/pause/seek).

## 5) Export do projeto vazio

- [ ] Projeto vazio com texto/imagem/áudio: o export TERMINA sem
      IllegalStateException (o arquivo abre, fundo preto, duração
      correta, overlays sobrepostos, áudio presente).
- [ ] Projeto vazio totalmente vazio: o export não é iniciado com
      sucesso falso (comportamento de erro adequado) — e o projeto é
      apagado ao sair (item 3).
- [ ] Projeto COM vídeo: o export continua idêntico ao v0.5.1
      (regressão nenhuma — o setFrameRate aplica-se só à base preta
      sem vídeo).

## Compatibilidade

- [ ] Abrir um projeto v0.5.1 com vídeo + overlays + áudio: tudo no lugar
      (schema v2 intocado).
- [ ] Renomear/apagar projetos da lista continua funcionando.
- [ ] Undo/redo dentro do editor continua funcionando (um passo por
      operação).
