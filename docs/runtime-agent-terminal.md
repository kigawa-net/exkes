# runtime-agent Terminal 仕様（PTY over WebSocket）

runtime-agent（Runtime Pod 内のエージェント）が、WebSocket 上で対話シェルを PTY として起動・中継する機能の仕様。
上位の terminal-gateway / `terminal_sessions` / API はこの仕様の利用側であり、本仕様の範囲外。

- 実装: `runtime-agent/src/main/kotlin/net/kigawa/exkes/runtimeagent/terminal/` と `routes/TerminalRoutes.kt`
- 前提: plan 第3章のとおり、runtime-agent は `exkes-common` に依存せず、K8s にも依存しない。通信は JSON over HTTP/WebSocket のみ。
- **認証は行わない。** agent は受信のみで、認証・認可・セッション検証は terminal-gateway の責務。agent は到達可能なクライアントをすべて信頼する前提で動くため、NetworkPolicy で gateway からの接続だけを許可すること。

## エンドポイント

`GET /terminal`（WebSocket アップグレード）

クエリパラメータはすべて任意。

| パラメータ | 既定値 | 制約 |
|---|---|---|
| `cols` | 80 | 整数 1〜1000 |
| `rows` | 24 | 整数 1〜1000 |
| `cwd` | agent の `agent.workingDir`（未設定なら agent の作業ディレクトリ） | 絶対パスで、既存のディレクトリであること |
| `shell` | `agent.terminal.defaultShell`（既定 `/bin/sh`） | 絶対パスで、実行可能な通常ファイルであること |

例: `ws://<runtime>:8080/terminal?cols=120&rows=40&cwd=/workspace`

要求が不正な場合は、WebSocket を張った直後に `error` を送信し、close code `1008`（Policy Violation）で切断する。
同時セッション数の上限に達している場合は `error` の後に close code `1013`（Try Again Later）で切断する。
シェルの起動に失敗した場合は、内部詳細を返さず `failed to start shell` を送って close code `1011` で切断する（詳細はサーバログ）。

## メッセージ仕様

すべてのフレームは **テキストフレーム（UTF-8）1 つ＝JSON オブジェクト 1 つ**。`type` フィールドで種別を判別する。
未知のフィールドは無視する。バイナリフレームは受け付けない（`error` を返し、セッションは継続する）。

### client → agent

| type | フィールド | 動作 |
|---|---|---|
| `input` | `data`: string | `data` を UTF-8 バイト列として PTY に書き込む（キー入力・貼り付け）。 |
| `resize` | `cols`: int, `rows`: int | PTY のウィンドウサイズを変更する（`TIOCSWINSZ` 相当）。範囲外なら `error`。 |
| `close` | なし | セッションを終了する。シェルとそのプロセスグループを kill し、WebSocket を閉じる。 |

```json
{"type":"input","data":"ls -la\n"}
{"type":"resize","cols":120,"rows":40}
{"type":"close"}
```

### agent → client

| type | フィールド | 送信タイミング |
|---|---|---|
| `output` | `data`: string | PTY からの出力。読み取り単位（最大 8 KiB）ごとに送る。 |
| `exit` | `code`: int | シェルが終了したとき。1 セッションにつき 1 回。直後に WebSocket を close code `1000` で閉じる。 |
| `error` | `message`: string | 要求の不正、不正なメッセージ、上限到達、アイドル・TTL 超過、シェル起動失敗など。 |

```json
{"type":"output","data":"total 8\r\n"}
{"type":"exit","code":0}
{"type":"error","message":"terminal session was idle for too long"}
```

`error` が送られた後、セッションが継続する場合（不正な 1 メッセージなど）と終了する場合（上限・アイドル・TTL・起動失敗）がある。
終了する場合は、続けて `exit`（シェルが kill された場合は kill 後の終了コード）が届くか、WebSocket が閉じられる。

### 不正メッセージ

- JSON として壊れている、`type` が未知、フィールドが不正（例: `resize` の `cols` が 0）の場合は `error`（`invalid message` / 範囲メッセージ）を返す。セッションは継続する。
- 不正メッセージの内容はログに出さない。

## 出力のエンコーディング（バイナリ出力の扱い）

