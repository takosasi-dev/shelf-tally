plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Android に依存しない。計算・判定・変換のロジックはここに置き、:app は表示と入出力だけを持つ。
// 単体テストは `gradlew :core:test` で走る(`testDebugUnitTest` では走らない)。
dependencies {
    // バーコードの読み取り。ZXing の型は :core の外に出さない(:app は ByteArray を渡して String を受ける)
    implementation(libs.zxing.core)

    testImplementation(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(17)
}
