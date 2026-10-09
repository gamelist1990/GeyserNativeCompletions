# Bedrock 接続テスト

PrismarineJS の bedrock-protocol 3.60.1 を使い、実際の Paper + Geyser-Spigot から受信したパケットを検証します。Node.js 24 以上が必要です。

## サーバーの準備

1. Java 25 でプロジェクト直下の `gradlew.bat build fixtureJar` を実行。
2. 検証専用の Paper 26.2 build 130 サーバーの `plugins` に、生成された製品 JAR、NativeCompletionFixture JAR、Geyser-Spigot 2.11.3-b1249 を配置。
3. Paper の `server.properties` に `server-ip=127.0.0.1`、`server-port=25587`、`online-mode=false` を設定。
4. Geyser の設定を以下に変更して再起動。

```yaml
bedrock:
  address: 127.0.0.1
  port: 19187
java:
  auth-type: offline
gameplay:
  command-suggestions: true
advanced:
  bedrock:
    validate-bedrock-login: false
```

既存の設定に上記の値を反映してください。これはローカルの検証専用設定です。自己署名のテストクライアントを受け入れるため、認証検証を無効にしています。

## 実行

このディレクトリで実行します。

```powershell
npm ci
npm test
```

接続先は環境変数 `BEDROCK_HOST`、`BEDROCK_PORT`、`BEDROCK_VERSION` で指定できます。既定値は `127.0.0.1:19187`、`1.26.51`。

テストは NCTestA / NCTestB の2クライアントを接続し、fixture の `/ncf set`、`/ncf delete` を実行します。検証専用サーバーを使ってください。offline の既定 XUID が両方0になるため、ログイン署名前に異なる検証用 XUID を与えます。
保存済みのテストプレイヤーが死亡している場合は、接続後にrespawnを要求します。テスト開始時にfixtureの実際の候補を読み、自分のテストプロフィールだけを初期化するため再実行できます。敵に倒されないよう検証サーバーをpeacefulに設定してください。

Javaの型付き引数の補完も検証する場合、サーバーコンソールで `op NCTestA` を実行してから、次の設定でテストします。NCTestBは非OPのままにします。

```powershell
$env:BEDROCK_PORT = '19188' # 実機検証サーバーを使用する場合
$env:BEDROCK_NATIVE_ARGUMENTS = '1'
npm test
```

`/gamemode`・`/defaultgamemode` の4候補、`/damage` のJavaのダメージ種別と既存の型、fixtureの型付きコマンド `/ncf_native` の候補の分離・動的更新を追加検証します。共有SoftEnumと重複する候補の表示抑制も検証。全20項目。

初回の候補・分岐・フォールバック・説明・権限情報、候補追加と削除の SoftEnum REPLACE、プレイヤーごとの候補分離、空になった分岐と復元時の AvailableCommands 再送を検証します。失敗時は終了コード1。受信パケットと結果は `results/latest.json` に保存します。
全AvailableCommandsでサーバー定義 `help` の混入も検査します。
他のコマンドの共有Enumが同時に分割・統合された場合は、候補の変更もAvailableCommandsに含まれます。テストでは両方の更新経路で候補が正しく届くことを確認し、fixture候補のSoftEnum REPLACEも別途受信検証します。
通常の全コマンドをOPで探索する場合、探索完了までの時間が更新間隔へ加算されます。同時接続している実機も探索予算を共有するため、動的更新の受信は最大60秒待って検証します。

Bedrock 実機の画面表示や TAB キー操作は、このパケットテストの対象外です。
