# 検証状況

2026-10-09 時点のソースです。

- Java 25 / Gradle 9.2.1 でコンパイルとJAR生成に成功。
- JUnitの自動テスト10件成功（失敗・エラー0）。
- 候補探索の上限、プレイヤー固有候補の分離、候補の無害化、自由入力用フォールバック、SoftEnum更新、Cloudburstのシリアライズ、送信経路の処理を検証。
- Paper 26.2 build 130 / Geyser-Spigot 2.11.3-b1249 のテストサーバーでプラグインの起動を確認。
- bedrock-protocol 3.60.1 / Node.js 24.21.0 の offline 接続による実際のパケット受信テスト10項目成功。Bedrock 1.26.51（プロトコル 2193）で2クライアントを接続。
- `/ncf` の `delete / set / list` とプレイヤー固有ホーム名を AvailableCommands の SoftEnum として受信。
- 候補の追加・削除は UpdateSoftEnum の REPLACE（bedrock-protocol では `action_type: update`）で反映。候補だけの追加では AvailableCommands を再送しないことを確認。
- 候補が空になった分岐の削除と、再追加による分岐復元では AvailableCommands を受信。
- 2クライアント間でホーム名・変更内容が混ざらないこと、元の説明・権限・自由入力フォールバックと固定引数＋SoftEnumの分岐を確認。
- Bedrock実機の補完画面・TABキー動作は未確認です。

## 実機確認で見つかった未解決事項

- `/ncf` の候補・引数表示を実機で確認。ただし `delete` が、ルートの候補と固定引数の分岐から重複表示されます。
- OPの実機で `/gamemode ` の説明行と候補が出ないとの報告。OP接続のパケット比較では、プラグイン有効／無効とも `gamemode`（文字列）と任意の `target` が同じ定義で配信されます。元の定義にはゲームモードの列挙候補がありません。
- 比較用のプラグイン無効サーバーでも、OPで `/gamemode ` の説明行・候補が出ないことを実機で確認。GeyserNativeCompletions の有無では変わりません。原因は未確定。パケット受信テストの成功だけでは実機の表示正常性を保証できません。

## 方式と制約

既存のPaper補完関数の結果を先読みして、BedrockのAvailableCommandsPacketとUpdateSoftEnumPacketに反映します。入力途中の文字列を受信して計算する方式ではありません。

Geyser内部APIに依存するため、異なるGeyserバージョンとの互換性は保証しません。導入先はGeyser-Spigotと同居するPaperのpluginsディレクトリです。

再実行手順は `tests/bedrock/README.md`、テスト本体は `tests/bedrock/integration.cjs`。受信パケットは `tests/bedrock/results/latest.json` に保存します。

今回のローカルサーバーは `.integration/server` に作成。Paper と Geyser はともに127.0.0.1に限定し、Java offline認証、Bedrockログイン検証無効で実行しました。オンライン認証や公開サーバー構成は未検証です。前回の認証メタデータ取得タイムアウトは今回再現せず、Geyserを変更せず接続できました。

テスト用サーバー・認証設定・外部クライアント依存物は配布対象外。fixtureソースは検証用で、製品JARには含まれません。
