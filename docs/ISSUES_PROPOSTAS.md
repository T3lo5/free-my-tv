# Propostas de melhoria (com base na pesquisa dos agentes)

## 1) Shuffle: sortear de todo conteúdo TMDB já consumido

> **Status (27/10):** feito e **testado na TV** (sorteou "The Odyssey", que não estava nos cards visíveis). O pool vive no
> `Recs` (`pool`, limite de 300 títulos por sessão) e é alimentado pela lista salva em disco e por cada hora nova;
> `random()` sorteia dele, nunca repete o último título e cai para a lista visível quando o pool está vazio.

**Problema**
Hoje o botão "Sorteio" (`SHUFFLE`) escolhe aleatoriamente apenas dentre os conteúdos que estão visíveis/recomendados na tela. Seria muito mais interessante sortear de todo o conteúdo que já foi consumido pela API do TMDB nesta sessão.

**Como funciona hoje (pesquisa)**
- `HomeView.drawShuffle()` / `HomeView.shuffleOpen()` sorteiam a partir dos `picks` já carregados/visíveis.
- Os conteúdos vêm das requisições TMDB feitas para preencher as abas da home.
- Não há pool global acumulado; o sorteio é restrito ao conjunto renderizado.

**Solução proposta (baixo risco)**
- **Pool unificado**: criar `ArrayList<Item>` + `HashSet<String>` com chave `type:id` (`movie:12345` / `tv:12345`) em `HomeView` (ou em um holder compartilhado). Acumular todo item que chegar das chamadas TMDB em todas as abas.
- **Sortear global**: em `shuffleOpen()`, priorizar sortear do pool global; manter fallback para o comportamento atual se estiver vazio.
- **Enriquecer sem pesar**: opcionalmente buscar até a página 2 das abas principais (`trending/popular`) em idle para aumentar o pool sem impactar a abertura da home.
- **Evitar `discover` aleatório**: não usar parâmetros randômicos na API (a maioria não tem "random" decente e gera requests extras). Melhor unificar o que já foi baixado.
- **Combinar com "Continuar"**: ao abrir via `HomeApps.open()`, salvar o último deeplink aberto em `Prefs` para reaproveitar no drawer "Continuar" (ver proposta relacionada em UX).

**Arquivos relacionados**
- `src/io/freemytv/HomeView.java` (`drawShuffle`, `shuffleOpen`, carregamento de `picks`)
- `src/io/freemytv/HomeApps.java` (`SHUFFLE`, `open`)
- Código responsável por carregar `picks`/recomendados do TMDB

**Critério de aceite**
- Ao pressionar Sorteio, é possível abrir títulos que não estão visíveis nas linhas da home (desde que já tenham sido carregados em alguma aba)
- Sem novas páginas, sem requisições extras a cada clique
- Sem impacto perceptível na velocidade da home
- Funciona com fallback seguro caso pool esteja vazio

## 2) Nuvio na TV: investigar melhorias (limitação ou contornável)

**Problema**
O Nuvio fica muito ruim na TCL mesmo abrindo direto pelo FreeMyTV (apenas com deeplink `nuvio://`). A dúvida é: é uma limitação da TV que não tem como ajudar, ou tem como melhorar?

**Análise (baseada na pesquisa)**
O FreeMyTV **só abre** o app com `Intent` + deeplink `nuvio://meta?...`. Ele não carrega player, não decodifica vídeo, não renderiza nada do Nuvio. Logo, grande parte do desempenho está fora do launcher.

**Principais causas**
| Causa | Explicação | Dá pra ajudar? |
| --- | --- | --- |
| **SoC fraco** | Muitas TCL baratas usam chips com pouco poder pra HEVC/AV1 + UI pesada. | Não. FreeMyTV não consegue acelerar o decoder do outro app. |
| **Nuvio com UI pesada** | Versão da Play Store às vezes é mais "mobile-like" e consome mais RAM/GPU na TV. | **Parcialmente.** Trocar pela build TV do GitHub. |
| **Memória baixa** | Se houver vários apps em background, Android pode matar/limitar o processo do Nuvio. | **Parcialmente.** Evitar apps pesados em background (o FreeMyTV já é enxuto). |
| **Rede** | WiFi fraco/2.4GHz lotado gera buffering mesmo com bom player. | Não diretamente pelo launcher. |
| **ExoPlayer/decoders** | Alguns firmwares forçam decoder software ao invés de hardware pra certos codecs. | Raramente contornável sem root; via ADB não tem muito o que fazer. |

**Ações práticas a testar**
1. **Trocar build do Nuvio** - Instalar a versão TV do GitHub (`com.nuvio.tv`) por cima (`adb install -r`). Essa costuma ser melhor em TCL. O app já detecta ambos (`HomeApps.nuvioPackage` busca `com.nuvio.app` e `com.nuvio.tv`).
2. **Limpar cache** - `adb shell pm clear com.nuvio.app` ou `com.nuvio.tv` (pode deslogar).
3. **Testar Ethernet** - Se a TV tiver porta Ethernet, testar com cabo (diferença gritante em muitas unidades).
4. **TV limpa** - Fechar/evitar apps pesados em background antes de testar.
5. **Verificar logs** - Observar comportamento com `adb logcat` enquanto testa pra entender se é queda de frames/UI ou buffering.

