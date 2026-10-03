# exkes

AI Agent や任意コマンドを安全な隔離環境（Kubernetes）で実行するための
Execution Runtime Platform。

詳細な設計は [docs/implementation-plan.md](docs/implementation-plan.md)、
要求定義は [docs/issue-1-body.md](docs/issue-1-body.md) を参照。
デプロイ方針の基準は [docs/issue-2-body.md](docs/issue-2-body.md)
（stg 自動デプロイ + prod 手動昇格、2 環境のみ）。

> `docs/issue-{1,2}-body.md` は **2026-10-01 時点の issue 本文のスナップショット**。
> 2026-10-03 に issue #1 / #2 は「Epic + 子 issue（#3〜#17）」へ再構成された。
> スナップショットのチェックボックスは現行 issue に存在しない（対応表は plan 10.3 章）。

## 構成

Gradle マルチモジュール monorepo。モジュールは「制御平面のデプロイ単位」に対応する。

| モジュール | 責務 | 依存 | 状態 |
|---|---|---|---|
| `exkes-common` | 設定 / DB (Exposed + Flyway) / Workspace domain。**Kubernetes client 非依存** | 外部のみ | 第1MS 実装済 |
| `exkes-api` | REST API (`/v1/workspaces`)。desired state だけを書く | `exkes-common` | 第1MS 実装済 |
| `exkes-controller` | Desired State と K8s 実状態の reconciliation。**Kubernetes 権限を持つ唯一のプロセス** | `exkes-common` + client-java | 第1MS 実装済（Workspace のみ） |
| `exkes-terminal-gateway` | WebSocket による Terminal relay | `exkes-common` | スケルトン（第5MS・issue #2 対象外） |
| `runtime-agent` | Runtime Pod 内の Process / PTY / Execution 管理 | **common 非依存** | スケルトン（第3MS・issue #2 対象外） |

`exkes-api` は Kubernetes API を一切呼ばない。Deployment で
`automountServiceAccountToken: false` を設定し、ServiceAccount も持たせない
（issue #2「Controller のみに Kubernetes 権限」）。

アーキテクチャ上、Control Plane（`exkes-api` / `exkes-controller` ほか）と
Sandbox Plane（`runtime-agent`、namespace は `platform-exkes-{stg,main}-sandbox`）は
分離する。Sandbox から Control Plane・MariaDB・Kubernetes API・他 Runtime へ
直接アクセスさせない。

## 必要環境

- JDK 21（Gradle wrapper は Gradle 8.14 / Kotlin 2.1.21）
- Docker + Docker Compose（ローカル起動用）

**JDK 21 で `./gradlew` を実行すること。** Gradle 8.14 は JDK 25 を Launcher JVM に
サポートしないうえ、`jvmToolchain(21)` 用の JDK も要る。シェル既定の JDK が 21 以外なら
JAVA_HOME を明示する（CI も `actions/setup-java` で 21 を導入している）:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew test
```

CI では `gradle/actions/setup-gradle@v4` がリポジトリの wrapper に GraalVM/JDK を
自動導入するが、ローカルでは素の JDK で走らせる（`org.gradle.java.home` を
`~/.gradle/gradle.properties` に書いておく方法でも良い）。

## 起動方法

### Docker Compose（推奨）

```bash
docker compose up -d
curl http://localhost:8080/health   # {"status":"ok"}  exkes-api
curl http://localhost:8081/health   # ok             exkes-controller
```

`exkes-api` と `exkes-controller` の両方が起動時に Flyway でスキーマを migrate してから
待ち受ける（独立した Deployment なので、どちらが先に立ち上がっても動くようにしている）。
ホスト側のポートが使用中の場合は `EXKES_HTTP_PORT=18080 EXKES_CONTROLLER_HTTP_PORT=18081 docker compose up -d`
のように変更できる（既定 8080 / 8081）。
`docker compose up -d mariadb` だけにして Gradle から直接動かすこともできる。

### Gradle

```bash
docker compose up -d mariadb
./gradlew :exkes-api:run
./gradlew :exkes-controller:run
```

※ ローカルに Kubernetes クラスタがない場合、Workspace 作成の provisioning は
失敗し `status=ERROR` になる（`/health` と API 自体は動作する）。
kubeconfig 経由で試す場合は `EXKES_KUBERNETES_USE_IN_CLUSTER=false` を設定する。

### テスト / ビルド

```bash
./gradlew test            # 全モジュールのテスト
./gradlew build -x test   # ビルドのみ
```

テストは H2 (`MODE=MySQL`) + Flyway と fake provisioner だけで完結し、
実際の Kubernetes クラスタは要らない。

### イメージビルド

```bash
./gradlew :exkes-api:installDist        # ローカル検証用
./gradlew :exkes-controller:installDist
docker build --build-arg MODULE=exkes-api -t exkes-api:local .
```

## API

| Method / Path | 応答 | 説明 |
|---|---|---|
| `POST /v1/workspaces` | 202 | Workspace 作成（`CREATING` で即時応答、PVC 作成は非同期） |
| `GET /v1/workspaces` | 200 | 一覧（`?status=&limit=&offset=`）。`DELETED` は一覧に出さない |
| `GET /v1/workspaces/{id}` | 200 / 404 | 単体取得（`DELETED` は 404） |
| `DELETE /v1/workspaces/{id}` | 202 / 404 / 409 | 削除要求（`DELETING` へ遷移、PVC 削除は非同期）。404 = 未存在/削除済み、409 = 競合書き込みで条件付き UPDATE が 3 回とも失敗 |
| `GET /health` | 200 | liveness / readiness probe 用 |

エラーは共通形式 `{"code": "NOT_FOUND|CONFLICT|VALIDATION_ERROR|INTERNAL", "message": "..."}`。
500 の場合、例外の詳細はサーバ側ログにのみ記録し（JDBC URL や Kubernetes API の
レスポンス本文など秘密 Informationenがクライアントへ漏洩しないよう）、メッセージは
`internal error` に固定する。

Workspace の状態機械:

```text
CREATING ──PVC Bound──▶ READY ──▶ ARCHIVED
   │                     │            │
   ├──失敗(再試行上限)──▶ ERROR        │
   │                     ▼            ▼
   └──────────DELETE──────────▶ DELETING ──PVC削除完了──▶ DELETED
