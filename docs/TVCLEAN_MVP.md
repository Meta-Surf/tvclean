# TVClean — Plano técnico do MVP Oferta no Radar

Status: implementação inicial (não distribuir APK com ofertas reais ainda).

## Origem do projeto
Código base: https://github.com/simbasjungles/free-my-tv (MIT).
Manter `LICENSE` e créditos; este repositório é uma versão independente, **não** um lançamento oficial do Free My TV.
Os workflows do upstream não foram importados; CI, releases e assinatura serão próprios.

## Escopo do MVP
- Android TV / Google TV primeiro. LG webOS e Samsung Tizen são etapa posterior.
- Preservar apps, alternância para home original, watchdog, recomendações e navegação do controle remoto.
- No **Explore**, substituir visualmente a terceira recomendação por **Publicidade · Oferta no Radar**.
- Filmes e Séries continuam sem promoções.
- Rotacionar a cada 600 segundos, somente quando a oferta não tem foco e o diálogo de QR Code não está aberto.
- Pressionar OK no card abre modal com produto, preço verificado, condição, aviso de afiliado e QR Code.
- O QR Code aponta para URL HTTPS de redirecionamento controlado pela API e com vínculo rastreável de campanha.
- Se a API falhar, nunca divulgar preço/link vencido. Exibir placeholder discreto ou ocultar o card.
- Cache local com expiração explícita; feed nunca deve interferir nas funcionalidades do launcher.

## Contrato do feed HTTP
GET https://midia.ofertanoradar.cloud/tv/api/v1/offers/current
Content-Type: application/json; charset=utf-8
Cache-Control: public, max-age=60

Objeto de resposta versionado:
- `schema_version`: inteiro, inicialmente 1.
- `generated_at`: data/hora UTC ISO 8601.
- `rotation_seconds`: 600 (servidor pode sugerir 300–900).
- `offers`: array de ofertas (0 a 10).
- cada oferta: `id`, `title`, `store`, `price_brl`, `price_verified_at`, `expires_at`,
  `image_url` (HTTPS ou ausente), `click_url` (HTTPS/HTTPS de domínio controlado ou null),
  `disclosure` e `kind` (`demo` ou `affiliate`).
- `click_url` só pode existir quando `kind=affiliate` e o canal TV tiver autorização.
- Nunca publicar tokens, cookies, IDs pessoais, payloads completos de Telegram ou segredos.
- O aplicativo não deve presumir que o feed seja confiável: restringir tamanho, URLs, tempos e texto.

## Fonte dos produtos
O backend será um módulo isolado ligado ao repositório privado
https://github.com/Meta-Surf/Radar-ofertas pela API HTTPS:
- Ler apenas ofertas comprovadamente publicadas (`deliveries` status `SENT`).
- Aproveitar payload do produto persistido, sem ler mensagens privadas dos grupos.
- Revalidar preço, estoque, imagem, link e prazo pela regra específica da origem.
- Identificar `destination=tvclean`, com campanha e rastreamento distintos de Telegram/Instagram.
- Para marketplace não autorizado, não expor links ou preços comerciais; usar dados demo.
- Registrar métricas agregadas, sem perfilamento invasivo da TV ou seus usuários.

## Segurança e distribuição
- Adotar identificador de pacote Android e chave de assinatura próprios antes de releases.
- Desativar/reescrever verificação de atualização do APK oficial do Free My TV.
- Não ativar qualquer recurso em VPS de produção antes de revisão, testes e aprovação.
- Não incorporar API key TMDB ou tokens de afiliados no APK público.
- Validar licença comercial do TMDB e regras dos programas de afiliados.
- Instalação inicial por APK assinado, com opção de restaurar home original sempre disponível.

## Entregas planejadas
1. Base MIT no novo repositório com histórico (concluído).
2. Branch de feature e contrato do feed (este documento).
3. Módulo Android `RadarOffers` e renderização do terceiro card de Explore.
4. Modal de QR Code com estado de foco estável.
5. Serviço da API e testes no Radar-ofertas (branch isolada).
6. Build CI e testes de smoke em TV/Emulador. LG/Samsung em seguida.

## Critérios mínimos de aceitação
- Sem rede: home funciona normalmente, não exibe oferta inválida.
- 10 minutos de rotação não provocam salto de foco.
- Movies/Shows, apps, atalhos e restauração original continuam utilizáveis.
- QR Code abre somente URL permitida, válida e autorizada.
- APK assinado com chave TVClean e mecanismo de atualização independente.
