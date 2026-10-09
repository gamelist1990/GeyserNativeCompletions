# GeyserNativeCompletions

Paperコマンドの補完候補を、Bedrock標準の引数補完UIに表示するプラグイン。
リソースパック、フォーム、独自チャット画面は使いません。

## 導入

1. Java 25、Paper 26.2、Geyser-Spigot 2.11.3 を用意します。
2. `GeyserNativeCompletions-0.1.0.jar` を **Paperの `plugins/`** に入れます。
3. Geyserの `gameplay.command-suggestions` を `true` にして再起動します。
4. Bedrockで接続し、普段のコマンド入力画面を開きます。

PaperとGeyser-Spigotが同じサーバー上にある構成に対応します。
Geyserの `extensions/` に入れる単独拡張ではありません。Paperの既存補完処理を直接呼ぶため、一体型のPaperプラグインにしています。

## 動作

- Geyserが配信したコマンド一覧から、引数情報がない／自由文字列だけのコマンドを自動検出。
- 実際のプレイヤーで既存の `Command.tabComplete()` を先読み。コマンドの実行処理は呼びません。
- 候補をたどり、`/home delete <ホーム名>` のような分岐をBedrockのOverloadに変換。
- 詳細なコマンド定義では、候補のない `STRING` 引数を検出し、PaperのBrigadierから本人の権限でJavaの補完候補を取得。例えば `/damage` の `damageType` を動的SoftEnumに変換します。
- 数値・座標・対象・固定分岐などの元の引数構造を保持し、候補を取得できた文字列引数だけにSoftEnumを設定。候補が空の引数は元のままです。
- `/gamemode`・`/defaultgamemode` には survival・creative・adventure・spectator の候補を設定。
- 候補だけの変更は `UpdateSoftEnumPacket` のREPLACE、分岐構造の変更は `AvailableCommandsPacket` で更新。
- 元の定義・説明・別名・権限情報を保持。任意の追加引数を受け付けるフォールバックも追加。
- 候補は接続ごとに保持し、プレイヤー固有の候補を共通配信しません。
- Bukkitの補完処理はメインスレッド、パケット処理はNettyのイベントループで実行。
- 探索の回数・深さ・候補数・配信量に上限を設けます。
- 配信量の上限では汎用プラグインの補完を優先し、型付きコマンドは小さい候補リストから配分。多数のOverloadを持つコマンドが他のコマンドの候補を先に使い切ることを防ぎます。
- Bedrock PC 26.52で引数UI全体を壊す配信定義 `help` を初回パケットから除外。クライアントの `/help` と、元の定義にある名前付きのJava helpコマンドは利用できます。
- 内容の異なる固定Enumが同じ内部名を使う場合は、名前を一意にします。
- 同じ引数位置で同じ候補を持つSoftEnumは共有します。同じ前方引数を持つOverloadからの重複候補と、親の候補に含まれる固定分岐の二重表示を抑制し、後続の引数は保持します。

入力中の文字列は標準Bedrockクライアントから送られないため、先読みした候補を端末側で絞り込む方式です。
任意の入力に対する `onTabComplete` の完全再現ではありません。
型付きコマンドでは既存Overloadの小さな固定分岐をたどり、前の引数に代表値（対象 `@s`、数値 `1`、座標 `~ ~ ~`、取得した候補の先頭など）を入れて問い合わせます。任意の入力値に依存する候補は、その全組み合わせを再現しません。
非同期の候補はメインスレッドを待機させず、最大2秒まで後続tickで確認します。

## コマンド

管理権限 `gnc.admin`（既定OP）が必要です。

| コマンド | 動作 |
|---|---|
| `/gnc status` | 接続数、補完コマンド数、候補リスト数を表示 |
| `/gnc refresh` | 候補の先読みをやり直す |
| `/gnc reload` | 設定を再読み込みして候補を更新 |

## 設定

`plugins/GeyserNativeCompletions/config.yml` に生成します。

- `refresh-ticks: 100`：探索完了後5秒で再探索。探索時間も加わります。
- `queries-per-tick: 8` / `budget-millis-per-tick: 2.0`：サーバー全体の補完呼び出し上限。
- `slow-query-millis: 10.0`：1回の補完が遅い場合、そのプレイヤーの対象コマンドを60秒休止。
- `max-depth: 3`：Bukkitの汎用補完を第3引数まで探索。既存の型付きOverloadの探索は引数位置を直接使用。
- `max-nodes-per-command: 32`：汎用コマンドの探索コンテキスト数、型付きコマンドの対象Overload数・Java補完の問い合わせ数の上限。
- `max-candidates-per-node: 64`：引数位置ごとの候補数。
- `max-total-nodes-per-player: 256` / `max-total-candidates-per-player: 4096`：接続ごとの配信量上限。
- `include-commands: []`：空なら自動検出。指定するとそのコマンドだけ処理。
- `exclude-commands`：自動処理から除外するコマンド。

空入力では候補を返さないプラグインには、必要な文字だけ `prefix-probes` に指定できます。
通常は空のままにします。接頭辞探索にも回数・候補数の上限が適用されます。

```yaml
prefix-probes: [a, b, c]
extra-contexts:
  example:
    - delete
    - 'admin remove'
```

`extra-contexts` は、候補から発見できない分岐の補完を問い合わせる設定です。
必須／任意、数値範囲、引数の意味はBukkitの補完結果だけでは判定できません。
元のBrigadier定義にある型付き引数はそのまま保持します。
文字列引数の候補はその位置へ追加します。

## 対応範囲

- 有限の候補を、空入力や既知のサブコマンドから取得できるBukkit／BasicCommandが主な対象です。
- 数字や自由文に依存する無限の分岐、候補内の空白・引用符、TABイベントだけで提供する補完は自動変換できません。
- 既に詳細なBrigadier引数定義を持つコマンドは、候補のない文字列引数を補強します。元の対象・数値型や固定Enumは上書きしません。
- `@e[...]` 内のキーの補完はBedrockクライアントのセレクター文法が担当します。このパケット方式では `distance`・`limit`・`sort` などのJava文法へ差し替えられません。セレクター全体をSoftEnumにした比較版は引用符を付けて挿入されるため、製品版には入れていません。
- 同じ候補をどの引数位置にも返す処理は、無限に展開しないよう探索を止めます。
- 外部プラグインの補完関数を途中停止できません。遅い関数は呼び出し後に休止します。
- 分割画面サブクライアント、Folia、Geyser Standalone／Velocity構成は対象外です。
- Geyser内部セッションとCloudburst送信経路を使うため、Geyser更新時は接続テストが必要です。

## ビルド

Java 25で実行します。Gradle Wrapper同梱。

```sh
./gradlew clean build
```

Windowsでは `gradlew.bat clean build`。
成果物は `build/libs/GeyserNativeCompletions-0.1.0.jar`。
Geyser・Paper・Cloudburst・Netty・Brigadierは同梱しません。

`./gradlew fixtureJar` で検証専用の `NativeCompletionFixture` を作れます。
通常サーバーへの導入は不要です。

詳細な検証結果は `VERIFICATION.md`。
bedrock-protocol による接続テストの再実行手順は `tests/bedrock/README.md`。
Bedrock PC 26.52のOP実機で通常一覧の引数表示、`/gamemode` と `/damage` の候補・Tab選択を確認済みです。他のバージョンや端末では別途確認が必要です。

## ライセンス

MIT。依存ライブラリとMinecraftのライセンスは各配布元に従います。