```

## 環境変数一覧

`application.conf`（HOCON）の各キーは `EXKES_<SECTION>_<KEY>` の環境変数で
上書きできる。`exkes-api` と `exkes-controller` は**別個の `application.conf`** を持つ
（`exkes.kubernetes.*` と `exkes.reconciler.*` は controller 側にだけ存在する）。

### 共通（`exkes-api` / `exkes-controller`）

| 環境変数 | 説明 | 既定値 |
|---|---|---|
| `PORT` | 待ち受けポート | `8080` |
| `EXKES_DATABASE_JDBC_URL` | MariaDB の JDBC URL | `jdbc:mariadb://localhost:3306/exkes` |
| `EXKES_DATABASE_USER` | DB ユーザー | `exkes` |
| `EXKES_DATABASE_PASSWORD` | DB パスワード | `change-this-in-production` |
| `EXKES_WORKSPACE_STORAGECLASS` | Workspace PVC の StorageClass | `rook-cephfs` |
| `EXKES_WORKSPACE_DEFAULT_STORAGE_SIZE_BYTES` | 作成時の既定サイズ（bytes） | `1073741824`（1Gi） |

### `exkes-controller` 専用

| 環境変数 | 説明 | 既定値 |
|---|---|---|
| `EXKES_KUBERNETES_NAMESPACE` | PVC を作成する namespace | `default` |
| `EXKES_KUBERNETES_USE_IN_CLUSTER` | in-cluster 認証を使う（`false` で kubeconfig） | `true` |
| `EXKES_KUBERNETES_KUBECONFIG_PATH` | kubeconfig のパス（空なら `~/.kube/config`） | （空） |
| `EXKES_RECONCILER_INTERVAL_SECONDS` | reconciler のポーリング周期 | `5` |
| `EXKES_RECONCILER_MAX_ATTEMPTS` | `ERROR` 化までの provisioning 再試行回数 | `3` |
| `EXKES_RECONCILER_CREATING_TIMEOUT_SECONDS` | 例外なしで `Bound` にならないまま `CREATING` を保つ上限（秒）。超過で `ERROR`。`0` で無効 | `300` |

> Sandbox namespace（`platform-exkes-{stg,main}-sandbox`）を config から読む設定は
> **まだ無い**。Runtime Pod を Sandbox に置くのは第2MS なので、その時点で
> `exkes.kubernetes.sandboxNamespace` / `EXKES_KUBERNETES_SANDBOX_NAMESPACE` を追加する。
> 第1MSで controller が触るのは自 namespace（`EXKES_KUBERNETES_NAMESPACE`）の
> PVC だけ。

## CI/CD

環境は **stg / main の 2 つだけ**（dev / PR preview / ApplicationSet は作らない、
issue #2）。workflow は 2 本。

### `.github/workflows/deploy-stg.yml`

`pull_request` と `main` push で起動する。

