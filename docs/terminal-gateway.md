# exkes-terminal-gateway 仕様

`exkes-terminal-gateway` は、ユーザーの Terminal（ブラウザ / CLI の WebSocket）と Runtime Pod 内の
runtime-agent（`/terminal`、仕様は [runtime-agent-terminal.md](runtime-agent-terminal.md)）を
中継する唯一の入口である。plan 第5MS / issue #1「exkes-terminal-gateway」の要求
（Authentication / Authorization / Session validation / Runtime validation / Session TTL）のうち、
**単回使用トークンの検証・Runtime の再検証・relay・TTL・監査** を実装する。

- 実装: `exkes-terminal-gateway/src/main/kotlin/net/kigawa/exkes/terminalgateway/`
- 前提となる API: `POST /v1/runtimes/{id}/terminal-sessions`（exkes-api、トークン発行）と
  `terminal_sessions`（V9、`TerminalSessionRepository.consume` で一回限りに消費）

## エンドポイント

`GET /v1/terminal`（WebSocket アップグレード）。クエリパラメータは持たない。
`GET /health` は liveness 用に `ok` を返す。

### 接続の流れ

1. クライアントが WebSocket を張る（URL に token は入らない）。
2. **最初のフレーム**を送る（テキスト、1 フレーム、2 KiB 以下）。他のフレームは受け付けない。

   ```json
   {"type":"auth","token":"<発行された token>","cols":120,"rows":40}
   ```

   `cols` / `rows` は任意（1〜1000）。agent の初期 PTY サイズとして `?cols=&rows=` で渡す。
   `cwd` / `shell` はクライアントから指定できない（agent 側の既定値を使う）。未知のフィールドは無視する。
3. ゲートウェイが token を検証し、通れば agent に接続して relay を開始する。
   以後の text フレームはそのまま agent へ、agent の text フレームはそのままクライアントへ渡す
   （JSON の中身は解釈・書き換えしない。`input` / `resize` / `close` / `output` / `exit` / `error` は agent の仕様どおり）。
4. 終了時は次のとおり。

| 終了の理由 | クライアントへ送るフレーム | close code |
|---|---|---|
| agent が終了（`exit` の後に agent が閉じる） | （agent の `exit` はそのまま転送済み） | 1000 `terminal closed` |
| クライアントが閉じる / 切断 | なし | agent 側は切断され、agent がシェルを kill する |
| アイドル超過 | `{"type":"error","message":"terminal session was idle for too long"}` | 1008 |
| 最大寿命超過 | `{"type":"error","message":"terminal session reached its maximum lifetime"}` | 1008 |
| agent に接続できない | `{"type":"error","message":"terminal unavailable"}` | 1011 |
| agent 接続が途中で壊れた | 同上 | 1011 |
| 認証・検証の失敗（下記） | `{"type":"error","message":"terminal session rejected"}` | 1008 |

### 拒否の扱い（情報を漏らさない）

- 不明・消費済み・期限切れの token、存在しない Runtime、RUNNING でない Runtime、Ready 観測が古い Runtime、
  Workspace 不整合、Pod 名不正 は**すべて同じ応答**（`terminal session rejected` / 1008）。
  クライアントは理由を区別できない。理由コードは監査ログにのみ出す。
- 認証フレームの不正（JSON 不正、`type` 違い、token が空または 256 文字超、`cols` 範囲外）も同じ応答。
- 認証フレームが `authTimeoutSeconds`（既定 10 秒）以内に来ない場合も同じ応答。
- 「agent に届かない」だけは運用上の問題なので `terminal unavailable`（1011）と区別する。

## 認証方式: なぜ最初のフレームで token を渡すか

token は単回使用の bearer credential であり、漏れると別の人がその Runtime に入れる。候補と判断:

