# Minispot

Минималистичный Android-пульт для Spotify. Музыку играет официальное приложение Spotify
(через App Remote SDK), а Minispot даёт быстрые экраны: Play / Play shuffled, Search,
Recents, Made for you (daylist) и Last releases.

## Настройка (один раз)

1. Нужен Spotify Premium.
2. На https://developer.spotify.com/dashboard создай приложение и отметь **Web API** и **Android**.
3. В настройках приложения укажи:
   - Redirect URI: `minispot://callback`
   - Android package: `app.minispot`
   - SHA1 fingerprint: `EC:61:11:EF:5E:E8:2F:5C:4E:7B:BB:84:29:B8:37:5C:BC:EC:8A:AB`
4. В **User Management** добавь свой email Spotify (в режиме разработки до 5 пользователей).
5. Установи APK (последняя сборка лежит в Releases → `latest`), вставь Client ID и войди.

## Как это устроено

- `player/RemotePlayer.kt` управляет приложением Spotify: play, пауза, перемотка, лайк, daylist.
- `api/SpotifyApi.kt` ходит в Web API за поиском, историей, подписками и дискографией.
- `auth/SpotifyAuth.kt` вход по Authorization Code + PKCE, без сервера и client secret.

## Ограничения Spotify (2026)

- Поиск отдаёт максимум 10 результатов за запрос, дальше подгружается при прокрутке.
- История прослушиваний в API ограничена последними 50 треками, поэтому Recents
  копит историю локально: чем чаще открываешь приложение, тем полнее неделя.
- Эндпоинт новинок удалён, поэтому Last releases обходит всех исполнителей, на которых ты
  подписан, и кэширует результат на 6 часов.
- Daylist ищется через контент приложения Spotify. Если не нашёлся, можно вставить ссылку
  на daylist в настройках.
- Вход нужно повторять раз в полгода.

## Сборка

GitHub Actions собирает APK на каждый push в `main` (`.github/workflows/build.yml`).
Локально: `./gradlew assembleDebug`. Все сборки подписаны ключом из `app/keystore`,
чтобы SHA1 в настройках Spotify не менялся.