**Observação**
Se mesmo com build `.tv`, boa conexão e TV "limpa" ainda travar, é provavelmente **limitação de hardware/firmware** e não tem como o FreeMyTV contornar. O launcher já é absurdamente ágil por não embedar nada; o gargalo é o próprio player.

**Arquivos relacionados**
- `src/io/freemytv/HomeApps.java` (detecção dos pacotes Nuvio: `nuvioPackage`)
- `tools/install-opentv.sh` (referência de instalação por ADB)

## 3) UX sem bloat (ideias rápidas e seguras)

> **Status (27/10):** "Continuar" no drawer e restauração do último foco feitos e **testados na TV**. O foco é guardado
> pela chave do título (`movie:123`) e restaurado **ao voltar de um app** (HOME continua indo para a fileira de apps, como
> antes); a lista usada é a dos cards, não o `picks` (ver item 6). "Continuar" só aparece com o Nuvio instalado, e o
> deeplink só é gravado quando o título abre de verdade. "Continuar" também entrou no painel Sorteio (três opções: Filme, Série e Continuar, esta só quando há um
> último título; testado na TV). Fica de fora só "app mais recente primeiro na Shelf": mexe numa ordem que a pessoa
> escolheu de propósito, então é decisão de produto, não de código.

Baseado na pesquisa dos agentes (foco em "continuar assistindo" e atalhos reais, sem páginas novas):

| Ideia | Arquivo(s) | Explicação | Risco |
| --- | --- | --- | --- |
| **"Continuar" no drawer inferior** | `HomeApps.drawerShortcuts()`, `HomeApps.open()`, `Prefs` | Salvar último deeplink aberto pelo Nuvio em `Prefs` e exibir atalho "Continuar" no drawer. Se não houver, fica oculto. Reaproveita drawer já animado. | Baixo |
| **Restaurar último foco** | `HomeView.reset()`, `HomeView.resume()` | Guardar `pickIndex` (ou chave do último pick aberto) e restaurar ao voltar pra home. Hoje volta sempre para o primeiro card. | Baixo |
| **"Continuar" no painel Sorteio** | `HomeView.drawShuffle()`, `HomeView.shuffleOpen()` | Adicionar 3º botão (Filme/Série/Continuar) usando o último deeplink salvo. Reaproveita painel existente. | Baixo |
| **App mais recente em primeiro na Shelf** | `HomeApps.shelfPackages()`, `Prefs.shelf()` | Ao abrir app da shelf, mover para o início da lista customizada e salvar. Melhora atalhos com controle remoto sem telas novas. | Baixo |

**Recomendação:** iniciar por **"Continuar" no drawer** + **restaurar último foco** (maior impacto com menor alteração).

## 4) Performance (manter 60fps sem animações pesadas)

> **Status (27/10, medido na TV):** o véu do drawer virou camada própria (retângulo desenhado uma vez; por frame só
> muda a opacidade) e o céu não é mais redesenhado a cada frame do drawer: drawer p50 **34 → 28 ms** (p90 42 → 34).
>
> **O teto deste painel (TCL G13, 1080p60):** com as quatro camadas do home escondidas o frame continua em ~24 ms, com
> elas paradas em ~26 ms, e a animação mais leve de todas (foco na fileira) fica em ~18 ms. Ou seja, a janela custa
> ~18–20 ms por frame animado **independentemente do que o app desenha**: 60 fps não é alcançável nesta TV. A ideia de
> "animar um retrato único" foi descartada depois de medida — esconder quatro camadas economiza só 2–4 ms.
>
> **Decisão:** em vez de perseguir fps, encurtar a animação. O drawer passou de `response` 0,46 s para 0,36 s (mesma
> curva, damping 0,9): ~20% menos tempo por transição (37 → 30 frames), mesma aparência. E o véu agora fica `INVISIBLE`
> com o drawer fechado, em vez de compor uma camada de tela cheia com opacidade 0 em todo frame de toda animação.

Melhorias concretas e de baixo risco para GPUs fracas (Mi Box 3/Mali):