| 方式 | 問題 |
|---|---|
| URL クエリ `?token=...` | Ingress / LB / プロキシのアクセスログ、ブラウザ履歴、Referer に残る。issue #1 が明示的に避けるよう求めている。 |
| `Authorization` ヘッダ | ブラウザの WebSocket API は任意ヘッダを付けられない。 |
| `Sec-WebSocket-Protocol` にトークンを入れる | 使えるが、サブプロトコルは応答にエコーされる仕様の癖があり、誤って返すとログ・応答に残る。 |
| Cookie | 認証基盤（OIDC）を入れる後続フェーズまで保留。今は CSRF 対策も無い。 |
| **最初のフレーム（採用）** | URL にもヘッダにも現れない。HTTP のログ・ヘッダ・ブラウザ履歴に残らない。WebSocket は upgrade 直後に認証を待ち、認証が通るまで agent に何も転送しない。 |

採用方式の制約:
- 認証前の接続が最大 `authTimeoutSeconds` 生きる（既定 10 秒、フレーム 2 KiB 以下）。
- 認証前に agent へは接続しない。拒否された token は agent に一切届かない。

### トークンの扱い（ログ・例外）

- token は `TerminalSessionRepository.consume` でハッシュ化されてから DB に問い合わせる（exkes-api の発行時と同じ SHA-256）。
- 認証フレームのパース失敗は例外メッセージを**読まずに捨てる**（kotlinx の JSON エラーは入力の一部を引用するため）。
- 例外を捕まえた場合も、ログには**例外クラス名のみ**を出す（JDBC / SQL メッセージに値が含まれる可能性があるため）。
- テスト `audit log never contains the token or the relayed keystrokes` が、ルートロガーに届く全ログ（例外メッセージを含む）に token と入力文字列が無いことを確認する。

## 検証フロー（admission）

`TerminalAuthorizer.admit(token)`（ブロッキング JDBC。`Dispatchers.IO` で実行）:

1. `TerminalSessionRepository.consume(token, now)`: 単回使用の条件付き UPDATE。失敗は `token_rejected`。
   **消費は先に行う**。後段の検証で拒否されても token は消費済みになる（fail closed。クライアントは新しい session を発行し直す）。
2. Runtime を再読込する。存在しない / DELETED は `runtime_not_found`、RUNNING でなければ `runtime_not_running`。
3. `RuntimeAdmission.denialReason`（exkes-common）で判定する。exkes-api の Execution / Terminal 発行と**同一のルール**:
   Workspace の PVC 配置が検証済み、Ready 観測が `exkes.admission.freshnessSeconds`（既定 15 秒）以内、
   Ready 観測が未来でない、TTL 内。失敗は `runtime_not_admitting`。
4. agent の URL を解決する（下記）。解決できなければ `no_agent_address`。

ルールは元々 `RuntimeService.admitRuntime`（exkes-api）に private で置かれていたが、gateway が
exkes-api に依存できないため `exkes-common` の `RuntimeAdmission` に移した。判定順・メッセージは変更していない
（`RuntimeServiceTest` が従来どおり通る）。

## agent の到達先の解決

- DB の既存情報だけを使う。`runtimes.pod_name`（controller が `rt-<runtime id>` で決める値）を、
  設定の URL テンプレート `exkes.terminalGateway.agentUrlTemplate` の `{podName}` に代入する。
- 既定値: `ws://{podName}.platform-exkes-stg-sandbox.svc.cluster.local:8080/terminal`
  （`EXKES_TERMINAL_GATEWAY_AGENT_URL_TEMPLATE` で上書き）。
- `pod_name` は DNS-1123 ラベル（`[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?`）に一致しなければ使わない（ホスト部を変えられないようにする）。
- **前提（未実装・controller 側の作業）**: Pod 名と同じ名前の `Service`（ClusterIP、Pod を selector で選択、port 8080）が
  Sandbox namespace に存在すること。`rt-<id>.<namespace>.svc` で Pod に届く。

### 検討して採らなかった案

- **Pod IP を DB に保存する**: Pod IP は Pod 再作成で変わり、controller が Ready 観測のたびに更新する必要がある。
  スキーマ変更（V10 以降）を伴うため今回は見送り。将来 Ready 観測と同時に書けるなら検討余地がある。