| ジョブ | 条件 | 内容 |
|---|---|---|
| `test` | 常に | `./gradlew test` |
| `build` | main push のみ | matrix（`exkes-api` / `exkes-controller`）で `harbor.kigawa.net/private/exkes-<module>:main-<sha>` へ build & push |
| `deploy-stg` | main push のみ | WIF（`kigawa-net-app-token@dev`）で `kigawa-net/platform` の書き込みトークンを鋳造し、`./exkes/stg/exkes-{api,controller}.yaml` の image を `yq` で更新して push |

**PR では test だけが実行される**（image は push しない、platform へはコミットしない）。

更新スクリプトのガード:

- 対象ファイルは `exkes/stg/<module>.yaml` の 2 つだけ。`kind` が `Deployment` で
  なければ hard fail（`exkes-api-svc.yaml` などに `.spec.template` を注入して Argo CD が
  `unknown field` で弾く事故を防ぐ）
- 書き込み後に読み戻し検証し、意図しないファイルがステージされていないか確認
- `image` は `export` してから `strenv(image)` で読む（shell 変数だと空になる）
- 更新対象が 1 つも無い場合は `changed=false` を出力し、`push` ステップを `if` で
  スキップする（`git commit` を走らせると `nothing to commit` でジョブが落ちてしまう）

### `.github/workflows/deploy-prod.yml`

`workflow_dispatch` で起動（`refs/heads/main` のみ許可）。

- **build / push は一切しない。** `exkes/stg/<module>.yaml` から現在の image を
  `yq` で読み、`exkes/main/<module>.yaml` へ**そのまま転記**して push
  → Argo CD が `platform-exkes-main` へ同期
- 昇格元 image が `harbor.kigawa.net/private/<module>:main-<40 hex>` でなければ fail
  （`:latest` や別 project の image だと prod が stg と別バイナリで動くため）
- prod が既に stg と同一 image を参照している場合は `changed=false` にして commit を
  スキップする（同じ昇格を再実行してもジョブは緑のまま）

> **依存順序**: `exkes/{stg,main}/` のマニフェストは `kigawa-net/platform` 側の
> 別 PR で追加する。**platform 側の manifest PR が未マージのまま exkes の main を
> マージすると、stg デプロイは該当モジュールだけ `::error::` を出してスキップする**
> （CI は失敗しない）。`platform` のマニフェスト PR を先に main に入れておくこと。
> 一方 prod 昇格は stg manifest が無ければ hard fail する。

## ステータス

- **第1マイルストーン（本リポジトリの初期実装）**: 実装済み
  - Gradle マルチモジュール scaffold / Flyway (`V1__workspaces.sql`) /
    Workspace domain（状態機械）/ `/v1/workspaces` API /
    CephFS PVC provisioning（`exkes-controller` の `WorkspaceProvisioner` +
    `WorkspaceReconciler`）/ Dockerfile（installDist 方式）/ docker-compose /
    CI（`deploy-stg.yml` + `deploy-prod.yml`）/ テスト 51 件
- **issue #2 反映済み（コード / CI 側）**: stg 自動デプロイ / prod 手動昇格（再ビルドなし）/
  単一 image タグ / `exkes-controller` 専用 RBAC（`exkes-api` は token を持たない）。
  Sandbox Namespace と NetworkPolicy は**設計確定**（plan 9.6 章）で、
  manifest の追加は platform 側の別 issue の作業
- **未実装（後続マイルストーン）**:
  - Runtime / Execution / Terminal / Agent（第2〜5MS）
  - 認証（Keycloak OIDC）— MVP 後続フェーズ
  - NetworkPolicy の egress 許可リスト（第3MS。default-deny 自体は issue #2 で定義済み）
  - Metrics / E2E（第6MS）
  - `exkes-controller` のリーダー選出（現状 `replicas: 1` 前提）。
    あわせて provisioning 試行回数の schema 列化（現状はプロセス内メモリで、
    再起動するとリセットされる）も follow-up 課題。
- リポジトリ外の前提作業（別 issue・別リポジトリ）:
  `platform` 側マニフェストの作成、`HARBOR_PASS` 登録、
  `kigawa-net-k8s` の secret 同期 namespace 追加（plan 第11章・第12章）。
  **platform 側の `exkes/{stg,main}/` マニフェスト PR が main に
  入っていない場合、stg の image 更新はスキップされるので、依存順序に注意**。
- **issue #3 / #4 / #6 のうち、コード側は一部のみ実装**。sub-issue は closed にせず、
  現行 issue との対応は plan 10.3 章を参照（issue #1 は 2026-10-03 に
  Epic + 子 issue へ再構成されている）。