| Mudança | Arquivo | Impacto | Risco |
| --- | --- | --- | --- |
| **Invalidações direcionadas** | `HomeView.java` | Trocar `invalidateAll()` pelos flags já existentes (`tilesDirty`, `statusDirty`, `heroDirty`, `drawerDirty`, `gridDirty`) evitando redraw da tela inteira. | Baixo |
| **Limpar `crispLogos` só quando necessário** | `HomeView.java` | Só chamar `clear()` em `layoutShelf()` quando lista mudar de tamanho/conteúdo. Hoje limpa toda vez. | Baixo |
| **`clipPath` -> `clipRoundRect/clipRect`** | `HomeView.java` | Mais barato em GPUs fracas. Verificar usos e trocar onde for trivial. | Baixo |
| **LRU no cache de sombras** | `Shadows.java` | Trocar `clear()` inteiro por `LinkedHashMap` com `accessOrder=true`. Quando > 64 itens, remover mais antigo ao invés de zerar tudo. | Baixo |
| **Reutilizar `Paint` em `mask()`** | `Shadows.java` | Evitar `new Paint()` a cada cache miss. `BlurMaskFilter` continua por `(r,b)`. | Baixo |
| **Cachear `SimpleDateFormat`** | `Ui.java` | Usar estático/`ThreadLocal` para `clock()` e `date()`. Hoje criam novo a a cada chamada. | Baixo |
| **Checar `saveLayerAlpha`** | `Recording.java` | Só em Android 9, é caro. Verificar se há `alpha < 1` e evitar se não for necessário. | Muito baixo |
| **Evitar alocações em `onDraw`** | `HomeView.java`, `Shadows.java` | Manter alocações fora de `onDraw/draw`. Já está majoritariamente correto. | Baixo |

## 5) Estabilidade/Compat TCL (Watchdog, Remote Keys, Wi-Fi)

| Ponto | Status | Observação / Correção mínima |
| --- | --- | --- |
| **Proteção Wi-Fi (`wifiOwners`)** | Verificado na TV (27/10) | Nesta TCL/Android 14 o `dumpsys wifi` traz `cname=android.uid.system:1000`, um **uid**, não um pacote — a checagem por pacote não casa, e não precisa: a rede é do sistema, não de um app que o switch desliga. O log agora mostra `wifi owners: [...]` para diagnosticar em outra TV, onde o `cname` pode ser pacote. | Baixo |
| **DEBLOAT/PROTECTED** | Correto | Bem alinhado com README. Manter padrão "adicionar por marca" ao adaptar para outra TV. |
| **Appops TCL (AUTO_START)** | Externo ao app | Se não feito via `adb shell` na instalação, `TclAppBoot` pode bloquear `ModeJob`. Continuar como instrução de instalação. |
| **Botões remotos TCL** | Funciona bem | Aprendizado por acessibilidade + broadcast dinâmico (`com.tcl.TCLkeyevent`, `tcl.sys.intent.action.USERSTATE_KEYDOWN`) cobre códigos variáveis. Serviço precisa estar habilitado. |
| **Reativação pós-update** | Verificado na TV (27/10) | Não precisa de mudança: o `HomeReceiver` já chama `ensureRemoteService(3 s)` em `MY_PACKAGE_REPLACED`. Teste real: apaguei o serviço de `enabled_accessibility_services`, reinstalei o APK e ele voltou sozinho (com os outros serviços preservados). | Baixo |
| **Watchdog (3 crashes/5 min)** | Funciona bem | Zera contagem ao entrar em Free My TV, agenda revert pro Original. Sincronizar `record/recent/clear` com lock estático (race conditions raras). Garantir `Watchdog.install()` no processo `:home`. | Baixo |
| **Volta pro Original** | Confiável | `applyOriginal` restaura `originalHome/originalDream`, religar apps, remove screensaver, desativa remote service. Depende de ADB e estado gravado. Checagens null-safe em `restoreHome/packageOf`. | Baixo |

## 6) Achados no teste na TV (27/10, TCL G13_2K_GB, Android 14)

- **`picks` morto no `HomeView`:** **resolvido (27/10) — removido.** O design claro substituiu o herói pela fileira de
  cards, mas sobraram `Hero`, `picks`, `pickIndex`, `heroFocus`, `Z_HERO`, `refreshHero`/`makeHero`/`browse`,
  `tickHeroFade` e o relógio do capsule (~126 linhas). Nada era alcançável: `currentPick()` devolvia sempre `null` e
  `hero.button` era sempre falso. Sem mudança visual; os fluxos (foco nos cards, drawer + Continuar, Todos os Apps,
  abas/Sorteio, voltar do app no card certo) foram testados na TV depois da remoção.
- **`Prefs.put` incrementava `version`, e o `HomeActivity` recria a home** quando ela muda no `onResume`. Gravar qualquer
  estado de tela em `Prefs` custava um `recreate()` inteiro ao voltar. Corrigido para `lastDeeplink`/`lastPickKey` com
  `putQuiet` (escreve sem sujar a versão).
- **O Nuvio ignora deeplink com o app já aberto:** com `com.nuvio.app` mostrando um título, abrir outro (pelo app ou por
  `am start`) só traz o Nuvio para frente; ele não troca de título (`onNewIntent` chega, o app não navega). Com o app
  fechado o link abre certo — os dois builds precisariam resolver isso (item 2).
- **Para chegar nos botões do canto a aba mudava:** corrigido. A prévia ao passar pelas abas continua, mas a aba
  selecionada ao entrar na fileira é restaurada quando o foco sai para os botões, e o OK numa aba passa a ser a que fica.