- **gateway から Kubernetes API で Pod IP を引く**: issue #2 で gateway への client-java / 権限付与は禁止されている。不可。
- **`<pod-ip>.<ns>.pod.cluster.local` を使う**: 同じく Pod IP が必要。

### 不足している DB / 設定

今回のリポジトリ内で完結させるための追加カラムは**無い**（`pod_name` を使う）。
ただし次が無いと end-to-end では動かない:
- controller が Runtime 用の Service を作ること（platform manifest / reconciler 側。第2MS〜）。
- Service 名 = Pod 名、かつ sandbox namespace 向けの URL テンプレート（namespace が stg / main で違うため、環境ごとに上書きする）。

## relay の制約

- 最大メッセージ: `maxMessageBytes`（既定 128 KiB、両方向）。gateway の受信側は Ktor の `maxFrameSize` にも同じ値を設定し、
  超えたフレームは Ktor の挙動に任せる（接続が閉じられることを想定。この点は単体テストでは検証していない）。
  agent の `output` は 8 KiB 単位だが、JSON エスケープで数倍になりうるため 128 KiB とした。
- 受け付けるのは非分割（`fin=true`）の text フレームだけ。分割フレームと binary フレームは `error` を返して捨て、セッションは継続する（agent と同じ扱い）。
- アイドル: 両方向のどちらにも中継フレームが無い状態が `idleTimeoutSeconds`（既定 900 秒）続くと切断する。
  長時間無出力のコマンド（`sleep 3600` など）は切断されるので、運用上は注意が要る。
- 最大寿命: `maxLifetimeSeconds`（既定 3600 秒）で、アクティビティに関係なく切断する。
  token の TTL（`exkes.terminal.sessionTtlSeconds`、既定 300 秒）は**接続開始まで**の期限であり、接続時間の上限ではない。
- バックプレッシャ: 各方向は「1 フレーム受信 → 送信」の逐次ループ。受信側が遅いと読み取りが止まり、TCP の流量制御で伝わる。
- 終了: 最初の終了理由が勝ち、他方の pump はキャンセルされ、agent の接続は non-cancellable に閉じる。
  クライアント側の close は呼び出し元が理由に応じて送る。
- 出力は UTF-8 文字列（runtime-agent 仕様のとおり、バイナリは非可逆）。

## 監査ログ

専用ロガー `TerminalGatewayAudit`。gateway には専用の logback.xml が無く、既定の設定（コンソール・平文）に出る（api のような JSON エンコーダ設定は未導入。必要なら追加する）。出すのは識別子・理由コード・時間・バイト数だけ。

| イベント | 例 |
|---|---|
| 接続開始 | `terminal connection accepted sessionId=<uuid> runtimeId=<uuid>` |
| 拒否 | `terminal connection rejected reason=token_rejected runtimeId=none detail=none` |
| 終了 | `terminal connection closed sessionId=... runtimeId=... end=idle_timeout durationMs=... clientToAgentBytes=... agentToClientBytes=...` |
| admission 例外 | `terminal admission failed exception=<クラス名>` |

- `reason` は `auth_timeout` / `malformed_auth` / `client_closed_before_auth` / `token_rejected` /
  `runtime_not_found` / `runtime_not_running` / `runtime_not_admitting` / `no_agent_address`。
- **出さないもの**: token、認証フレーム全体、`input` / `output` の内容、例外メッセージ。
- `detail` は内部の判定理由（例: `last Ready observation is stale`）で、Runtime の状態名しか含まない。
- session id は DB の UUID（token から導出しない）。

## 設定

`exkes-terminal-gateway/src/main/resources/application.conf`。`exkes.kubernetes.*` / `exkes.reconciler.*` /
`exkes.workspace.*` は書かない（gateway は読まない）。