- PTY の出力はバイト列であり、`output.data` は **UTF-8 文字列** として送る。
- 読み取り境界で 1 文字が分割されても文字化けしないよう、末尾の未完結バイト列を次の読み取りに持ち越す（`Utf8StreamDecoder`）。
- UTF-8 として不正なバイトは U+FFFD に置換する。**バイナリを厳密に運ぶ用途には向かない（非可逆）。** 対話端末（xterm.js 等）は文字列を前提とするため、この扱いを採用した。
- 厳密なバイト列が必要になった場合は、`output` に `encoding: "base64"` を追加する拡張で対応する（現時点では未実装）。
- `input` も UTF-8 文字列。クライアントは任意のバイト列を送れない（同じ理由で非対応）。

## ライフサイクル

1 セッション = 1 シェル（PTY）。次の経路のいずれかで必ず終了する。

1. シェルが終了（`exit` など）→ プロセスグループと残存プロセスを kill → 残った出力を送信 → `exit` → WebSocket を閉じる。
2. クライアントが `close` を送る、または WebSocket が切断される → シェルとプロセスグループを kill。
3. アイドル超過（`agent.terminal.idleTimeoutSeconds`）→ `error`（`idle`）→ kill → `exit`。
4. 最大寿命超過（`agent.terminal.maxTtlSeconds`）→ `error`（`maximum lifetime`）→ kill → `exit`。
5. agent 停止（SIGTERM / ApplicationStopping）→ 全セッションを kill（既存の Execution の shutdown と同様）。

### 子プロセスの kill

- PTY の子は `setsid()` によりセッションリーダとなるため、pid = プロセスグループ ID。`kill(-pid, SIGKILL)` でシェルとそのジョブ全体を kill する（JNA で libc の `kill` を呼ぶ）。
- プロセスグループを抜けた子（`setsid` 済みのデーモン等）は、セッション固有のトークン（環境変数 `EXKES_EXECUTION_TOKEN`、セッションごとの乱数）を `/proc/<pid>/environ` で走査して回収する。
- kill 後はシェルを `waitFor()` で必ず回収する（ゾンビを残さない）。
- シェル終了後に PTY の出力が閉じない場合（残存プロセスが PTY を掴み続ける場合）、出力の排出を最大 3 秒待ち、超えた分は切り捨てて `exit` を送る（スロットは解放される）。

### 同時数上限

- `agent.terminal.maxSessions`（既定 4）。セマフォで、シェル起動の**前**に枠を確保し、セッション終了時に解放する。
- 上限到達時は起動せず `error` + close `1013`。

### ブロッキング IO

- PTY の起動・読み書き・`waitFor`・kill は `Dispatchers.IO` で実行する。Netty のイベントループはブロックしない。
- WebSocket の切断時の後始末も `Dispatchers.IO` の `NonCancellable` で行う（キャンセルされても kill は必ず走る）。

## 設定（`agent.terminal.*`、すべて任意）

| キー | 既定値 | 説明 |
|---|---|---|
| `agent.terminal.maxSessions` | 4 | 同時 Terminal 数の上限（1 以上）。 |
| `agent.terminal.idleTimeoutSeconds` | 900 | 入力・出力がこの秒数なければ終了する。 |
| `agent.terminal.maxTtlSeconds` | 28800（8 時間） | 活動の有無によらず、この秒数で終了する。 |
| `agent.terminal.defaultShell` | `/bin/sh` | `shell` クエリ未指定時のシェル。 |

すべて未設定でも既定値で起動する。値の指定は `application.conf` の `agent.terminal` セクション（HOCON）で行う。

## 環境変数・シェルの前提

- 子プロセスには agent の環境変数を引き継ぎ、次を設定する: `TERM=xterm-256color`、`LANG`（未設定時のみ `C.UTF-8`）、`EXKES_EXECUTION_TOKEN=<セッション用トークン>`。
- agent の環境変数（認証情報を含みうる）はシェルに見える。Runtime Pod 内のプロセスは利用者の作業環境として扱う前提。

## ログ方針

- セッションの開始（id・シェルパス）、終了（id・終了コード）、要求の拒否（固定メッセージ）のみ記録する。
- **キー入力（`input`）・端末出力（`output`）の内容は一切ログに出さない。** キー入力は秘密（パスワード等）を含みうるため。
- シェル起動失敗の例外はサーバログに残すが、クライアントには固定文言のみ返す。

## ライブラリ選定

PTY の実現に **pty4j 0.13.13**（`org.jetbrains.pty4j:pty4j`）を採用。

