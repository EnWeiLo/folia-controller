# Folia 控制器（Android）

把 [Folia](https://github.com/chthollyphile/folia-major) 的全螢幕動態歌詞放到手機或平板上，**本身不播放音樂**。
你照常用 Spotify、YouTube Music 或任何播放器聽歌，這個 App 會：

- 讀取正在播放的歌曲、封面和進度（透過 Android 的媒體工作階段）
- 自動找同步歌詞（中日韓歌曲優先找網易雲，有逐字歌詞；其他先找 LRCLIB）
- 用 Folia 的歌詞動畫全螢幕顯示，跟著播放器同步
- 提供控制列：上一首、播放／暫停、下一首、拖曳進度條

## 建置（不需要 Android Studio）

1. 在 GitHub 建一個新的 repo（可以設為 Private），把這個資料夾的內容全部上傳。
2. 上傳後，GitHub Actions 會自動執行 **Build APK**，約 8–12 分鐘。
3. 完成後到 repo 的 **Releases**，下載 `FoliaController-<編號>.apk`。用手機瀏覽器直接開 Releases 頁面就能下載。

之後只要修改並推送程式碼，就會產生新版。每一版都用同一把簽章，可以直接覆蓋安裝。
想換成最新版的 Folia，可以到 **Actions → Build APK → Run workflow**，把 `folia_ref` 填成 `main`。

## 第一次使用

1. 安裝 APK（需要允許「安裝不明來源的應用程式」）。
2. 打開 App，按「開啟通知使用權設定」，把 **Folia 控制器** 打開。
   - Android 13 以上自行安裝的 App，這個開關可能是灰色的，並顯示「受限制的設定」。這時先按「開啟 App 資訊」，點右上角 **⋮** → **允許受限制的設定**，再回去開啟通知使用權。
   - 小米 HyperOS 的位置是：設定 → 應用程式 → 權限 → 特殊應用程式存取權 → 通知使用權。
3. 在 Spotify 或 YouTube Music 播一首歌，回到 Folia 控制器即可看到歌詞。

這個 App 只用通知使用權來讀取媒體工作階段，不會讀取或保存任何通知內容。

## 操作

- **點畫面任一處**：顯示或隱藏控制列（4 秒後自動隱藏）
- **點歌名**或右下角按鈕：切回正在播放的 App
- **左下角齒輪**：設定
  - 歌詞動畫：流光、心象、雲階、浮名、莫奈、群唱、傾訴、回環、鏡台、時計、商籟、凝彩、繪光、靜止
  - 日間主題、顯示翻譯、簡體歌詞轉繁體（日文歌詞不會轉）
  - 歌詞時間偏移：戴藍牙耳機時如果歌詞偏早，可以調成 +250 到 +500 ms
  - 保持螢幕常亮

## 運作原理

```
Spotify / YouTube Music ──媒體工作階段──▶ MediaHub（Kotlin）
                                            │ 找歌詞：網易雲 / LRCLIB
                                            ▼
                          WebView 裡的 Folia「Now Playing」頁面
              （folia-bridge.js 把原本的 WebSocket 換成和 App 之間的直接通道）
```

Folia 原本就有一個給 OBS 用的「Now Playing」模式，它只顯示歌詞、不播放音樂，靠 `ws://localhost:9863` 接收
`Track`、`Lyric`、`PlayerPauseState`、`PlayerProgress` 四種事件。這個 App 在 WebView 裡送出相同的事件，
所以 **Folia 的程式碼完全沒有修改**。CI 每次會抓 Folia 原始碼、用 `vite build` 打包，再放進 APK。

| 檔案 | 用途 |
| --- | --- |
| `MediaHub.kt` | 追蹤目前播放的 App、讀取歌曲資訊和進度、送事件給 Folia、處理播放控制 |
| `LyricRepository.kt` | 搜尋歌詞：網易雲（含逐字 YRC 和翻譯）與 LRCLIB；處理繁簡轉換 |
| `FoliaAssetServer.kt` | 在 `https://appassets.androidplatform.net/` 提供 Folia 網頁，並注入橋接腳本和 viewport 設定 |
| `MainActivity.kt` | 全螢幕 WebView、控制列、權限說明畫面、設定 |
| `assets/folia-bridge.js` | 把 Folia 的 Now Playing WebSocket 換成原生橋接 |

## 授權

Folia 以 AGPL-3.0 授權。這個 APK 內含 Folia 的網頁打包檔，自己使用沒有問題；如果要散布給別人，需要依照 AGPL 一併提供原始碼。
音樂與歌詞的版權屬於各權利人。