| キー | 既定 | 環境変数 | 意味 |
|---|---|---|---|
| `exkes.database.*` | api と同じ | `EXKES_DATABASE_*` | MariaDB 接続 |
| `exkes.admission.freshnessSeconds` | 15 | `EXKES_ADMISSION_FRESHNESS_SECONDS` | Ready 観測の鮮度（api と同じ値にする） |
| `exkes.terminalGateway.agentUrlTemplate` | `ws://{podName}.platform-exkes-stg-sandbox.svc.cluster.local:8080/terminal` | `EXKES_TERMINAL_GATEWAY_AGENT_URL_TEMPLATE` | agent URL。`{podName}` 必須、`ws://` か `wss://` |
| `exkes.terminalGateway.authTimeoutSeconds` | 10 | `EXKES_TERMINAL_GATEWAY_AUTH_TIMEOUT_SECONDS` | 認証フレームの期限 |
| `exkes.terminalGateway.idleTimeoutSeconds` | 900 | `EXKES_TERMINAL_GATEWAY_IDLE_TIMEOUT_SECONDS` | アイドル切断 |
| `exkes.terminalGateway.maxLifetimeSeconds` | 3600 | `EXKES_TERMINAL_GATEWAY_MAX_LIFETIME_SECONDS` | 接続の最大寿命 |
| `exkes.terminalGateway.maxMessageBytes` | 131072 | `EXKES_TERMINAL_GATEWAY_MAX_MESSAGE_BYTES` | 1 メッセージの上限（1 KiB〜1 MiB） |

起動時に不正な値（テンプレートの欠落、非正のタイムアウト、範囲外のサイズ）は即座に失敗する。

## DB マイグレーションを実行しない理由

`exkes-api` と `exkes-controller` は起動時に Flyway を実行し、どちらが先に立ち上がっても動くようにしている
（CLAUDE.md の migration 手順 5）。gateway はこの一覧に含めない:

- gateway は `terminal_sessions` / `runtimes` / `workspaces` を読み書きするだけで、スキーマ変更の責務を持たない。
- 単独で起動したときに schema を作ると、api / controller より先にスキーマを確定させてしまい、
  migration の順序と所有者が曖昧になる。
- gateway は schema が無いときに consume で失敗する（fail closed）。テスト `gateway does not run database migrations` が
  「migrate せずに /health が返り、テーブルが作られない」ことを確認する。

## CI（デプロイ）の扱い: 今回は変更しない

`exkes-terminal-gateway` は `.github/workflows/deploy-stg.yml` / `deploy-prod.yml` に**入れていない**。理由:

- `deploy-stg.yml` の build matrix に入れると、image は push されるが、更新対象の 2 ファイル
  （`exkes-api.yaml` / `exkes-controller.yaml`）に含まれないため**デプロイされない**（CLAUDE.md の警告どおり）。
- `env.MODULES` に入れると、deploy 側が `kigawa-net/platform` の `exkes/stg/exkes-terminal-gateway.yaml` を探し、
  無ければ失敗する。`deploy-prod.yml` は stg manifest を main へ転記するため、stg が未整備だと **prod の昇格が失敗する**。
- issue #2 は gateway を「対象外」にしており、CI 対象化はその判断の変更になる。

Dockerfile は変更不要（`MODULE` 引数の経路は既存のまま。`:exkes-terminal-gateway:installDist` の成功は確認済み。`docker build` は未実行）。

### CI 対象化の手順（platform 側の準備が済んでから）

1. `kigawa-net/platform` に `exkes/stg/exkes-terminal-gateway.yaml`（Deployment、`containers[0]` が gateway）と
   `exkes-terminal-gateway-svc.yaml`、`exkes/main/...` を追加して main にマージする。
2. このリポジトリで `deploy-stg.yml`: build matrix と `env.MODULES` に追加し、deploy の更新対象に
   `exkes-terminal-gateway.yaml` を加える（ファイル名を列挙する。glob は使わない）。
3. `deploy-prod.yml`: `env.MODULES` に追加する（stg manifest が main に無い状態でマージしない）。
4. CLAUDE.md の「モジュール追加手順」4〜6 を満たしているか確認する。

## NetworkPolicy / platform manifest（このリポジトリの外。案）

目的: **agent の受信は gateway からの接続だけ**。gateway は agent と DB にしか出られない。
（runtime-agent-terminal.md の「認証は行わない。NetworkPolicy で gateway からの接続だけを許可する」の前提。）

