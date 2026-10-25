# Propostas de melhoria

## 1) Shuffle: sortear de todo conteúdo TMDB já consumido

**Problema**
Hoje o botão "Sorteio" (`SHUFFLE`) escolhe aleatoriamente apenas dentre os conteúdos que estão visíveis/recomendados na tela. Seria muito mais interessante sortear de todo o conteúdo que já foi consumido pela API do TMDB nesta sessão.

**Ideia (baixo risco)**
- Criar um pool unificado em memória (`ArrayList<Item>` + `HashSet<String>` com chave `type:id`) e ir acumulando todo item que chegar das requisições TMDB em todas as abas.
- Em `shuffleOpen()`, sortear a partir desse pool global ao invés de apenas dos itens em tela.
- Manter fallback para o comportamento atual caso o pool ainda esteja vazio.
- Opcional: buscar até página 2 das abas principais (`trending/popular`) em idle para enriquecer o pool sem pesar a abertura da home.
- Não fazer `discover` com parâmetros aleatórios (melhor unificar o que já foi baixado).

**Arquivos relacionados**
- `src/io/freemytv/HomeView.java` (`drawShuffle`/`shuffleOpen`)
- `src/io/freemytv/HomeApps.java` (`SHUFFLE`)
- Código responsável por carregar `picks`/recomendados do TMDB

**Critério de aceite**
- Ao pressionar Sorteio, é possível abrir títulos que não estão visíveis nas linhas da home (desde que já tenham sido carregados em alguma aba)
- Sem novas páginas, sem requisições extras a cada clique
- Sem impacto perceptível na velocidade da home

## 2) Nuvio na TV: investigar melhorias (limitação ou contornável)

**Problema**
O Nuvio fica muito ruim na TCL mesmo abrindo direto pelo FreeMyTV (apenas com deeplink `nuvio://`). Dúvida: é limitação da TV ou tem como melhorar?

**Análise**
- O FreeMyTV só abre o app com `Intent` + deeplink. Não carrega player, não decodifica vídeo, não renderiza nada do Nuvio.
- Principais causas: SoC fraco (Mali/Mediatek baratos), UI pesada da versão Play Store, memória baixa, rede WiFi 2.4GHz, decoders forçados pelo firmware.

**Ações práticas a testar**
1. **Trocar build**: instalar a versão TV do GitHub (`com.nuvio.tv`) por cima da Play Store (`adb install -r`). Geralmente melhor em TCL.
2. **Limpar cache**: `adb shell pm clear com.nuvio.app` ou `com.nuvio.tv` (pode deslogar).
3. **Testar Ethernet**: se possível, conectar por cabo pra descartar WiFi.
4. **Verificar recursos**: fechar outros apps pesados antes de testar.

**Observação**
Se mesmo com build `.tv`, boa conexão e TV "limpa" ainda travar, provavelmente é limitação do hardware/firmware e não tem como o FreeMyTV contornar. Focar no shuffle geral primeiro (100% código do launcher).

**Arquivos relacionados**
- `src/io/freemytv/HomeApps.java` (detecção dos pacotes Nuvio: `nuvioPackage` busca `com.nuvio.app` e `com.nuvio.tv`)
- `tools/install-opentv.sh` (referência de instalação por ADB)
