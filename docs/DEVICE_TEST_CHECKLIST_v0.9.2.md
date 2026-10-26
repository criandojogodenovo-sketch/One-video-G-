# Device Test Checklist — v0.9.2 (UI redesign)

**Dispositivo de referência:** realme RMX3624 (Mali-G57, Android 13)
**Variante:** release assinada (CI) — `OneVideoG-v0.9.2-release.apk`
**Âmbito:** apenas interface, identidade visual e animações. Nenhuma lógica de edição, exportação ou timeline foi alterada (paridade intacta).

> Nota de âmbito: o redesign trocou a paleta de interface do editor (âmbar → laranja vidrado) e o acento dos gizmos/playhead. Os valores de cor **escolhidos pelo utilizador** nos overlays (paleta de cores do texto) mantêm-se exatamente iguais.

## 1. Ícone novo
- [ ] Ícone no launcher mostra a forma fluida de metal escuro com luz laranja interior e as duas esferas laranja brilhantes sobre fundo preto.
- [ ] Ícone circular (launcher redondo) e quadrado com cantos arredondados — nada cortado em nenhum formato de máscara.
- [ ] Ícone temático (themed icon) do Android 13 mostra a silhueta branca correta quando o launcher usa ícones monocromáticos.
- [ ] Ícone nos recentes / definições do sistema consistente com o do launcher.

## 2. Ecrã Início (tab Início)
- [ ] Topo: logo (ícone) + "One Video G" + avatar + engrenagem (definições).
- [ ] Avatar abre a tab Perfil; engrenagem abre Definições.
- [ ] Banner de destaque com a tesoura estilo anime e a frase "Corta. Cria. Evolui." (PT) / "Cut. Create. Evolve." (EN).
- [ ] Animação das letras "O" e "G" no centro: entram baixas, sobem com spring, brilho laranja a "respirar" em loop calmo — suave, sem solavancos.
- [ ] Botão grande "Nova composição" (laranja metálico vidrado, brilho suave) abre o diálogo de nome.
- [ ] Botão "Projeto vazio" continua disponível e cria um projeto sem vídeo (timeline preta, 5 s).
- [ ] Cartão "Projetos" com contagem; tocar abre a tab Projetos.
- [ ] Secção "Seus projetos" mostra miniatura, duração e data (até 4 recentes); "Ver todos" abre a tab Projetos.
- [ ] Estado vazio (sem projetos) mostra a mensagem correta em PT e EN.

## 3. Barra inferior e tabs
- [ ] Barra inferior grafite com: Início · Projetos · (+) central · Presets · Perfil.
- [ ] Botão (+) central laranja vidrado abre o diálogo de nova composição.
- [ ] Mudança de tab com transição suave fade + slide curto (200–350 ms).
- [ ] Item selecionado fica laranja; os restantes cinza.
- [ ] Tab Projetos: lista completa, renomear e apagar pelo menu (⋮) funcionam.
- [ ] Tabs Presets e Perfil mostram "Em desenvolvimento".

## 4. Diálogos, menus e feedback
- [ ] Diálogos (novo projeto, renomear, apagar) entram com scale + fade suave.
- [ ] Botões principais têm feedback ao tocar (ligeiro encolher com spring) e brilho laranja.
- [ ] Dropdowns e menus do editor animam suavemente.

## 5. Identidade em todo o app
- [ ] Fundo preto profundo (#050505 → #0D0F14) em todos os ecrãs.
- [ ] Superfícies em cinza-grafite metálico com borda fina clara em cima (efeito de luz).
- [ ] Definições e Export respeitam o novo tema; top bar redesenhada.
- [ ] Tema claro (Definições) continua legível e coerente com o laranja da marca.
- [ ] Idioma PT/EN troca todas as strings novas corretamente.

## 6. Regressão rápida (lógica intocada)
- [ ] Editor: importar vídeo, preview, cortar, dividir, mover clipes.
- [ ] Timeline: playhead e região inativa com o novo laranja; gizmos de seleção laranja.
- [ ] Efeitos (blur, vinheta, nitidez, curvas, rampas), estilos, câmara 2.5D.
- [ ] Áudio multipista: ganho, mutes.
- [ ] Export completo num projeto pequeno — o resultado é idêntico ao de v0.9.1 (paridade).
- [ ] v0.5.2/v0.5.6: duplo-toque em "Nova composição"/(+) não cria dois projetos.

## 7. Desempenho (realme RMX3624)
- [ ] Animações a 60 fps no ecrã Início e nas transições; sem jank visível.
- [ ] Scroll da lista de projetos fluido.
- [ ] A app abre sem pausa anormal; animação O/G só no arranque do ecrã (loop calmo do brilho).
- [ ] Sem fugas visíveis: voltar de editor → início não aquece nem drena bateria.