### Sandbox namespace（`platform-exkes-stg-sandbox`）

既存の default-deny（`network-policy.yaml`、plan 7.3）に対し、Runtime Pod（`exkes.runtime: "true"`）の
Ingress を gateway の Pod に限定して許可する。

```yaml
# exkes/stg/terminal-gateway-to-runtime-netpol.yaml（案）
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-terminal-gateway-to-runtime
  namespace: platform-exkes-stg-sandbox
spec:
  podSelector:
    matchLabels:
      exkes.runtime: "true"
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: platform-exkes-stg
          podSelector:
            matchLabels:
              app.kubernetes.io/name: exkes-terminal-gateway
      ports:
        - protocol: TCP
          port: 8080
```

- 同じ namespace の他の Pod、Control Plane の `exkes-api` / `exkes-controller`、他 Runtime からの 8080 は届かない。
- Service（`rt-<id>`）経由でも、Service の入口は NetworkPolicy で制御される Pod への接続として評価される。

### Control Plane namespace（gateway 側の egress）

- gateway → sandbox の Pod（TCP 8080）と、gateway → MariaDB（既存の DB 接続先）だけを許可する。
- gateway から Kubernetes API への egress は**定義しない**（ServiceAccount も `automountServiceAccountToken: false`）。
- 外部（インターネット）への egress は不要。

### Runtime 側の Service（controller が作る。案）

```yaml
apiVersion: v1
kind: Service
metadata:
  name: rt-<runtime-id>          # runtimes.pod_name と同じ
  namespace: platform-exkes-stg-sandbox
spec:
  selector:
    exkes.runtime-id: <runtime-id>   # Pod 側に付けるラベル
  ports:
    - name: agent
      port: 8080
      targetPort: 8080
```

## テスト

`exkes-terminal-gateway/src/test/kotlin/net/kigawa/exkes/terminalgateway/`

- `TerminalGatewayRoutesTest`: `testApplication` で gateway を起動し、runtime-agent は loopback の fake WebSocket サーバ
  （`support/FakeAgent`、`/terminal` の最小実装）。DB は H2（`MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE`）+ Flyway。
  - 有効 token で relay と入出力の往復、cols / rows の転送、client close → exit → close 1000
  - 二重使用の拒否（agent に届かない）、同時使用で 1 件だけ通ること
  - 期限切れ、不明 token、RUNNING でない Runtime、Ready が古い Runtime の拒否（agent に届かない）
  - 認証フレームの不正・未送信（タイムアウト）の拒否
  - agent 不達（1011）、アイドル超過・最大寿命超過（error + 1008、agent 側の切断を確認）
  - client 切断で agent 側も切断、binary フレームは error で継続
  - 監査ログに token・入力内容・例外メッセージが出ないこと（ルートロガーを捕捉）
  - gateway が migrate しないこと
- `TerminalProtocolTest`: auth フレームのパース、agent URL の解決（Pod 名の検証）、設定値の検証。

待ちはすべて上限付き（`WAIT_LIMIT`）の条件待ちで、固定 sleep は使わない。

## 未解決事項・懸念

- **Runtime 用 Service を作る controller 側の実装がまだ無い**。gateway は `pod_name` 名の Service が存在する前提で動く。
- gateway 全体の同時接続数に上限が無い（agent 側の上限は別）。必要になったら `maxConnections` を追加する。
- TLS は gateway で終端しない前提（クラスタ内 `ws://`）。外部公開（Ingress / DNS）は認証（Keycloak OIDC）と合わせて後続。
- **認可は未実装**。token を持つ者は誰でもその Runtime に入れる。ユーザー単位の所有者チェックは認証フェーズで扱う。
- token は拒否された場合も消費される（検証順序の仕様）。認証フレームが不正な場合（JSON 不正など）は消費されない。
- agent の出力は UTF-8 置換を含む非可逆（runtime-agent 仕様どおり）。
- 出力の大量送信時のバックプレッシャは逐次ループと TCP 流量制御に依存しており、Ktor 内部の送信バッファ上限は測っていない。
