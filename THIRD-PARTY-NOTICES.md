# 使っているライブラリとライセンス

ShelfTally そのものは MIT(`LICENSE`)です。ビルドのときに次のライブラリを取り込みます。版は `gradle/libs.versions.toml` にあります。

## アプリに入る物

| 名前 | 版 | ライセンス | 使う所 |
|---|---|---|---|
| Kotlin 標準ライブラリ(org.jetbrains.kotlin) | 2.0.21 | Apache-2.0 | 全体 |
| kotlinx.coroutines | 1.9.0 | Apache-2.0 | 非同期の処理 |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 | 通知、FileProvider |
| AndroidX Lifecycle | 2.8.6 | Apache-2.0 | ViewModel |
| AndroidX Activity Compose | 1.9.2 | Apache-2.0 | 画面、ファイルや写真の選択 |
| Jetpack Compose(BOM 2024.10.00: ui / material-icons-extended) | 2024.10.00 | Apache-2.0 | 画面 |
| Compose Material 3(androidx.compose.material3:material3) | 1.5.0-alpha12 | Apache-2.0 | 画面(Material 3 Expressive の部品)。これに引かれて Compose の ui・foundation は 1.8.2、runtime は 1.9.0-rc01 になる |
| AndroidX Navigation Compose | 2.8.2 | Apache-2.0 | 画面の行き来 |
| AndroidX Room | 2.6.1 | Apache-2.0 | データの保存 |
| AndroidX Glance(appwidget / material3) | 1.1.0 | Apache-2.0 | ホーム画面のウィジェット |
| AndroidX CameraX(camera-core / camera-camera2 / camera-lifecycle / camera-view) | 1.5.3 | Apache-2.0 | バーコードの読み取りのカメラ |
| ZXing core(com.google.zxing:core) | 3.5.4 | Apache-2.0 | バーコードの読み取り |
| Zip4j(net.lingala.zip4j:zip4j) | 2.11.5 | Apache-2.0 | バックアップの zip と暗号化 |

これらが依存する AndroidX のライブラリ(WorkManager など)も一緒に入ります。どれも Apache-2.0 です。

## テストのときだけ使う物

| 名前 | 版 | ライセンス |
|---|---|---|
| JUnit 4 | 4.13.2 | EPL-1.0 |
| kotlin-test | 2.0.21 | Apache-2.0 |
| Robolectric(org.robolectric:robolectric) | 4.14.1 | MIT |
| Roborazzi(io.github.takahirom.roborazzi:roborazzi / roborazzi-compose) | 1.32.2 | Apache-2.0 |
| Compose UI Test(androidx.compose.ui:ui-test-junit4) | BOM 2024.10.00 | Apache-2.0 |

Robolectric と Roborazzi は、画面を PC の上で画像にして見た目を確かめるために使います(`gradlew testDebugUnitTest -Pshots` のときだけ画像を書く)。

## 要件定義書から変えた所: ML Kit を使わない

要件定義書は、バーコードの読み取りに Google ML Kit Barcode Scanning を挙げていますが、このアプリは使っていません。ML Kit は Google の独自の規約(ML Kit Terms of Service)の下で配られていて、オープンソースのライセンスではないためです。代わりに、Apache-2.0 の ZXing を使っています。読み取りは端末の中だけで行い、通信はしません。

## 写したコード

- `core/src/main/kotlin/.../core/barcode/Barcode.kt` と、そのテストは、同じ作者(takosasi-dev)の DeskSlate(MIT)の probe にあるコードを写し、複数の形式を読めるようにした物です。
- バックアップ・写真の取り込み・アプリロック・ウィジェット・通知の書き方は、同じ作者の収支管理アプリ(win-loss-tracker、MIT)に合わせています。

## 同梱していない物

実在の企業・商品の画像、アイコン、音、フォントは入れていません。アプリのアイコンと通知のアイコンは、自分で描いたベクターです。画面のアイコンは Material Icons(Apache-2.0、Compose の material-icons-extended に含まれる)です。