- 理由: 本物の PTY（`openpty` / `forkpty` 相当）が得られ、vim 等の全画面アプリ・ウィンドウサイズ変更（`SIGWINCH`）が正しく動く。libpty.so は jar に同梱されており（linux x86-64 / aarch64）、ビルド時のネイティブコンパイルや Dockerfile の変更が不要。
- `pty4j 0.14.0` は Kotlin metadata 2.4.0 でビルドされており、本プロジェクトの Kotlin 2.1.21 コンパイラで読めないため採用しない。0.13.13 は kotlin-stdlib 2.1.21 でビルドされている。
- `kotlin-stdlib` は pty4j の依存から除外する（プロジェクトの Kotlin プラグインが stdlib を固定するため）。
- `net.java.dev.jna:jna` 5.19.1 を直接依存に加える。pty4j が runtime で要求するもので、プロセスグループへの `kill(-pid, SIGKILL)` に使う。
- 代替案（不採用）: `script -qfc` 経由で `util-linux` の `script` を起動する案は、Runtime イメージに `script` が無い場合に成立せず、PTY の制御（ウィンドウサイズ・終了コードの取得）が間接的になるため採用しない。

## テスト

- `runtime-agent/src/test/kotlin/net/kigawa/exkes/runtimeagent/TerminalRoutesTest.kt`: Ktor `testApplication` と WebSocket クライアントで、echo（UTF-8 含む）・初期サイズと resize（`stty size`）・exit code 伝播・WebSocket 切断時の子プロセス（ジョブ含む）kill・`close` メッセージ・同時数上限と枠の解放・アイドル/TTL 超過・不正クエリ・不正メッセージ。
- `terminal/TerminalManagerTest.kt`: 上限と解放、`shutdown()` による全 kill、クエリ検証。
- `terminal/Utf8StreamDecoderTest.kt`: 1 バイトずつ分割した入力でも文字化けしないこと。

## 既知の制限（未解決の懸念）

- **pid 再利用の極小窓:** 通常終了時、シェルは `waitFor()` で回収された後にプロセスグループを kill する。その間にグループ ID が別プロセスに再利用されると誤 kill の可能性がある（確率は極めて低い）。pty4j が `Process.toHandle()` を未サポートのため、開始時刻による識別ができない。
- **出力の切り捨て:** 終了直前の未配信出力は、クライアントが極端に遅い場合 3 秒の排出上限で切り捨てられることがある。
- **バイナリ非可逆:** 上記のとおり、UTF-8 以外のバイトは U+FFFD になる。
- **認証なし:** agent の `/terminal` は誰でも接続できる。gateway 経由に限定する NetworkPolicy が前提。
- **Pod 内のシェルは agent コンテナのもの:** Terminal のシェルは runtime-agent の image（`eclipse-temurin:21-jre-jammy` ベース、`/bin/sh` は dash）上で起動する。user コンテナの作業ツール（git 等）は見えない。user コンテナ内で開く経路は未実装。

## コンテナでの確認（Runtime Pod 相当）

`docker build --build-arg MODULE=runtime-agent -t exkes-runtime-agent:test .` で作った image を、Pod の securityContext 相当の条件で起動して確認した。

- `--user 1000:1000 --read-only --cap-drop ALL --security-opt no-new-privileges:true`、`/tmp`・`/workspace`・`/var/lib/exkes-agent` は tmpfs（Pod では emptyDir / PVC）
- `GET /health` → `200 ok`
- `GET /terminal?cols=100&rows=30&cwd=/workspace` の WebSocket で `input` を送ると、`output` に `echo` の結果、`id -u` が `1000`、`pwd` が `/workspace`。`exit` で `exit code 0`。
- 反例: `/var/lib/exkes-agent` を書き込み可能にしないと起動時に `FileSystemException: /var/lib/exkes-agent: Read-only file system` で終了する。Pod spec で state 用 emptyDir を必ずマウントすること。

Pod 側の前提（`exkes-controller` の `buildRuntimePodSpec`）:

| 項目 | 値 |
|---|---|
| UID / GID | 1000 / 1000（数値。両コンテナ、Pod レベル） |
| 環境変数（agent） | `EXKES_AGENT_WORKING_DIR=/workspace`、`EXKES_AGENT_STATE_DIR=/var/lib/exkes-agent` |
| マウント（agent） | `/workspace`（PVC）、`/tmp`（emptyDir）、`/var/lib/exkes-agent`（emptyDir）。root fs は read-only |
| readiness | `GET /health` ポート 8080 |
| imagePullSecrets | `exkes.runtime.imagePullSecret`（既定 `harbor-registry`、空で無効） |
