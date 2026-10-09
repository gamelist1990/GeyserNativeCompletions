# 検証状況

2026-10-09。Java 25 / Gradle 9.2.1、Paper 26.2 build 130、Geyser-Spigot 2.11.3-b1249で検証。

## 自動検証

- `gradlew.bat build fixtureJar` 成功。JUnit 31件、失敗・エラー0。
- 汎用候補探索の上限・無害化、自由入力フォールバック、固定分岐、プレイヤー固有候補、初回の送信経路、help互換処理、固定Enum名の衝突を検証。
- 実際のBrigadier dispatcherを使い、型付き引数の候補取得、権限、前の引数の解析失敗を検証。非同期の結果待ち、同じ問い合わせの共有、不要な末尾の探索抑制も確認。
- 大きなOverload群が他の補完を配信上限から押し出さない配分と、型付き引数へのコマンド別・接続別の上限を検証。
- Cloudburstのプロトコル2193のシリアライズ・逆シリアライズで、文字列引数のSoftEnum化と数値・対象・必須／任意・説明・権限・別名の保持を確認。
- bedrock-protocol 3.60.1 / Node.js 24.21.0、Bedrock 1.26.51（プロトコル2193）の実接続で20項目成功。NCTestA（OP）とNCTestB（非OP）の2クライアントを使用。
- `/ncf` のdelete・set・list、個別のホーム名、追加・削除時のSoftEnum REPLACE、空の分岐の削除・復元を確認。
- 他の共有Enumが同時に分割・統合された場合はAvailableCommandsによる更新を受け入れ、候補とプレイヤー間の分離が保たれることを検証。SoftEnum REPLACEによる候補更新も実際に受信。
- 初回・更新後の全AvailableCommandsに、互換性のないサーバー定義 `help` が戻らないことを確認。
- `/gamemode`・`/defaultgamemode` のJavaの4候補と、`/damage` のダメージ種別を受信。後者は既存の対象型・数値型を保持。
- 検証用Brigadierコマンド `/ncf_native <amount:int> <choice:string>` の動的候補がSoftEnumへ変換され、候補の追加・削除が更新パケットで届くこと、プレイヤー間で混ざらないことを確認。

テスト本体は `tests/bedrock/integration.cjs`、最新の受信パケットと結果は `tests/bedrock/results/latest.json`。再実行手順は `tests/bedrock/README.md`。

## 実機確認

Windows PCのBedrock 26.52、OPのPEXKoukunで確認。

- 通常の全コマンド一覧で `/give @s minec`・`/gamemode`・`/effect`・`/gamerule`・`/ncf` の引数表示が正常。
- `/gamemode` のsurvival・creative・adventure・spectatorの候補とTab選択が正常。
- JavaのBrigadierから動的に取得した `/damage @s 1 ` のダメージ種別の候補・Tab選択が正常。`/gamemode` と `/ncf` の表示も維持。
- `/damage` と `/ncf` の候補の二重表示が解消し、`/damage @s 1 minecraft:fall by ` などの後続補完も正常。

初期のOPで引数表示が消える問題は、配信定義を同程度の数で比較し、追加する定義を二分して切り分けました。`help` 1定義の追加で再現し、通常一覧から `help` を外すと正常になりました。引数型・別名・定義数だけを変える修正では解決しませんでした。正式版では初回からbare `help` の名前と別名を配信せず、クライアント側の `/help` に任せます。これは当該環境での実機確認に基づく互換処理です。

## 制約

- 入力途中の文字列をクライアントから受信する方式ではありません。Javaの補完を先読みし、Bedrockがローカルで絞り込みます。
- 型付きOverloadの前の引数には代表値を使うため、任意の対象・座標・自由入力によって変わる候補の全再現はできません。候補の空白・引用符、無限の分岐は対象外。
- `@e[...]` のJava引数への補完切り替えは未対応。セレクター全体をSoftEnumにする実機比較では引用符付きの候補になり、通常のJavaセレクターとして使えないため撤回しました。製品版のTARGET型は維持。
- PaperのMojangマッピング、Geyser内部セッション、Cloudburst送信経路を使用。他バージョン、Folia、Standalone／Velocity構成は未検証・対象外。

今回の実機検証サーバーは `.integration/op-server`。Javaは127.0.0.1:25588、BedrockはLANの192.168.1.5:19188。検証用offline認証とBedrockログイン検証無効、difficulty=peacefulを使用。元の比較環境 `.integration/server` は別ポートに保持しています。

テストサーバー・認証設定・クライアント依存物・fixtureは配布対象外。製品JARにfixtureや依存ライブラリは同梱しません。
