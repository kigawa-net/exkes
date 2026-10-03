# exkes 実装計画（Implementation Plan）

- 対象リポジトリ: `kigawa-net/exkes`
- 根拠資料:
  - [issue #1「exkes MVP」](./issue-1-body.md)（要件・アーキテクチャ・MVPタスク23項目・完了条件14項目）
  - [issue #2「deploy exkes via platform with stg auto deploy and manual prod promotion」](./issue-2-body.md)
    （デプロイ規則・platform 側構成・Controller RBAC・Sandbox Namespace）
- 状態: 第1マイルストーン実装完了（PR 提出時点）。**デプロイ方針は issue #2 を基準に改訂済み**
  （改訂履歴は 0 章）。2026-10-03 の issue 再構成（Epic + 子 issue）にも追随済み（0 章・10.3 章）
- 作成日: 2026-10-01 / 最終改訂: 2026-10-03

このドキュメントは issue #1 の内容を、確定済みの技術方針・実際のインフラ実物
（`kigawa-net/platform`、`kigawa-net/ai-scheduler`、`kigawa-net/keruta`、`kigawa-net/kigawa-net-k8s`）
に適合する形で具体化した実装計画である。issue #1 の MVPタスク23項目・完了条件14項目は
すべて第10章のフェーズ表で追跡する。

---

## 0. 改訂履歴（issue #2 基準への追随）

初版は「dev（PR）環境 + Argo CD ApplicationSet + stg」というデプロイ方針で書いた。
**issue #2 作成時にデプロイ方針が変更され、issue #2 を基準に再編した。**
以下の差分は意図的な変更であり、実装・CI・マニフェスト構成のすべてを issue #2 に揃えている。

| # | 初版の方針 | 改訂後（issue #2 基準） | 反映先 |
|---|---|---|---|
| 1 | dev（PR ごと）+ stg の 2 環境。`pull_request` で image push → ApplicationSet が namespace/image を生成 | **stg と main の 2 環境のみ**。dev / PR preview / ApplicationSet は作らない。`pull_request` では test のみ実行し image も push しない | 3 章 / 8 章 / 9 章 / 10 章 |
| 2 | `.github/workflows/cd.yml` 1 本（test → build → deploy-stg） | **`.github/workflows/deploy-stg.yml` と `deploy-prod.yml` の 2 本**に分割（issue #2 の命名に従う） | 3 章 / 8 章 / 11 章 |
| 3 | prod への昇格経路なし（stg のみ） | `workflow_dispatch`（`refs/heads/main` 限定）で prod へ手動昇格。**再ビルドせず stg で使用中の image tag をそのまま転記** | 8 章 |
| 4 | image は `harbor.kigawa.net/library/exkes-*:<tag>`（単一 project 想定の記述） | `harbor.kigawa.net/private/exkes-<module>:main-<sha>`。**タグはモジュール別**、stg / prod で同一タグ。push 対象は `exkes-api` と `exkes-controller` の 2 つ（`exkes-terminal-gateway` / `runtime-agent` は issue #2「対象外」） | 8 章 / 9 章 |

   > **行 4 の理由（issue #2 本文の `private/exkes:main-<sha>` からの意図的な逸脱）**:
   > issue #2 の Flow は単一 image `private/exkes:main-<sha>` を前提に書かれているが、
   > 同じ issue の「`exkes-api`、`terminal-gateway`、Runtime Pod には Kubernetes Runtime
   > 作成権限を与えない。Controller のみに付与する」という決定により、制御平面は
   > **2 つの別プロセス（`exkes-api` / `exkes-controller`）**に分散し、それぞれ別の
   > Deployment で動く。1 つの image タグで 2 つの異なるバイナリを配ることはできない
   > （`exkes-controller` のクライアントコードを `exkes-api` へ入れると「client-java を
   > api に入れない」が壊れる）。したがってタグをモジュール別にし、
   > **stg / prod では同一タグを使う**ことで「prod は stg と同一バイナリ」という
   > issue #2 の要求は満たしたままである。prod 昇格時のガード（8.2.2 章）も
   > `private/exkes-<module>:main-<40 hex>` に合わせて検査する。
| 5 | Kubernetes 権限を `exkes-api` の ServiceAccount に付与（`exkes-api-rbac.yaml`）。`WorkspaceReconciler` は第2MSで移設する前提 | **`exkes-controller` のみ**が Runtime / PVC 作成権限を持つ。`exkes-api` は Kubernetes API を一切呼ばず `automountServiceAccountToken: false`。`KubernetesClientFactory` / `KubernetesWorkspaceProvisioner` / `WorkspaceReconciler` は**この改訂で `exkes-controller` へ移設済み** | 2 章 / 3 章 / 7 章 |
| 6 | platform マニフェストは Deployment + Service を 1 ファイルにまとめ、`exkes-api-rbac.yaml` などに分割 | **1 リソース 1 ファイルのフラット構成**。`exkes.yaml`（Deployment）と `service.yaml`（Service）を別ファイルにする | 9 章 |
| 7 | NetworkPolicy は第2MSに postpone（第1MSでは書かない） | **Sandbox Namespace（`platform-exkes-{stg,main}-sandbox`）と NetworkPolicy の定義を issue #2 の範囲として扱う**。第1MSには Sandbox Pod が無いので実効は無いが、定義は issue #2 と整合させる（7.3 章） | 7.3 章 / 9 章 |
| 8 | `EXKES_RECONCILER_*` は `exkes-api` の設定 | 同じ設定は **`exkes-controller` 専用**（`exkes.kubernetes.*` と `exkes.reconciler.*` の section を `exkes-api` の `application.conf` から削除） | 3 章 / 6 章 |

> **この改訂の後に issue そのものが再構成された（2026-10-03）**: issue #1 / #2 は
> 「Epic + 子 issue（#3〜#17）」に分割され、issue #1 本文の「MVP タスク 23 項目 /
> 完了条件 14 項目」のチェックボックスは子 issue 側に移った。`docs/issue-1-body.md` /
> `docs/issue-2-body.md` は**再構成前の本文のスナップショット**であり、本文の該当箇所は
> 現行 issue と一致しない。フェーズ割当（10.1 / 10.2 章）はスナップショットの
> 版を前提にしているので、**現行 sub-issue との対応は 10.3 章**に置く。
> デプロイ規則自体（stg 自動 / prod 手動昇格 / 環境は 2 つだけ）は変わらない。

この改訂で**行わなかった**こと（意図的な据え置き）:

- 認証（Keycloak OIDC）・Ingress / DNS の公開。issue #2 も API の認証を要求していないため、
  stg / main はいずれも ClusterIP + Ingress は任意（9 章）。
- `exkes-terminal-gateway` と `runtime-agent` の実装。issue #2 の「対象外」に明記されているため
  スケルトンのまま残し、CI の image matrix にも入れていない。
- Runtime Pod / PVC（Workspace 以外のもの）の実装。issue #2 の「対象外」。

---

## 1. スコープ

### 1.1 第1マイルストーンでやること

| # | 内容 |
|---|------|
| 1 | 本ドキュメント（`docs/implementation-plan.md`）の作成 |
| 2 | Gradle マルチモジュールのスケルトン（`settings.gradle.kts` / 4モジュール + common / wrapper / 依存定義） |
| 3 | Ktor 3.x (Netty) アプリ骨格: `Application.module()`、手動 DI、`application.conf`（HOCON + `${?ENV}`）、logback |
| 4 | MariaDB 初期スキーマ + Flyway migration（`V1__workspaces.sql`、起動時 migrate）+ `docker-compose.yml`（mariadb:11 + api + controller） |
| 5 | Workspace domain 実装: 状態機械（CREATING → READY → ARCHIVED → DELETING → DELETED / ERROR）+ repository + `/v1/workspaces` API |
| 6 | CephFS PVC provisioning（`exkes-controller` 内の `WorkspaceProvisioner` / `WorkspaceReconciler`、詳細は第7章） |
| 7 | Dockerfile（`installDist` 方式）+ `.github/workflows/deploy-stg.yml` / `deploy-prod.yml`（第8章） |
| 8 | `kigawa-net/platform` リポジトリ用マニフェスト（`exkes/{stg,main}/` + `apps/`。**別リポジトリ・別タスク**。第9章） |
| 9 | テスト（JUnit5 + `ktor-server-test-host` + H2 MySQL mode。51 件） |
| 10 | 上記一式を **PR** として提出（main 直 push 不可。Approvals 0 でも PR 必須） |

### 1.2 第1マイルストーンでやらないこと（スコープ外）

- **認証・Authorization**: Keycloak (`user.kigawa.net`) OIDC + JWKS 検証は**後続フェーズ**。第1マイルストーンでは無認証とし、対外公開（Ingress/DNS）も既定では行わない（ClusterIP Service のみ。詳細は第12章）。
- **dev / PR preview 環境**: issue #2 の決定により**作らない**。`pull_request` は test のみ。ApplicationSet・`deploy-preview` ラベル・GitHub App の exkes への導入はいずれも不要になった。
- **他リポジトリの PR**: 以下はすべて**別リポジトリ側の作業**であり exkes リポジトリのスコープ外（第12章で対応 issue を提案）。
  - `kigawa-net/kigawa-net-k8s`: `kigawa-system/secret-provider/bitwarden-sync-crn.yaml` の `TARGET_NAMESPACES` に `platform-exkes-stg` / `platform-exkes-main`（および sandbox namespace）を追記（`harbor-sync-crn.yaml` も同様）
  - `kigawa-net/platform`: `exkes/{stg,main}/` と `apps/exkes-{stg,main}-app.yml` の追加（第9章・第11.2章）
- **Runtime / Execution / Terminal / Agent** の実装（第2マイルストーン以降。第10章の表を参照）
- **Ceph RGW 実装**: `CephObjectStoreUser` 未整備のため後続（第12章）
- **BWS (Bitwarden Secrets Manager) への DB パスワード実UUID の登録**: プレースホルダのまま提出し、デプロイ時に置換

---

## 2. アーキテクチャ概要

issue #1 のアーキテクチャを踏襲しつつ、4 コンポーネントの責務・依存関係・データフローを整理する。
**Control Plane と Sandbox Plane を分離**し、Sandbox から Control Plane・MariaDB・Kubernetes API・
他 Runtime へ直接アクセスさせない。

```text
 ai-scheduler / 利用者
      │  HTTPS（認証は後続フェーズ。第1MSは無認証・ClusterIPのみ）
      ▼
┌────────── Control Plane（namespace: platform-exkes-{stg,main}）──────────────┐
│ exkes-api（Ktor 3.x / Netty）                                                 │
│  ├─ Workspace CRUD（/v1/workspaces）/ Runtime・Execution API（第2〜3MS）        │
│  ├─ desired state のみを書く（CREATING 行の insert / DELETING への遷移）       │
│  ├─▶ MariaDB（mariadb-operator、アプリ専用、Source of Truth）                  │
│  └─▶ Kubernetes API は呼ばない（automountServiceAccountToken: false）         │
│                                                                               │
│ exkes-controller: Desired State(DB行) ⇔ K8s 実状態の reconciliation           │
│  … PVC(ws-<id>) 作成/削除・Runtime Pod 作成/削除・NetworkPolicy・Resource 制限・│
│    一時 Secret・TTL 監視・障害検出/再作成                                      │
│  … **Kubernetes 権限を持つのはこのプロセスだけ**（issue #2「Controller RBAC」）│
│ exkes-terminal-gateway（第5MS〜、issue #2 対象外・スケルトンのまま）:          │
│  WebSocket relay / session validation / session TTL / audit                   │
│ Ceph RGW（S3、第4MS〜）: Execution log / Artifact                              │
└──────────────────────────────┬────────────────────────────────────────────────┘
                               │ 制御は常に Control Plane → Sandbox 方向
                               ▼
┌────────── Sandbox Plane（namespace: platform-exkes-{stg,main}-sandbox）────────┐
│ Runtime Pod（SecurityContext: non-root / drop ALL / seccomp / readOnlyRootFS）│
│  ├─ runtime-agent        … Process 管理・PTY・Execution 管理・log stream       │
│  ├─ Claude Code / shell  … Agent Provider（第5MS〜）                           │
│  └─ /workspace           … CephFS PVC マウント（Runtime 削除後も保持）         │
│ NetworkPolicy default-deny（Ingress/Egress DENY、許可リストは第3MS）           │
└────────────────────────────────────────────────────────────────────────────────┘
```

> **namespace の分離**: issue #2「Sandbox Namespace」により、Control Plane と
> Sandbox は **別 namespace**（`platform-exkes-stg` / `platform-exkes-stg-sandbox`）に
> 置く。Controller は対応する Sandbox namespace だけを操作できる。Runtime Pod が
> 実際に立ち上がる前は（第2MS）までは Pod が 0 台なので default-deny の実効はないが、
> **NetworkPolicy の定義は本 issue #2 の範囲として前倒しで用意する**（7.3 章・9 章）。

### 2.1 コンポーネントの責務と依存関係

| コンポーネント | 責務 | 依存（Kubernetes 権限） | 登場MS |
|---|---|---|---|
| `exkes-api` | Workspace/Runtime/Execution の CRUD と状態遷移の入口。**API request 内で PVC/Pod の完了を待たず、desired state だけを書いて 202 を返す**。MariaDB を唯一の Source of Truth として write する | MariaDB のみ。**Kubernetes API は呼ばない**（`automountServiceAccountToken: false`、client-java 依存なし） | **第1MS** |
| `exkes-controller` | Desired State（DB行）と K8s 実状態の reconciliation。PVC / Runtime Pod / NetworkPolicy / Secret / TTL 監視 | MariaDB + Kubernetes API。**PVC / Pod / Secret / Service / NetworkPolicy のみ**（issue #2「Controller RBAC」） | **第1MS（Workspace reconciliation のみ。他は第2MS〜）** |
| `exkes-terminal-gateway` | WebSocket でユーザー Terminal と Runtime を接続。Authentication / Authorization / session validation / TTL / audit / relay（Runtime に SSH Server は立てない） | MariaDB、exkes-api（session 発行元）、Runtime Pod。**issue #2 対象外**（スケルトンのまま） | 第5MS |
| `runtime-agent` | Runtime Pod 内で Process/PTY/Execution 管理、log stream、workspace 概念 API（`exec` / `startExecution` / `stopExecution` / `openTerminal` / `getExecutionStatus` / `streamLogs`） | **Control Plane に依存しない**（受信のみ。JSON over HTTP/WebSocket）。**issue #2 対象外**（スケルトンのまま） | 第3MS |

### 2.2 データフロー（Workspace 作成）

```text
client ──POST /v1/workspaces──▶ exkes-api
                                 │ 1. workspaces 行を status=CREATING で insert（commit）
                                 │ 2. 202 Accepted を即時返す（同期待ちはしない）
                                 ▼
              exkes-controller / WorkspaceReconciler（数秒周期のループ）
                                 │ 3. status=CREATING の行を拾う
                                 │ 4. WorkspaceProvisioner が PVC(ws-<id>) を create
                                 │ 5. PVC が Bound なら status=READY
                                 │    例外は maxAttempts 回で ERROR + error_message
                                 │    例外なしで Bound にならないまま
                                 │    creatingTimeoutSeconds 経過で ERROR
                                 ▼
                              MariaDB
```

初版は 3〜5 が `exkes-api` 内のループだった。issue #2 の「`exkes-api` に Kubernetes
作成権限を与えない」決定に伴い、**reconciler と provisioner は `exkes-controller` に
移っている**。Runtime 作成以降（第2MS〜）も同じパターンで、controller 側の
reconciliation の対象が増えていく。Runtime が破棄・再作成されても
`workspaces.pvc_name` を見て既存 PVC を再 attach する。

---

## 3. リポジトリ構成

Gradle マルチモジュール monorepo。モジュールは「制御平面のデプロイ単位」に対応させる
（`runtime-agent` は Sandbox 側のため **`exkes-common` に依存させない**）。

```text
exkes/
├── settings.gradle.kts              # rootProject.name = "exkes"、include 4+1モジュール
├── build.gradle.kts                 # subprojects 共通（kotlin jvm / serialization / リポジトリ）
├── gradle.properties / gradlew / gradlew.bat
├── gradle/
│   ├── libs.versions.toml           # 依存バージョンの一元管理（第4章）
│   └── wrapper/gradle-wrapper.properties   # Gradle 8.14
├── exkes-common/                    # shared: 制御平面モジュール共通（**Kubernetes 非依存**）
│   └── src/main/kotlin/net/kigawa/exkes/common/
│       ├── config/ExkesConfig.kt        # HOCON 読込（EXKES_<SECTION>_<KEY> 上書き対応）
│       │                                #   ※ database / workspace のみ。kubernetes・reconciler は
│       │                                #     exkes-controller 側の config が読む
│       ├── db/Database.kt               # HikariCP + Flyway migrate + Exposed connect
│       ├── db/WorkspacesTable.kt        # Exposed Table 定義
│       ├── db/WorkspaceRepository.kt    # CRUD / 状態遷移（楽観的バージョン考慮）
│       └── domain/Workspace.kt          # data class + WorkspaceStatus enum
│   ├── src/main/resources/db/migration/
│   │   └── V1__workspaces.sql           # Flyway migration（このモジュールに集約）
│   └── src/test/kotlin/...              # MigrationH2Test / WorkspaceRepositoryTest
├── exkes-api/                       # HTTP 入口（Kubernetes 権限なし）
│   └── src/main/kotlin/net/kigawa/exkes/api/
│       ├── Application.kt               # fun Application.module()（手動 DI）
│       ├── routes/HealthRoutes.kt       # GET /health
│       ├── routes/ErrorPages.kt         # StatusPages（共通エラー形式）
│       ├── routes/WorkspaceRoutes.kt    # /v1/workspaces
│       ├── routes/WorkspaceDtos.kt      # @Serializable DTO
│       └── workspace/WorkspaceService.kt # desired state のみ書く（Dispatchers.IO へ逃がす）
│   ├── src/main/resources/application.conf / logback.xml
│   └── src/test/kotlin/...              # H2(MODE=MySQL) + testApplication（API 契約）
├── exkes-controller/                # **Kubernetes 権限を持つ唯一のプロセス**
│   └── src/main/kotlin/net/kigawa/exkes/controller/
│       ├── Application.kt               # DB connect → migrate → reconciler 開始 → /health
│       ├── config/ControllerConfig.kt   # exkes.kubernetes.* / exkes.reconciler.* の読込
│       ├── k8s/KubernetesClientFactory.kt         # client-java の in-cluster 生成ヘルパ
│       └── workspace/
│           ├── WorkspaceProvisioner.kt            # interface（PvcState / resolvePvcName も）
│           ├── KubernetesWorkspaceProvisioner.kt # client-java 実装（PVC 作成/削除）
│           └── WorkspaceReconciler.kt             # CREATING/DELETING 行の処理ループ
│   ├── src/main/resources/application.conf / logback.xml
│   └── src/test/kotlin/...              # reconciler の状態機械テスト（fake provisioner）
├── exkes-terminal-gateway/          # スケルトンのみ。**issue #2 対象外**（第5MS）
├── runtime-agent/                   # スケルトンのみ。**issue #2 対象外**（第3MS、common 非依存）
├── Dockerfile                       # ARG MODULE で各モジュール共用（installDist 方式）
├── docker-compose.yml               # ローカル開発用 mariadb:11 + exkes-api + exkes-controller
├── .github/workflows/
│   ├── deploy-stg.yml               # main push → test → build/push → platform/exkes/stg 更新
│   └── deploy-prod.yml              # workflow_dispatch(main 限定) → stg の image を main へ転記
├── .gitignore / .dockerignore
├── README.md / CLAUDE.md
└── docs/
    ├── issue-1-body.md              # issue #1 全文
    ├── issue-2-body.md              # issue #2 全文（デプロイ方針の基準）
    └── implementation-plan.md       # 本ドキュメント
```

> 初版は `k8s/KubernetesClientFactory.kt` を `exkes-common` に置き、`exkes-api` 内に
> `WorkspaceProvisioner` / `KubernetesWorkspaceProvisioner` / `WorkspaceReconciler` を
> 置いていた。issue #2 の「`exkes-api`、`terminal-gateway`、Runtime Pod には Kubernetes
> Runtime 作成権限を与えない」決定に伴い、**Kubernetes 関連のコードと client-java
> 依存を `exkes-common` から完全に外し `exkes-controller` へ移した**。

### 3.1 モジュール責務

| モジュール | 責務 | 依存先 | mainClass | CI image |
|---|---|---|---|---|
| `exkes-common` | domain / DB (Exposed + Flyway) / 設定。**client-java 非依存** | （外部依存のみ） | なし（ライブラリ） | なし |
| `exkes-api` | REST API。desired state（CREATING 行 / DELETING 遷移）だけを書く。**Kubernetes API を呼ばない** | `exkes-common` | `io.ktor.server.netty.EngineMain` | `private/exkes-api:main-<sha>` |
| `exkes-controller` | reconciliation ループ。**PVC / Pod / Secret / Service / NetworkPolicy の権限のみ**（issue #2） | `exkes-common` + client-java | 同上 | `private/exkes-controller:main-<sha>` |
| `exkes-terminal-gateway` | WebSocket relay（第5MS〜）。issue #2 対象外 | `exkes-common` | 同上 | **なし**（issue #2 対象外） |
| `runtime-agent` | Sandbox 内 Process/PTY/Execution 管理（第3MS〜）。issue #2 対象外 | **common 非依存**（JSON のみ） | 同上 | **なし**（issue #2 対象外） |

### 3.2 設計メモ

- **DI はフレームワーク不使用**。`Application.module()` 内で repository → service
  （api）/ repository → provisioner → reconciler（controller）を順に new して
  クロージャ/引数で渡す（`kigawa-net` の慣行。ai-scheduler と同型）。
- **Flyway migration は `exkes-common` に置き、起動時 migrate は `exkes-api` と
  `exkes-controller` の両方が実行する**。初版は「同時 migrate による衝突を避ける」
  ため api だけに限定していたが、issue #2 で 2 つの Deployment が独立して起動 /
  再起動する構成になり、**先に立ち上がった側が動けるように migrate を両方で行う**ことに
  した。Flyway は `flyway_schema_history` で run を 직列化するので衝突は起きにくい。
  もし将来これが問題になった場合は「片方から削除」ではなく専用 migration job を用意する。
- **ブロッキング JDBC は Netty のイベントループで走らせない**。`WorkspaceService` の
  公開メソッドは全て `suspend` で、内部で `withContext(Dispatchers.IO)` する。
  Exposed の `transaction {}` はスレッドをブロックするため、これが無いと 1 リクエストで
  同じイベントループの接続が全部止まる。
- **モジュール追加の規約**: 新モジュールは `settings.gradle.kts` に include +
  `Dockerfile` の `MODULE` 引数でビルド可能にする。issue #2 の対象に含まれる場合は
  さらに `deploy-stg.yml` の matrix と `MODULES`、`deploy-prod.yml` の `MODULES` にも
  追加する（3 箇所の同期が必須）。

---

## 4. 技術スタックとバージョン

根拠は `kigawa-net/ai-scheduler`（同一スタック: Ktor + Exposed + Flyway + H2）と
`kigawa-net/keruta` の実物（`buildSrc/src/main/kotlin/Version.kt`）を参照。
Renovate は有効（kigawa-net org 標準）なので、以降の更新は自動 PR に任せる。

| 区分 | 選定 | バージョン | 根拠（実物） |
|---|---|---|---|
| 言語 | Kotlin (JVM) | **2.1.21** | ai-scheduler `build.gradle.kts` |
| ビルド | Gradle wrapper | **8.14** | ai-scheduler `gradle-wrapper.properties` |
| Java | toolchain / 実行イメージ | **21** (temurin) | ai-scheduler `Dockerfile`（`gradle:jdk21` → `eclipse-temurin:21-jre-jammy`） |
| サーバー | Ktor 3.x (Netty) + `io.ktor.plugin` | **3.1.3** | ai-scheduler（keruta は 3.5.0 の使用実績あり。Renovate で追随） |
| シリアライズ | kotlinx.serialization（Ktor plugin 経由） | Ktor 同伴 | ai-scheduler |
| DI | `Application.module()` 内の手動生成 | - | kigawa-net 慣行（フレームワーク不使用） |
| DB | MariaDB（本番/ローカル）、**H2 MySQL mode（テスト）** | mariadb:11 / H2 2.3.232 | ai-scheduler `docker-compose.yml` / test |
| ORM | Exposed (`exposed-core/jdbc/java-time`) | **0.56.0** | ai-scheduler（keruta は 1.5.0。1.x 移行は Renovate 時に API 差分を確認） |
| 接続プール | HikariCP | 6.2.1 | ai-scheduler |
| JDBC driver | mariadb-java-client | 3.5.1 | ai-scheduler |
| Migration | **Flyway** (`flyway-core` + `flyway-mysql`) | **10.20.1** | ai-scheduler。パス: `src/main/resources/db/migration/V{n}__name.sql`、起動時 `migrate()` |
| K8s クライアント | 公式 `io.kubernetes:client-java`（fabric8 は組織内未使用） | **27.0.0** | keruta `Version.KUBERNETES_CLIENT` |
| 認証（後続） | java-jwt / jwks-rsa（Keycloak OIDC + JWKS 検証） | 4.6.1 / 0.24.1 | keruta `Version`。**第1マイルストーンでは未実装** |
| ログ | logback console + logstash-logback-encoder | 1.5.18 / 8.0 | ai-scheduler（`logback.xml` は `LogstashEncoder` + MDC） |
| Metrics（第6MS） | micrometer-registry-prometheus + ktor-server-metrics-micrometer | 1.14.2 | ai-scheduler |
| テスト | JUnit5（kotlin-test-junit5）+ `ktor-server-test-host` | Ktor 同伴 | ai-scheduler。**Kotest / Testcontainers は不使用** |
| 設定 | HOCON `application.conf` + `${?ENV}` | Typesafe Config（Ktor同梱） | ai-scheduler。env 名は `EXKES_<SECTION>_<KEY>` |
| コンテナビルド | `installDist`（**shadowJar 不可**: Flyway のクラスパス走査と相性が悪い） | - | ai-scheduler `Dockerfile` のコメントと実方針 |
| CI runner | `ubuntu-latest` | - | kigawa-net org に in-cluster runner なし |

---

## 5. データモデルと DB スキーマ

### 5.1 テーブル全体像（issue #1 の候補に対する方針）

| テーブル | 内容 | 作成フェーズ |
|---|---|---|
| `workspaces` | Workspace metadata / status / PVC 参照 | **第1マイルストーン（V1）** |
| `runtime_templates` | Runtime の雛形（image/resources/default TTL） | 第2MS（V2） |
| `runtimes` | Runtime 状態・TTL・workspace attach・pod 名 | 第2MS（V2） |
| `executions` | Execution 状態・type・ログ/成果物の object key | 第3MS（V3） |
| `terminal_sessions` | Terminal session 発行・TTL | 第5MS（V6） |
| `credentials` | 一時 credential の metadata（本体は K8s Secret / BWS） | 第4MS（V4） |
| `artifacts` | Artifact metadata（size / content type / checksum / object key） | 第4MS（V5） |
| `audit_logs` | 監査ログ | 第5MS（V7） |

Runtime Pod や Kubernetes Resource 自体は Source of Truth としない（DB 行が常に正）。
DB の `id` は **`VARCHAR(36)`（UUID 文字列）** とする（ai-scheduler の文字列 ID 方針に合わせ、
H2/MariaDB 両対応で `BINARY(16)` の差異を避ける）。

### 5.2 第1マイルストーンで作る `workspaces` テーブル DDL

`exkes-common/src/main/resources/db/migration/V1__workspaces.sql`

```sql
-- workspaces: Runtime より長いライフサイクルを持つ永続 filesystem の metadata。
-- Source of Truth は MariaDB。PVC はこの行を正として作成/削除する。

CREATE TABLE workspaces (
    id            VARCHAR(36)  NOT NULL,            -- UUID (java.util.UUID#toString)
    name          VARCHAR(128) NOT NULL,            -- 利用者指定の一意な名前
    status        VARCHAR(32)  NOT NULL,            -- CREATING/READY/ARCHIVED/DELETING/DELETED/ERROR
    storage_class VARCHAR(64)  NOT NULL DEFAULT 'rook-cephfs',
    storage_size  BIGINT       NOT NULL DEFAULT 1073741824,  -- bytes（既定 1Gi）
    pvc_name      VARCHAR(128) NULL,                -- 例: ws-<id>（CREATING 中は NULL）
    error_message TEXT         NULL,                -- status=ERROR の理由
    labels_json   TEXT         NULL,                -- 任意ラベル（kotlinx.serialization で JSON 化）
    archived_at   TIMESTAMP    NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_workspaces_name UNIQUE (name)
);

CREATE INDEX idx_workspaces_status ON workspaces (status);
```

実装上の注意:

- **`ON UPDATE CURRENT_TIMESTAMP` や `ENGINE=` / `CHARSET=` 句は書かない**。テストが
  H2 (`MODE=MySQL`) で Flyway を実行し DDL 互換性を検証するため。文字コードは
  mariadb-operator の `Database` CR（`utf8mb4` / `utf8mb4_unicode_ci`）側で担保する。
  `updated_at` はアプリ側（`WorkspaceRepository`）で更新する。
- Flyway 設定は `validateMigrationNaming(true)` + `classpath:db/migration`
  （ai-scheduler `db/Database.kt` と同型）。
- Exposed 定義は `WorkspacesTable`（`object WorkspacesTable : Table("workspaces")`）。
  `status` は `varchar("status", 32)` に `WorkspaceStatus` enum 名を格納。

---

## 6. API 設計

issue #1 の API 初期案のうち、**第1マイルストーンで実装するのは `/v1/workspaces` 系**。
（issue では `/workspaces` 表記だが、実装では `/v1` プレフィックスを付ける。ai-scheduler の
`/v1/jobs` と同じ。）

### 6.1 第1マイルストーンのルート

| Method / Path | 応答 | 説明 |
|---|---|---|
| `POST /v1/workspaces` | `202 Accepted` | Workspace 作成（`status=CREATING` で即時応答） |
| `GET /v1/workspaces` | `200 OK` | 一覧（issue 初期案に追加。`?status=&limit=&offset=`） |
| `GET /v1/workspaces/{id}` | `200` / `404` | 単体取得 |
| `DELETE /v1/workspaces/{id}` | `202 Accepted` / `404` | 削除要求（`status=DELETING` へ遷移、PVC 削除は非同期） |
| `GET /health` | `200 OK` | liveness/readiness（issue 外、Deployment probe 用） |

後続フェーズで追加（issue #1 初期案の残り）:
`POST /v1/workspaces/{id}/checkout`（第4MS）、`/v1/runtimes` 系（第2MS）、
`/v1/executions` 系（第3MS）、`POST /v1/runtimes/{id}/terminal-sessions`（第5MS）、
`/v1/artifacts` 系（第4MS）。

### 6.2 リクエスト / レスポンス JSON

```jsonc
// POST /v1/workspaces
// Request
{
  "name": "ai-scheduler-fix-123",
  "storageSizeBytes": 1073741824,        // 省略時は 1Gi
  "labels": { "project": "ai-scheduler" } // 省略可
}

// 202 Accepted（PVC 完了を待たない。status は CREATING）
{
  "id": "3f0f3b1a-8e52-4a6e-9a41-1c1a2b3c4d5e",
  "name": "ai-scheduler-fix-123",
  "status": "CREATING",
  "storageClass": "rook-cephfs",
  "storageSizeBytes": 1073741824,
  "pvcName": null,
  "errorMessage": null,
  "labels": { "project": "ai-scheduler" },
  "createdAt": "2026-10-01T00:00:00Z",
  "updatedAt": "2026-10-01T00:00:00Z"
}

// GET /v1/workspaces  → 200
{ "items": [ /* Workspace JSON */ ], "total": 12, "limit": 50, "offset": 0 }

// エラー共通形式
{ "code": "NOT_FOUND", "message": "workspace not found: <id>" }
//   code: NOT_FOUND | CONFLICT | VALIDATION_ERROR | INTERNAL
//   404: 存在しない id / 削除済み(DELETED)
//   409: name 重複（uk_workspaces_name 違反）
//   400: name 不正（空/128超/不許可文字）、storageSizeBytes 不正
//   500: provisioning 初期化失敗など
```

- JSON は kotlinx.serialization（`@Serializable` の DTO を `exkes-api` の `routes` 配下に配置）。
- `DELETE` は `202` を返し、`DELETING → DELETED` は reconciler が非同期で進める。
  `ERROR` / `READY` / `ARCHIVED` からの削除は許可。`CREATING` 中の削除も受付可（reconciler が
  PVC 作成完了後に削除を続行する）。

---

## 7. Workspace 状態機械と PVC provisioning 方針

### 7.1 状態機械

```text
（存在しない）
      │ POST /v1/workspaces
      ▼
  CREATING ──── provisioning 失敗 ──────────────► ERROR
      │                                          │  │
      │ PVC Bound 確認（reconciler）             │  │ DELETE /v1/workspaces/{id}
      ▼                                          │  ▼
    READY ──── DELETE ──────────────────────► DELETING ── PVC 削除完了 ──► DELETED（最終）
      │                                          ▲
      │ archive（将来の API、第2MS以降で検討）   │
      ▼                                          │
  ARCHIVED ─── DELETE ───────────────────────────┘
```

| From | イベント | To | 実行主体 |
|---|---|---|---|
| （なし） | `POST /v1/workspaces` | `CREATING` | exkes-api（同期 insert） |
| `CREATING` | PVC `Bound` 確認 | `READY` | WorkspaceReconciler（**`exkes-controller`**。issue #2 により api には無い） |
| `CREATING` | provisioning 失敗（再試行上限） | `ERROR` | 同上（`error_message` を記録） |
| `CREATING` | `created_at` から `creatingTimeoutSeconds` 経過 | `ERROR` | 同上（exception 無しで `Bound` にならない滞留の解消） |
| `ERROR` | `DELETE /v1/workspaces/{id}` | `DELETING` | exkes-api |
| `READY` / `ARCHIVED` | `DELETE /v1/workspaces/{id}` | `DELETING` | exkes-api |
| `DELETING` | PVC 削除完了（または PVC なし） | `DELETED` | WorkspaceReconciler |
| `READY` | archive（将来） | `ARCHIVED` | 後続 |

- `ARCHIVED` は **PVC を保持**したまま Runtime から attach 不可とする状態（将来の archive API 用）。
  実データ削除は `DELETING` 経由のみ。
- 遷移は必ず `UPDATE ... WHERE id=? AND status=?`（現在の status 条件付き）で行い、
  競合時に 0 行更新なら再読込して再判断する（reconciler と API の二重実行に耐える）。

### 7.2 CephFS PVC provisioning の実装方針（issue #2 反映）

**判断: Kubernetes API を呼ぶのは `exkes-controller` だけ**。`exkes-api` は
desired state（DB の `CREATING` 行の insert / `DELETING` への遷移）だけを書く。
「呼ぶ実体」は `WorkspaceProvisioner`（インターフェース）+ `WorkspaceReconciler`
（DB の `CREATING`/`DELETING` 行を数秒周期で拾うループ）で、**この2クラスは
`exkes-controller` モジュール内に置く**（初版は `exkes-api` に置いていた。第3章）。

- **プロビジョニング内容**（`exkes-controller` の `KubernetesWorkspaceProvisioner`）:
  - PVC `ws-<workspaceId>` を作成:
    - `storageClassName: rook-cephfs`、`accessModes: [ReadWriteMany]`（複数 Runtime 同時 mount 可）、
      `resources.requests.storage: <storageSize>Gi`
    - label: `app.kubernetes.io/managed-by=exkes`、`exkes.workspace-id=<id>`
  - status polling で `Bound` を確認（CephFS の provision は通常即時〜数秒）
  - 削除時: PVC を `PropagationPolicy=Foreground` で delete（Runtime 側 mount 解除を待つ）
- **起動順序**（`exkes-controller` の `Application.kt`）: DB connect → Flyway migrate →
  `KubernetesClientFactory`（in-cluster `Config` の生成）→ reconciler ループ開始 → `/health` 待ち受け。
  K8s 接続失敗でプロセスを落とさず、`/health` は 200 を返す（ArgoCD の readiness を通す）。

**理由:**

1. **issue #2 の明示的な決定。**「`exkes-api`、`exkes-terminal-gateway`、Runtime Pod には
   Kubernetes Runtime 作成権限を与えない。Controller のみに付与する」。API に
   `automountServiceAccountToken: false` を付けると **Pod 内の任何人（外部からの
   ライブラリ経由を含む）がトークンを取得できない**ため、api からの K8s 呼び出しは
   構造的に不可能になる。
2. **RBAC 面の最小化。** 権限を持つ ServiceAccount が1つだけになり、
   `platform` リポジトリに Role/RoleBinding を書くのも1組で済む。
3. **移設コストを実質ゼロにした。** `WorkspaceReconciler` は「DB を読む → provisioner を呼ぶ →
   DB を書く」だけの構造で、provisioner をインターフェースにしていたので、
   パッケージ移動と `build.gradle.kts` の client-java 追加だけで完了した。
4. **拒否した代替案**:
   - *API リクエスト内で PVC 完了を同期待つ* → issue #1「API request 内で完了を待たず、
     非同期 state transition」に反する。
   - *controller は第2MSまでスケルトンのまま* → issue #2 は第1MSから controller を
     2つ目 Deployment としてデプロイする前提なので、この分離だと初版から
     「api が K8s を呼ぶ」状態になり RBAC をあとから剥がすことになる（移行の順序が逆になる）。

**副作用（RBAC）**: `exkes-controller` の ServiceAccount に
`persistentvolumeclaims` の `create/get/list/watch/delete` を許可する Role/RoleBinding を
platform マニフェストに新規作成する（`platform` リポジトリに Role/RoleBinding の実例はなく新規）。
`exkes-api` には ServiceAccount を**与えない**（Deployment の `serviceAccountName` を
指定せず、`automountServiceAccountToken: false` で token も作らない）。

**残した論点（issue #2 で決まっていないもの）:**

- **`replicas: 1` 前提**。`WorkspaceReconciler` はプロセス内メモリで試行回数を
  持っているので、replicas を増やすと同じ workspace を複数 Pod が同時に provision する。
  リーダー選出（Lease / k8s leader election）は issue #2 の範囲外として未議論。
  現状は `replicas: 1` に固定し、第2MS の Runtime で水平スケールする段階引入を討論する。
- **`error_message` に K8s API のレスポンス本文が入る**。`ERROR` 遷移時に
  例外メッセージ（provisioner が K8s の `Status` オブジェクト由来の文字列を含む）が
  DB に入る。API の 500 応答は `internal error` に固定している（漏洩先は無認証の API）ので
  クライアント直への漏洩は無いが、DB アクセス権者は読める。RBAC の events 経由で
  公開する形への変更は第2MS。
- **Flyway を両モジュールで実行する**（第3.2章）。独立 Deployment なので
  どちらかが先に立ち上がっても動くようにしているが、両者が同時に migrate を
  走らせた場合の競合リスクは未検証。

### 7.3 NetworkPolicy と Sandbox Namespace（issue #2 反映）

**issue #2 の範囲として、Sandbox Namespace と NetworkPolicy の定義を issue #2 に含める。**
初版「第1MSでは NetworkPolicy を書かない」という判断を撤回した。

- **Namespace**（issue #2「Sandbox Namespace」）: `platform-exkes-{stg,main}-sandbox`。
  Control Plane とは**別 namespace**に置き、`exkes-controller` は
  「control plane namespace 内の PVC/Runtime Pod + sandbox namespace 内の
  Runtime Pod 配下リソース」のみ操作できる Role を与える（第9章）。
- **NetworkPolicy**（issue #2）: sandbox namespace に `default-deny`（ingress/egress とも
  全 DENY）を置き、`exkes.runtime: "true"` ラベルの Pod に対する egress 許可リストを
  後続フェーズで積み上げる。**第1MSには Sandbox Pod が存在しないので実効は無い**が、
  「Pod が入れば既定で deny」という状態を先に作っておくことで、第2MS の Runtime 実装時に
  「許可ルールを書き忘れた」状態を作らない。
- **最小の許可ルール**: `lipl` 等の既存 manifest は Deployment + Service の multi-doc で、
  NetworkPolicy の *egress* 許可ルールを個別に書いた実例が無い。**Kubernetes API への
  egress と DNS への egress だけは定義する**（これがないと Sandbox から名前解決すら
  できない）。外部 API（GitHub / Anthropic 等）の許可リストは第3MS。
- **実例が無い**: `platform` リポジトリに NetworkPolicy の実例が無いため、
  1 リソース 1 ファイルの新規パターンになる。`kubectl apply --dry-run=client` での検証が必須。

---

## 8. CI/CD

参考実物: `kigawa-net/lipl` の platform manifest（`harbor.kigawa.net/private/lipl-api:main-<sha>`）、
`kigawa-net/ai-scheduler/.github/workflows/cd.yml`（test → docker build&push →
`yq` で manifest リポジトリの image 更新 → commit/push）、WIF 版
（`kigawa-net/kinfra/.github/actions/kigawa-net-app-token@dev` で短命トークン取得）。

### 8.1 フロー

issue #2 の「デプロイフロー」に従い、**workflow は 2 本**に分割する
（`cd.yml` は廃止）。

```text
 PR (pull_request)  ──▶ deploy-stg.yml: test だけ（build / push / platform コミットなし）
 main push         ──▶ deploy-stg.yml: test → build×2 → push → exkes/stg/ の image 更新 → platform main へ commit
                                                      │
                                                      ▼ Argo CD が platform-exkes-stg へ自動同期
 main 上で手動 (workflow_dispatch)
                   ──▶ deploy-prod.yml: guard(main 限定) → exkes/stg/ の image を exkes/main/ へ転記 → platform main へ commit
                                                      │
                                                      ▼ Argo CD が platform-exkes-main へ自動同期
```

- **環境は stg / main の 2 つだけ**。dev / PR preview / ApplicationSet は作らない。
- **image タグは `main-<github.sha>` 固定**。stg / prod で**同一タグ**を使う。
  `develop-<sha>` や `:latest` は使わない（prod が stg と別バイナリで動く事故を防ぐ。
  8.2 のガードで `:latest` 等は昇格時に必ず落ちる）。
- **`pull_request` では image を push しない**（`build` ジョブに
  `if: github.event_name == 'push' && github.ref == 'refs/heads/main'` を付ける）。
- **`main` push では `yq` で `./exkes/stg/<module>.yaml` の image を更新し platform `main` へ
  commit/push** → ArgoCD が stg へ自動同期。
- **prod は再ビルドしない。** `deploy-prod.yml` は build/push ステップを持たず、
  stg の manifest から image を `yq` で**読み出して** main へ転記するだけ
  （issue #2「stg で使用中の image をそのまま prod に昇格させる」）。
- **Harbor**: `harbor.kigawa.net/private/exkes-<module>:<tag>`、
  ログインは `secrets.HARBOR_PASS`（robot$ kigawa-net）。**タグはモジュール別**
  （`private/exkes-api:main-<sha>` / `private/exkes-controller:main-<sha>`）。
- **push 対象は `exkes-api` と `exkes-controller` の 2 つ**。
  `exkes-terminal-gateway` / `runtime-agent` は issue #2「対象外」。
- **runner** は `ubuntu-latest`（org に in-cluster runner なし）。
- branch protection: PR 必須・Approvals 0・main 直 push 不可（platform/github の Terraform 管理下）。

### 8.2 `.github/workflows/deploy-stg.yml` / `deploy-prod.yml`

実物は `.github/workflows/deploy-stg.yml` と `.github/workflows/deploy-prod.yml`
（actionlint 1.7.7 + shellcheck 0.10.0 で指摘 0、yq 式はローカル fixture で実測検証済み）。
ここでは**設計上の要点**だけを記す。

#### 8.2.1 `deploy-stg.yml`

```yaml
on:
  push: { branches: [main] }
  pull_request: { branches: [main] }
env:
  HARBOR_REGISTRY: harbor.kigawa.net
  IMAGE_NAMESPACE: private          # issue #2「Container Registry」: harbor.kigawa.net/private/
  HARBOR_USER: robot$kigawa-net
  MANIFEST_REPOSITORY: kigawa-net/platform
  MANIFEST_DIR: exkes/stg
  MODULES: exkes-api exkes-controller   # build の matrix と必ず同じ内容にする
jobs:
  test:  ...                          # PR でも必ず走る（job 側の if は不要）
  build:
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    strategy: { matrix: { module: [exkes-api, exkes-controller] } }
    outputs: { tag: ${{ steps.tag.outputs.tag } }     # prod へ受け渡す
  deploy-stg:
    needs: build
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    permissions: { id-token: write }   # WIF（kigawa-net-app-token@dev）に必須
```

要点:

- `test` ジョブは `if` を付けない（PR でも必ず走る）。`build` / `deploy-stg` だけが push 限定。
- **マトリクスは `build` と `MODULES` の 2 箇所に書く**。両者がずれると
  「stg manifest に無いモジュールを push する」等の取りこぼし・取り残しが生まれる。
  モジュール追加時は 3 箇所（`deploy-stg.yml` の matrix、同ファイルの `MODULES`、
  `deploy-prod.yml` の `MODULES`）を同期する（第3.2章の規約）。
- image 更新ループ（`for module in $MODULES`）:
  1. `manifest="./${MANIFEST_DIR}/${module}.yaml"`（`exkes-` が二重にならないよう module 名そのまま）
  2. `export image="harbor.kigawa.net/private/${module}:${TAG}"` を**ループ内で毎回 export する**
     （yq の `strenv()` はプロセス「環境」を読む。shell 変数だと空文字で上書きされる）
  3. ファイルが無ければ `::error::` を出して `continue`（platform の manifest 追加が
     先行していない状態で stg デプロイが落ちて高考できないように）
  4. `yq -r '.kind'` が `Deployment` でなければ **hard fail**（multi-doc を混ぜると
     Argo CD が `unknown field "spec.template"` で弾く事故を先回りする）
  5. `yq -i '(select(.kind == "Deployment") | .spec.template.spec.containers[0].image) = strenv(image)'`
  6. **読み戻し検証**（`updated != image` なら fail）
  7. `git add` 後に、**意図しないファイル（exkes-api-svc.yaml / rbac.yaml 等）が混ざっていないか**
     `git diff --cached --name-only` で検証する。判定は**完全一致**で行う
     （`git add` / `git diff` は先頭の `./` を落とすので、記録する側も
     `${path#./}` に揃えておく）
- 変更が無ければ `git commit` しない。**これは `exit 0` だけでは足りない**:
  `run` ステップを抜けると次の `push` ステップが実行され、`git commit` が
  `nothing to commit` で失敗してジョブが赤になる。更新ステップで
  `changed` を `GITHUB_OUTPUT` に出力し、`push` 側に
  `if: steps.<id>.outputs.changed == 'true'` を付ける。

#### 8.2.2 `deploy-prod.yml`

```yaml
on:
  workflow_dispatch: {}            # 手動のみ
env:
  SOURCE_DIR: exkes/stg
  TARGET_DIR: exkes/main
  MODULES: exkes-api exkes-controller
jobs:
  guard:
    # workflow_dispatch は任意ブランチから実行できるので、main 以外を明示的に落とす
    # （job の if にすると無言で skip されて察觉しづらい）
  promote:
    needs: guard
    if: github.ref == 'refs/heads/main'
    permissions: { id-token: write }
    # build / push は一切無い。stg の image を読み、main へ転記するだけ。
```

要点:

- **build/push ステップは存在しない**（再ビルドしないこと自体が issue #2 の要求）。
- `guard` ジョブで `refs/heads/main` 以外を `::error::` + `exit 1` で弾く。
- 昇格元 `exkes/stg/<module>.yaml` の `kind` が `Deployment` でない、
  image が取得できない（空 / `null`）、`main` 側の manifest が無い → いずれも**明確に fail**
  （空 image を prod に書き込まない）。
- **昇格元 image の正規表現ガード**: `harbor.kigawa.net/private/<module>:main-<40 hex>` に
  一致しなければ fail（`:latest` や別 project の image が入っていた場合に、
  prod が stg と別バイナリで動く事故をその場で防ぐ）。
- 書き込み後は読み戻し検証と「意図しないファイル混入チェック」を stg 側と同じ做法で行う。
- **冪等**: prod が既に stg と同一 image を参照している場合は `changed=false` にして
  `push` ステップを飛ばす。同じ昇格を再実行してもジョブは緑のまま。

### 8.3 stg / main の挙動差分

| 項目 | stg（main マージ） | main（prod、手動昇格） |
|---|---|---|
| namespace | `platform-exkes-stg` | `platform-exkes-main` |
| image タグ | `main-<sha>`（CI が `yq` で platform に commit） | **stg と同一の `main-<sha>`**（再ビルドしない） |
| imagePullPolicy | `IfNotPresent` | `IfNotPresent` |
| CI の platform コミット | あり | あり（stg の値を転記） |
| ランタイム | main push | `workflow_dispatch`（main ブランチ限定） |
| DB | `platform-exkes-stg` 内に常設 | `platform-exkes-main` 内に常設 |
| Workspace PVC | 永続（CephFS） | 永続（CephFS） |
| RBAC | `exkes-controller` の SA のみ | `exkes-controller` の SA のみ（`exkes-api` は token 無し） |

> **PR 環境の行が無い**のは意図的で、issue #2 の「環境は stg と main の 2 つ」に対する結果。
> 初版の dev / PR preview / ApplicationSet 設計は削除した。

---

## 9. マニフェスト設計（`kigawa-net/platform` リポジトリ側）

platform リポジトリは `<service>/<env>/` のフラット構成（唯一の実例: `lipl`）。
`apps/apps-app.yml` が `apps/` を再帰同期するため、Application ファイル追加だけで登録される
（ArgoCD への手動登録は不要 = 第12章の「argocd 登録」リスクは解消済みの設計）。
AppProject `platform` は destinations `platform-*` / sourceRepos `https://github.com/kigawa-net/*`
を許可 → exkes は問題なし（検証済み）。

### 9.1 作成するファイル一覧

**1 リソース 1 ファイルのフラット構成**（issue #2「platform 側構成」）。kustomization.yaml は
使わない（`directory.recurse: true` で全ファイルを適用する `lipl` と同じ形）。
`exkes/dev/` と ApplicationSet は作らない。

```text
platform/
├── apps/
│   ├── apps-app.yml                      # 既存（変更なし）
│   ├── exkes-stg-app.yml                 # 新規: stg Application
│   └── exkes-main-app.yml                # 新規: prod Application
└── exkes/
    ├── stg/
    │   ├── ns.yaml                       # Namespace: platform-exkes-stg
    │   ├── sandbox-ns.yaml               # Namespace: platform-exkes-stg-sandbox（issue #2）
    │   ├── exkes-api.yaml                # Deployment（image は CI が更新）
    │   ├── exkes-api-svc.yaml            # Service: exkes-api
    │   ├── exkes-controller.yaml         # Deployment（image は CI が更新）
    │   ├── exkes-controller-svc.yaml     # Service: exkes-controller（probe 前提。任意）
    │   ├── rbac.yaml                     # ServiceAccount + Role + RoleBinding（controller 専用）
    │   ├── network-policy.yaml           # sandbox namespace の default-deny（issue #2）
    │   └── mariadb.yaml                  # BitwardenSecret + MariaDB + Database + User + Grant
    └── main/
        ├── ns.yaml                       # Namespace: platform-exkes-main
        ├── sandbox-ns.yaml               # Namespace: platform-exkes-main-sandbox
        ├── exkes-api.yaml                # Deployment（image は deploy-prod.yml が転記）
        ├── exkes-api-svc.yaml
        ├── exkes-controller.yaml
        ├── exkes-controller-svc.yaml
        ├── rbac.yaml
        ├── network-policy.yaml
        └── mariadb.yaml
```

> **ファイル名は `kigawa-net/platform` PR #16 の実物に合わせている**
> （`ns-sandbox.yaml` ではなく `sandbox-ns.yaml`、Service は `service.yaml` ではなく
> `<module>-svc.yaml`）。本リポジトリの CI が触るのは `exkes-api.yaml` と
> `exkes-controller.yaml` の **2 ファイルだけ**なので\Service のファイル名には
> 影響しないが、plan と manifest が食い違わないようにしておく。
> `exkes/dev/` と ApplicationSet は作らない。
> `exkes-controller-svc.yaml` は「`exkes-controller` には Service が要らない」という
> 9.4 章の判断とは別の要件（probe を Service 経由でも叩けるようにしておくため）の
> 任意ファイルで、CI は触らない。

> **CI が触るのは `exkes-api.yaml` と `exkes-controller.yaml` の 2 ファイルだけ**。
> `*-svc.yaml` / `rbac.yaml` / `ns.yaml` / `sandbox-ns.yaml` / `network-policy.yaml` /
> `mariadb.yaml` は CI から変更しない（`deploy-stg.yml` / `deploy-prod.yml` に
> 「意図しないファイル混入チェック」を入れてあるのはこれが理由）。
> **Deployment のファイル名はモジュール名そのもの**（`exkes-exkes-api.yaml` にしない）。

### 9.2 `apps/exkes-{stg,main}-app.yml`（要点）

lipl と同じ形。`directory.recurse: true`（kustomization 不要）、`project: platform`、
`automated: { prune: true, selfHeal: false }`、`syncOptions: [CreateNamespace=true]`。

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: platform-exkes-stg          # prod は platform-exkes-main
  namespace: argocd
  finalizers: [resources-finalizer.argocd.argoproj.io]
spec:
  project: platform
  source:
    repoURL: https://github.com/kigawa-net/platform.git
    targetRevision: main
    path: exkes/stg                  # prod は exkes/main
    directory:
      recurse: true                 # フラットな複数ファイルを全部適用する
  destination:
    server: https://kubernetes.default.svc
    namespace: platform-exkes-stg    # prod は platform-exkes-main
  syncPolicy:
    automated: { prune: true, selfHeal: false }
    syncOptions: [CreateNamespace=true]
```

> **sandbox namespace への Argo CD Application は作らない**。`sandbox-ns.yaml` は
> stg/main の Application の `directory.recurse` で**自動的に**作られる
> （Argo CD は宛先 namespace 以外のリソースも apply できる。namespace リソースを
> 別 Application に分ける必要は無い）。NetworkPolicy も同様。

### 9.3 `exkes/{stg,main}/mariadb.yaml`（BWS UUID はプレースホルダ）

`platform/lipl/stg/mariadb.yaml` の 5 リソース構成を踏襲する（main は `-stg` を `-main` に置換）。

```yaml
# ① BitwardenSecret: bwSecretId はプレースホルダ（デプロイ時に実 UUID へ置換。第12章#2）
apiVersion: k8s.bitwarden.com/v1
kind: BitwardenSecret
metadata: { name: mariadb-exkes-stg }
spec:
  organizationId: "a2b57f3d-6e2b-4467-b499-b31e00bfd804"
  secretName: mariadb-exkes-stg
  authToken: { secretName: bitwarden-sec, secretKey: token }
  map:
    - { bwSecretId: "<TODO-BWS-UUID-root-pass>", secretKeyName: root-pass }
    - { bwSecretId: "<TODO-BWS-UUID-pass>",      secretKeyName: pass }
---
# ② MariaDB（platform/lipl/stg/mariadb.yaml と同型。ローカル/実運用とも mariadb:11 系で統一）
apiVersion: k8s.mariadb.com/v1alpha1
kind: MariaDB
metadata: { name: mariadb-exkes-stg }
spec:
  image: mariadb:11
  port: 3306
  rootPasswordSecretKeyRef: { name: mariadb-exkes-stg, key: root-pass }
  storage: { size: 1Gi, storageClassName: rook-ceph-rbd }
  resources: { requests: { cpu: 100m, memory: 256Mi }, limits: { cpu: 500m, memory: 512Mi } }
  startupProbe: { initialDelaySeconds: 20, periodSeconds: 10, failureThreshold: 60 }
  updateStrategy: { autoUpdateDataPlane: true }
---
# ③ Database / ④ User / ⑤ Grant は lipl と完全同型（name/exkes, charset utf8mb4,
#    passwordSecretKeyRef: mariadb-exkes-stg/pass, privileges: [ALL PRIVILEGES]）
```

### 9.4 `exkes/{stg,main}/exkes-api.yaml` / `exkes-api-svc.yaml`（要点）

lipl の `lipl-api.yaml` を踏襲（probe は `/health`、env は `EXKES_*`）。
**Deployment と Service は別ファイル**（issue #2「1 リソース 1 ファイル」）。

#### `exkes-api.yaml`（Deployment）

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: exkes-api, labels: { app: exkes-api } }
spec:
  replicas: 1
  selector: { matchLabels: { app: exkes-api } }
  template:
    metadata: { labels: { app: exkes-api } }
    spec:
      # issue #2「Controller RBAC」: exkes-api に K8s 権限を与えない。
      # serviceAccountName を書かない（default SA になる）+ トークンを作らない。
      automountServiceAccountToken: false
      imagePullSecrets: [{ name: harbor-registry }]
      containers:
        - name: exkes-api
          image: harbor.kigawa.net/private/exkes-api:main-0000000000000000000000000000000000000000
          # ↑ CI（deploy-stg.yml / deploy-prod.yml）が yq で書き換える
          imagePullPolicy: IfNotPresent
          ports: [{ containerPort: 8080 }]
          env:
            - name: EXKES_DATABASE_JDBC_URL
              value: "jdbc:mariadb://mariadb-exkes-stg:3306/exkes"
            - name: EXKES_DATABASE_USER
              value: exkes
            - name: EXKES_DATABASE_PASSWORD
              valueFrom: { secretKeyRef: { name: mariadb-exkes-stg, key: pass } }
          startupProbe: { httpGet: { path: /health, port: 8080 }, initialDelaySeconds: 10,
                          periodSeconds: 5, failureThreshold: 30 }
          livenessProbe:  { httpGet: { path: /health, port: 8080 }, initialDelaySeconds: 30,
                          periodSeconds: 10, failureThreshold: 3 }
          readinessProbe: { httpGet: { path: /health, port: 8080 }, initialDelaySeconds: 10,
                          periodSeconds: 10, failureThreshold: 3 }
          resources:
            requests: { cpu: 100m, memory: 256Mi }
            limits:   { cpu: 500m, memory: 512Mi }
```

> `EXKES_KUBERNETES_*` / `EXKES_RECONCILER_*` は **`exkes-api` に設定しない**
> （issue #2 により controller 専用。第3章の決定により `exkes-api` の
> `application.conf` からも該当 section を削除済み。設定が余ると
> 起動時に `MissingPropertyException` で落ちる）。

#### `exkes-controller.yaml`（Deployment）

`exkes-api.yaml` と同型で、差分は次のとおり。

```yaml
      serviceAccountName: exkes-controller      # rbac.yaml（PVC 作成権限）
      containers:
        - name: exkes-controller
          image: harbor.kigawa.net/private/exkes-controller:main-000...   # CI が更新
          env:
            - name: EXKES_DATABASE_JDBC_URL
              value: "jdbc:mariadb://mariadb-exkes-stg:3306/exkes"
            - name: EXKES_DATABASE_USER
              value: exkes
            - name: EXKES_DATABASE_PASSWORD
              valueFrom: { secretKeyRef: { name: mariadb-exkes-stg, key: pass } }
            - name: EXKES_KUBERNETES_NAMESPACE           # ← controller 専用設定
              valueFrom: { fieldRef: { fieldPath: metadata.namespace } }
            - name: EXKES_WORKSPACE_STORAGECLASS
              value: rook-cephfs
```

> `replicas: 1` 固定（7.2 章のリーダー選出が未決のため）。
> **Sandbox namespace を config から読む設定は第1MSでは無い**（Runtime Pod を
> Sandbox に置くのが第2MSなので、その時点で `exkes.kubernetes.sandboxNamespace` /
> `EXKES_KUBERNETES_SANDBOX_NAMESPACE` を追加する）。第1MSで controller が触るのは
> 自 namespace の PVC だけ。

#### `exkes-api-svc.yaml`（Service）

`exkes-api` だけ。`exkes-controller` に Service は作らない（reconcile は DB ポーリングで
駆動されるので、外部から叩かれる面が無い。`/health` は readiness probe が直接叩く）。

```yaml
apiVersion: v1
kind: Service
metadata: { name: exkes-api }
spec:
  selector: { app: exkes-api }
  ports: [{ port: 8080, targetPort: 8080, protocol: TCP }]
  type: ClusterIP                 # 第1MSでは Ingress/DNS なし（無認証のため）
```

### 9.5 `exkes/{stg,main}/rbac.yaml`（新規。platform に実例なし）

**Kubernetes 権限を持つのは `exkes-controller` の ServiceAccount だけ**（issue #2）。
`exkes-api` には ServiceAccount を作らない。

```yaml
apiVersion: v1
kind: ServiceAccount
metadata: { name: exkes-controller }
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: { name: exkes-controller-workspace-pvc }
rules:
  # 第1MSで実際に使うのはこの 1 行だけ。platform PR #16 もこの範囲に絞っている。
  - apiGroups: [""]
    resources: ["persistentvolumeclaims"]
    verbs: ["get", "list", "watch", "create", "delete"]
  # ↓ 第2MS（Runtime）で必要になった時点で追加する。**第1MSでは付けない**
  #   - apiGroups: [""]
  #     resources: ["pods", "services", "secrets", "events"]
  #     verbs: ["create", "get", "list", "watch", "delete"]
  #   - apiGroups: ["apps"]
  #     resources: ["deployments"]
  #     verbs: ["create", "get", "list", "watch", "delete", "update", "patch"]
  #   - apiGroups: ["networking.k8s.io"]
  #     resources: ["networkpolicies"]
  #     verbs: ["create", "get", "list", "watch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: { name: exkes-controller-workspace-pvc }
roleRef: { apiGroup: rbac.authorization.k8s.io, kind: Role, name: exkes-controller-workspace-pvc }
subjects: [{ kind: ServiceAccount, name: exkes-controller }]
```

> **第1MSで実際に使うのは `persistentvolumeclaims` だけ**。`kigawa-net/platform` PR #16 は
> この方針で Role 名を `exkes-controller-workspace-pvc` にして **PVC の 1 行だけ**を
> 付与している（Runtime 系の resource はコメントで未付与にしてある）。
> コード側は `KubernetesWorkspaceProvisioner` が `persistentvolumeclaims` しか触らないので
> この範囲だけで足りる。**「まだ必要でない権限」は追加しない。**
> 将来 `watch` 系の verb や `pods` が必要になったときは、実装と同じ PR で
> Role に 1 行ずつ追加する。
> **Namespace をまたぐ権限（sandbox namespace の Pod 操作）は必要になった時点で
> Role + RoleBinding を `sandbox-ns.yaml` と同じ namespace 側に追加する**
> （`directory.recurse` で拾われる）。cross-namespace を1つの RoleBinding で束ねると
> `subjects[].namespace` の指定が必要になり、Argo CD の宛先 namespace と
> 食い違いやすくなるため、第1MSでは意図的に同一 namespace 内に閉じている。

### 9.6 `exkes/{stg,main}/sandbox-ns.yaml` と `network-policy.yaml`

```yaml
# sandbox-ns.yaml: issue #2「Sandbox Namespace」
apiVersion: v1
kind: Namespace
metadata: { name: platform-exkes-stg-sandbox }   # main は platform-exkes-main-sandbox
```

```yaml
# network-policy.yaml: issue #2「NetworkPolicy」。第1MSには Sandbox Pod が無いので
# 実効は無いが、「入れば既定で deny」の状態を先に作る。
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny
  namespace: platform-exkes-stg-sandbox
spec:
  podSelector: {}                 # namespace 内の全 Pod
  policyTypes: [Ingress, Egress]
  # ingress / egress のルールは空（= 全 deny）。許可は第2MSから egress で積み上げる。
```

> `platform` リポジトリに NetworkPolicy の実例が無いため新規パターン。
> `kubectl apply --dry-run=client -f <file>` で検証する。

### 9.7 CephFS / RGW / その他の新規マニフェスト

| 対象 | 方針 |
|---|---|
| Workspace PVC | **`exkes-controller` が実行時に動的作成**（静的マニフェストには置かない）。storageClass `rook-cephfs`（ReadWriteMany）。実例: `kigawa-net-k8s/oneserver-files/pvc.yml`, `open-webui/deployment.yaml` の volumeClaimTemplates |
| Ceph RGW | 第4MS。エンドポイントはクラスタ内 Service `rook-ceph-rgw-prometheus-store.kigawa-system-rook-ceph.svc:80` のみ。専用 `CephObjectStoreUser` 未整備 → 第12章 |
| NetworkPolicy | **第1MSで sandbox namespace の default-deny を作成**（7.3 章・9.6）。許可リストは第3MS |
| ResourceQuota / LimitRange / PSA / RuntimeClass | クラスタに既存の適用例なし = 全て新規。**Runtime 導入時の第2MSで新規作成**（Runtime のリソース制限と合わせて検討） |
| Ingress / DNS | 第1MSでは作らない（無認証のため）。公開は認証導入後 |

---

## 10. フェーズ分割

issue #1 の **MVPタスク23項目**と**完了条件14項目**をすべてフェーズへ割り当てる。
第1マイルストーン = 本ドキュメントのスコープ（第1.1章）。

| フェーズ | 名称 | 主な成果 |
|---|---|---|
| **第1MS** | 基盤 + Workspace | ドキュメント / スケルトン / Flyway / Workspace domain + API / **`exkes-controller`（Workspace reconciliation のみ）** / CephFS provisioning（controller 側）/ **issue #2 のデプロイ基盤（stg 自動 / prod 手動昇格 / controller 専用 RBAC / sandbox namespace）** → PR |
| **第2MS** | Runtime 基盤 | Runtime domain / KubernetesRuntimeProvider / controller の reconciliation 拡張（Runtime Pod）/ SecurityContext / TTL / **リーダー選出（`replicas > 1` 対応）** |
| **第3MS** | Execution 基盤 | runtime-agent / Command Execution / log streaming / Execution API / NetworkPolicy 許可リスト |
| **第4MS** | データ保存 | Repository checkout / 一時 Credential / Ceph RGW log・Artifact |
| **第5MS** | 対話・Agent | Terminal Gateway / PTY / Claude Code Agent Provider / Audit Log |
| **第6MS** | 品質 | Metrics / E2E |

### 10.0 issue #2 のタスク → フェーズ割当

issue #2 で決まったデプロイ基盤の作業は**すべて第1MS**に前倒しした
（controller の reconciliation は第2MS相当の話だが、Workspace 分の reconciliation は
第1MSで実装する。第2MSに残るのは Runtime Pod の作成とリーダー選出だけ）。
issue #2 は 2026-10-03 に Epic + 子 issue（**#3 / #4 / #5**）へ再構成された。
issue 番号で見ると、本 MS のデプロイ基盤は **#4（CI）+ #3（bootstrap、Docker image）** に、
platform 側マニフェストは **#5** に対応する（#5 は別リポジトリ = 別 PR）。

| issue #2 の決定 | フェーズ | 実装先 | 参照 |
|---|---|---|---|
| 環境は stg / main の 2 つだけ（dev / PR preview / ApplicationSet なし） | 第1MS | `deploy-stg.yml` / `deploy-prod.yml` / `apps/exkes-{stg,main}-app.yml` | 8 章 / 9 章 |
| main push → stg 自動デプロイ | 第1MS | `.github/workflows/deploy-stg.yml` | 8.2.1 |
| `workflow_dispatch`（main 限定）で prod 手動昇格、**再ビルドせず stg の image を転記** | 第1MS | `.github/workflows/deploy-prod.yml` | 8.2.2 |
| image は `harbor.kigawa.net/private/exkes-<module>:main-<sha>`、stg/prod 同一タグ | 第1MS | build ジョブ + `deploy-prod.yml` のガード | 8.1 |
| push 対象は `exkes-api` と `exkes-controller` の 2 つ | 第1MS | matrix / `MODULES`（3 箇所） | 8.2.1 |
| **`exkes-controller` のみが Runtime / PVC 作成権限**、`exkes-api` は K8s API を呼ばない | 第1MS | `rbac.yaml` / `automountServiceAccountToken: false` / コードの移設 | 3 章 / 7.2 / 9.6 |
| Sandbox Namespace（`platform-exkes-{stg,main}-sandbox`） | 第1MS | `sandbox-ns.yaml` | 9.6 |
| NetworkPolicy の定義 | 第1MS（default-deny のみ）/ 第3MS（許可リスト） | `network-policy.yaml` | 7.3 / 9.7 |
| **issue #2 の子 issue として**: #3（最小起動可能アプリ / MariaDB / Docker image） | 第1MS | Gradle マルチモジュール / Flyway / Dockerfile / docker-compose | 11.1 |
| **issue #2 の子 issue として**: #4（stg auto deploy / prod manual promotion workflow） | 第1MS | `deploy-stg.yml` / `deploy-prod.yml` | 8 章 |
| **issue #2 の子 issue として**: #5（platform stg/main manifests） | 第1MS（**別リポジトリ = 別 PR**。`kigawa-net/platform` PR #16） | `apps/exkes-{stg,main}-app.yml` / `exkes/{stg,main}/` | 9 章 / 11.2 |

### 10.1 MVPタスク23項目 → フェーズ割当

> **「issue #1 の該当チェックボックス」列は `docs/issue-1-body.md`（2026-10-01 時点の
> スナップショット）の番号を指す。** 2026-10-03 に issue #1 は Epic + 子 issue
> （#3〜#17）へ再構成され、このチェックボックスは issue 側に存在しなくなった。
> 現行 sub-issue との対応は **10.3 章**。フェーズ割当そのものは変わらない。

| フェーズ | タスク | 旧 issue #1 の該当チェックボックス（スナップショット） |
|---|---|---|
| 第1MS | プロジェクト構成を決定する | [ ] プロジェクト構成を決定する |
| 第1MS | MariaDB schema / migration を作成する（V1 workspaces） | [ ] MariaDB schema / migration を作成する |
| 第1MS | Workspace domain を実装する | [ ] Workspace domain を実装する |
| 第1MS | CephFS + Ceph CSI による Workspace provisioning を実装する（**`exkes-controller` 内の provisioner/reconciler**。issue #2 により api には置かない。7.2 章） | [ ] CephFS + Ceph CSI による Workspace provisioning を実装する |
| 第2MS | Runtime domain / state machine を実装する | [ ] Runtime domain / state machine を実装する |
| 第2MS | KubernetesRuntimeProvider を実装する | [ ] KubernetesRuntimeProvider を実装する |
| 第2MS | exkes-controller の reconciliation を実装する（**Workspace 分は第1MSに前倒し済**。ここから Runtime Pod の作成・TTL・SecurityContext・リーダー選出） | [ ] exkes-controller の reconciliation を実装する |
| 第3MS | runtime-agent を実装する（Process/PTY/Execution/log stream） | [ ] runtime-agent を実装する |
| 第3MS | Command Execution を実装する | [ ] Command Execution を実装する |
| 第3MS | Execution stdout / stderr streaming を実装する | [ ] Execution stdout / stderr streaming を実装する |
| 第4MS | Ceph RGW への log 保存を実装する（第3MSで filesystem 抽象を先行導入、RGW 実装はここ。`CephObjectStoreUser` 整備に依存 = 第12章） | [ ] Ceph RGW への log 保存を実装する |
| 第4MS | Artifact upload / metadata 管理を実装する（`artifacts` テーブル = V5） | [ ] Artifact upload / metadata 管理を実装する |
| 第4MS | Repository checkout を実装する | [ ] Repository checkout を実装する |
| 第4MS | 一時 Credential 注入を実装する（`credentials` テーブル = V4） | [ ] 一時 Credential 注入を実装する |
| 第2MS | Runtime SecurityContext を適用する（non-root / drop ALL / seccomp / readOnlyRootFS 等） | [ ] Runtime SecurityContext を適用する |
| 第3MS | NetworkPolicy default deny を実装する（**default-deny 自体は issue #2 で第1MSに定義済み**。許可リスト整備と疎通検証の完了はここ。方針は第7.3章） | [ ] NetworkPolicy default deny を実装する |
| 第2MS | Runtime TTL cleanup を実装する（TTL 監視 → EXPIRED → STOP/DELETE） | [ ] Runtime TTL cleanup を実装する |
| 第5MS | Terminal Gateway / PTY 接続を実装する（`terminal_sessions` = V6） | [ ] Terminal Gateway / PTY 接続を実装する |
| 第5MS | Claude Code Agent Provider を実装する（Custom Command は第3MSで先行対応） | [ ] Claude Code Agent Provider を実装する |
| 第3MS | Runtime / Execution API を実装する（Runtime API は第2MSで先行実装、Execution API を含めてここで完了） | [ ] Runtime / Execution API を実装する |
| 第5MS | Audit Log を実装する（`audit_logs` = V7） | [ ] Audit Log を実装する |
| 第6MS | 基本 Metrics を追加する（micrometer + /metrics） | [ ] 基本 Metrics を追加する |
| 第6MS | E2E テストを追加する | [ ] E2E テストを追加する |

### 10.2 MVP完了条件14項目 → フェーズ割当

> 10.1 と同じく、チェックボックス列は `docs/issue-1-body.md` のスナップショットを指す。
> 現行 issue の MVP 完了条件は issue #1 本文側にある。

| フェーズ | 検証内容 | 旧 issue #1 の該当チェックボックス（スナップショット） |
|---|---|---|
| 第1MS | `POST /v1/workspaces` で作成 → `READY` になる | [ ] API から Workspace を作成できる |
| 第1MS | PVC `ws-<id>` が `rook-cephfs` で `Bound`、Pod 間で再マウント可能 | [ ] Workspace が CephFS 上に永続化される |
| 第2MS | `POST /v1/runtimes` → Pod `Running` | [ ] API から Kubernetes Runtime を作成できる |
| 第2MS | 既存 workspace id を指定した Runtime が同じ PVC を mount | [ ] Runtime に既存 Workspace を attach できる |
| 第3MS | `POST /v1/runtimes/{id}/executions`（COMMAND）で結果取得 | [ ] Runtime 内で任意コマンドを実行できる |
| 第5MS | AGENT type で Claude Code 起動・終了・結果取得 | [ ] Claude Code を Execution として起動できる |
| 第5MS | terminal-sessions 発行 → WebSocket 接続 → シェル操作 | [ ] ユーザーが同一 Runtime に Terminal 接続できる |
| 第3MS | `GET /v1/executions/{id}/logs`（または stream）で stdout/stderr 取得 | [ ] stdout / stderr を Execution 単位で取得できる |
| 第4MS | log body / artifact body が RGW にあり、DB に metadata のみ | [ ] Log / Artifact が Ceph RGW に保存される |
| 第2MS | Runtime DELETE 後も PVC と workspaces 行が残る | [ ] Runtime を削除しても Workspace が保持される |
| 第2MS | 同一 workspace で Runtime を再作成し mount に成功 | [ ] Runtime 再作成時に同じ Workspace を再接続できる |
| 第2MS | Pod の resources.limits が指定値で適用される | [ ] CPU / Memory limit が適用される |
| 第3MS | default-deny 下で Sandbox から K8s API / exkes-api / MariaDB / 他 Runtime へ到達不可 | [ ] Sandbox から Kubernetes API / Control Plane / 他 Runtime にアクセスできない |
| 第2MS | TTL 超過で Runtime が自動 EXPIRED → 削除される | [ ] Runtime TTL 超過時に自動削除される |

> 割当の原則: 「完了条件は、その検証に必要なタスク群の**最後のフェーズ**」に置いた。
> 例: 完了条件「Ceph RGW に保存」はタスク「RGW への log 保存」と Artifact の両方が
> 揃う第4MS。認証（Keycloak OIDC）は issue #1 の MVPタスク/完了条件に含まれないため、
> 第1.2章のとおり**MVP 後続フェーズ**として別途起票する。

### 10.3 現行 sub-issue（#3〜#17）との対応（2026-10-03 の issue 再構成に追随）

issue #1 / #2 は「Epic + 子 issue」に再構成された。10.1 / 10.2 章の表は
**スナップショット（旧 issue #1 本文）のタスク番号を前提にしているため**、
現行の sub-issue 番号との対応だけをここに置く。

| 現行 sub-issue | 旧 issue #1 の該当タスク（10.1 章） | 本 MS の扱い |
|---|---|---|
| #3 `feat: bootstrap exkes application and persistence layer` | プロジェクト構成 / MariaDB schema・migration / Domain・Repository 層 | **部分的に実装**。Gradle マルチモジュール、`GET /health`、MariaDB 接続、Flyway `V1__workspaces.sql`、`WorkspaceRepository`、Docker image は済。`runtime_templates` 等の他 7 テーブルは各 sub-issue へ遅延、`desired_state` / `observed_state` は `workspaces.status` として持つ（列の分離はしない） |
| #4 `ci: add stg auto deploy and prod promotion workflows` | （旧 issue #1 には無い。issue #2 のみ） | **workflow は実装済**。`deploy-stg.yml` / `deploy-prod.yml`。ただし Harbor push / platform への commit は exkes の main マージと platform PR #16 マージを待つため**未検証** |
| #5 `infra: add exkes stg and prod manifests to platform` | （同上） | **別リポジトリ**。`kigawa-net/platform` PR #16 で作成済み・未マージ |
| #6 `feat: implement persistent workspace provisioning with CephFS` | Workspace domain / CephFS provisioning | **部分的に実装**。domain / service / API / PVC dynamic provisioning は済。`/workspace` mount・Runtime 再 attach・quota は #7 / #13 の領分（Runtime が無いため mount 先が無い） |
| #7 `feat: implement Kubernetes runtime lifecycle and reconciliation controller` | Runtime domain / KubernetesRuntimeProvider / controller の reconciliation | Workspace 分の reconciliation のみ先取り（provisioner / reconciler は `exkes-controller` に配置）。本体は第2MS |
| #13 `feat: define public API and authentication/authorization` | Runtime / Execution API、認証 | 未着手。認証（Keycloak OIDC）は MVP 後続フェーズ（第1.2章・第12章#9） |
| #14 / #17 | Runtime Template / Credential 配送 | 未着手（第2MS / 第4MS） |
| #10 `security: isolate sandbox runtimes` | NetworkPolicy default deny | **default-deny の定義のみ issue #2 相当で前倒し済**（`network-policy.yaml`）。Pod が無いので実効なし。sandbox 隔離の実装・E2E 検証は #10 の領分 |
| #8 / #11 / #12 / #9 | runtime-agent / Execution / RGW / Terminal / Agent | 未着手（第3〜5MS）。`runtime-agent` / `exkes-terminal-gateway` はスケルトンのみ |
| #15 / #16 | Metrics / E2E | 未着手（第6MS） |

> **本 MS で issue のチェックボックスを閉じられるか**: #3 / #4 / #6 は
> 上表のとおり「部分的に実装」であり、sub-issue 側の完了条件（例: #6 の
> `/workspace` mount・quota、#4 の Harbor push 成功）は未検証・未達のため、
> **sub-issue は closed にしない**。Epic のチェックボックス（`- [ ] #3` 等）も
> 該当する sub-issue が揃うまで付けない。

---

## 11. 第1マイルストーンの成果物チェックリスト

PR で提出するファイル一覧（具体的）。

### 11.1 `kigawa-net/exkes` 側（本体 PR）

- [ ] `docs/implementation-plan.md` — 本ドキュメント
- [ ] `docs/issue-1-body.md` / `docs/issue-2-body.md`（issue #2 がデプロイ方針の基準）
- [ ] `settings.gradle.kts`（`rootProject.name = "exkes"`、5モジュール include） / `build.gradle.kts`（kotlin 2.1.21 + serialization + `io.ktor.plugin` 3.1.3、subprojects 共通） / `gradle.properties`
- [ ] `gradle/libs.versions.toml`（第4章のバージョン一覧） + `gradle/wrapper/*`（Gradle 8.14） + `gradlew` + `gradlew.bat`
- [ ] `exkes-common/build.gradle.kts`（**client-java 非依存**）
- [ ] `exkes-common/src/main/kotlin/net/kigawa/exkes/common/config/ExkesConfig.kt`（`database` / `workspace` のみ）
- [ ] `exkes-common/src/main/kotlin/net/kigawa/exkes/common/db/Database.kt`（HikariCP + Flyway + Exposed）
- [ ] `exkes-common/src/main/kotlin/net/kigawa/exkes/common/db/WorkspacesTable.kt` / `db/WorkspaceRepository.kt`
- [ ] `exkes-common/src/main/kotlin/net/kigawa/exkes/common/domain/Workspace.kt`（`WorkspaceStatus` enum 含む）
- [ ] `exkes-common/src/main/resources/db/migration/V1__workspaces.sql`（第5.2章 DDL）
- [ ] `exkes-api/build.gradle.kts`（**client-java 非依存**）
- [ ] `exkes-api/src/main/kotlin/net/kigawa/exkes/api/Application.kt`
- [ ] `exkes-api/src/main/kotlin/net/kigawa/exkes/api/routes/HealthRoutes.kt` / `routes/ErrorPages.kt` / `routes/WorkspaceRoutes.kt` / `routes/WorkspaceDtos.kt`
- [ ] `exkes-api/src/main/kotlin/net/kigawa/exkes/api/workspace/WorkspaceService.kt`（`suspend` + `Dispatchers.IO`）
- [ ] `exkes-api/src/main/resources/application.conf`（HOCON + `${?EXKES_<SECTION>_<KEY>}`。**`kubernetes` / `reconciler` section は無い**） / `logback.xml`
- [ ] `exkes-api/src/test/kotlin/net/kigawa/exkes/api/WorkspaceRoutesTest.kt`（testApplication + H2） / `api/WorkspaceServiceTest.kt`
- [ ] `exkes-controller/build.gradle.kts`（**client-java あり** / ktor / h2 / logstash）
- [ ] `exkes-controller/src/main/kotlin/net/kigawa/exkes/controller/Application.kt`（DB connect → migrate → reconciler → `/health`）
- [ ] `exkes-controller/src/main/kotlin/net/kigawa/exkes/controller/config/ControllerConfig.kt`
- [ ] `exkes-controller/src/main/kotlin/net/kigawa/exkes/controller/k8s/KubernetesClientFactory.kt`（in-cluster）
- [ ] `exkes-controller/src/main/kotlin/net/kigawa/exkes/controller/workspace/WorkspaceProvisioner.kt`（interface + `PvcState` + `resolvePvcName`）
- [ ] `exkes-controller/src/main/kotlin/net/kigawa/exkes/controller/workspace/KubernetesWorkspaceProvisioner.kt`
- [ ] `exkes-controller/src/main/kotlin/net/kigawa/exkes/controller/workspace/WorkspaceReconciler.kt`
- [ ] `exkes-controller/src/main/resources/application.conf`（`kubernetes` / `reconciler` section あり） / `logback.xml`
- [ ] `exkes-controller/src/test/kotlin/.../WorkspaceReconcilerTest.kt`（fake provisioner + timeout 系を含む）
- [ ] `exkes-common/src/test/kotlin/.../MigrationH2Test.kt` / `db/WorkspaceRepositoryTest.kt`
- [ ] `exkes-terminal-gateway/` / `runtime-agent/` の各 `build.gradle.kts` + `Application.kt`（**スケルトンのまま。issue #2 対象外**）
- [ ] `Dockerfile`（`ARG MODULE`、`installDist` 方式、temurin 21 JRE）
- [ ] `.dockerignore`
- [ ] `docker-compose.yml`（mariadb:11 + `exkes-api` + `exkes-controller`）
- [ ] `.github/workflows/deploy-stg.yml`（第8.2.1章）
- [ ] `.github/workflows/deploy-prod.yml`（第8.2.2章）
- [ ] `.gitignore`
- [ ] `README.md`（構成・起動方法・環境変数一覧・CI/CD）
- [ ] `CLAUDE.md`（開発規約: Flyway 追加手順 / installDist 理由 / テスト方針 / モジュール追加 3 箇所同期）

### 11.2 `kigawa-net/platform` 側（別 PR・別 issue。**BWS UUID はプレースホルダ**）

> **作業は `kigawa-net/platform` PR #16 として作成済み（未マージ）。**
> PR #16 のファイル構成は 9.1 章の実物に合わせている（`sandbox-ns.yaml` /
> `exkes-{api,controller}-svc.yaml`）。ここでのチェックは **PR #16 のマージ状況**を
> 追跡するために使う。**exkes 側の main をマージする前に PR #16 を main に入れること**
> （未マージだと stg の image 更新がスキップされる。第12章 #4）。

- [ ] `apps/exkes-stg-app.yml`（`path: exkes/stg` / `namespace: platform-exkes-stg` / `directory.recurse: true`）
- [ ] `apps/exkes-main-app.yml`（`path: exkes/main` / `namespace: platform-exkes-main`）
- [ ] `exkes/stg/ns.yaml`（Namespace: `platform-exkes-stg`）
- [ ] `exkes/stg/sandbox-ns.yaml`（Namespace: `platform-exkes-stg-sandbox` + PSA `restricted`）
- [ ] `exkes/stg/exkes-api.yaml`（Deployment。`automountServiceAccountToken: false`）
- [ ] `exkes/stg/exkes-controller.yaml`（Deployment。`serviceAccountName: exkes-controller`）
- [ ] `exkes/stg/exkes-api-svc.yaml` / `exkes/stg/exkes-controller-svc.yaml`（Service）
- [ ] `exkes/stg/rbac.yaml`（ServiceAccount / Role / RoleBinding — **controller 専用**。Role は `persistentvolumeclaims` のみ）
- [ ] `exkes/stg/network-policy.yaml`（sandbox namespace の default-deny）
- [ ] `exkes/stg/mariadb.yaml`（BitwardenSecret の `bwSecretId` 2件は `<TODO-BWS-UUID>`）
- [ ] `exkes/main/` に上記ファイル（`mariadb-exkes-stg` → `mariadb-exkes-main` に置換）
- [ ] 各ファイルで `kubectl apply --dry-run=client -f <file>` 検証済みであること（platform/CLAUDE.md の規約）
- [ ] `exkes/stg/exkes-{api,controller}.yaml` の `kind` が `Deployment` **1リソース**であること
      （`yq -r '.kind'` が 1 行であること。CI が hard fail する）

### 11.3 リポジトリ設定（手動・PR とは別）

- [ ] `HARBOR_PASS`（robot$ kigawa-net）シークレットを exkes リポジトリに登録
- [ ] `kigawa-net/kigawa-net-k8s` の secret 同期 CR の `TARGET_NAMESPACES` に
      `platform-exkes-stg` / `platform-exkes-main` を追記（PR #251 で作成済み・未マージ）。
      未マージだと BitwardenSecret / imagePullSecret が同期されず、
      **Pod が SecretNotFound で起動できない**（第12章#3）
- [ ] Harbor に `private` project があり、`exkes-api` / `exkes-controller` の
      push 権限を持つ robot アカウントがあること（`HARBOR_PASS` の対象）
- [ ] ~~`deploy-preview` ラベル~~ / ~~GitHub App の exkes へのインストール~~ は
      **不要になった**（issue #2 で dev 環境を廃止したため）

---

## 12. リスク・未解決事項

| # | 項目 | 内容 | 影響 | 対応 |
|---|---|---|---|---|
| 1 | **Ceph RGW 未整備** | エンドポイントは cluster内 Service `rook-ceph-rgw-prometheus-store.kigawa-system-rook-ceph.svc:80` のみ。専用 `CephObjectStoreUser`（アクセスキー）が未整備 | 第4MS「Log/Artifact が Ceph RGW に保存」がブロック | 第3MSまでに `CephObjectStoreUser` 作成の**別 issue を起票**（Rook 管理側）。整備までは `ExecutionLogStorage` 抽象の filesystem 実装（PVC/emptyDir）で前進し、RGW 実装は差し替え |
| 2 | **BWS UUID プレースホルダ** | `exkes/{stg,main}/mariadb.yaml` の `bwSecretId` は仮値。実UUID は Bitwarden への手動登録、または `infra/platform/` 配下の Terraform（実例: kalender worktree）で管理 | プレースホルダのままでは BitwardenSecret が同期失敗 → MariaDB 起動不可 | デプロイ前に実UUID 置換 PR。Terraform 化の要否を infra 側 issue で判断 |
| 3 | **他リポジトリ連携 PR（スコープ外）** | `kigawa-net-k8s` の `bitwarden-sync-crn.yaml` / `harbor-sync-crn.yaml` の `TARGET_NAMESPACES` に `platform-exkes-stg` / `platform-exkes-main` と sandbox namespace を追記 | 追記なし = `bitwarden-sec` トークンが同期されず BWS 全般が動かない | **`kigawa-net-k8s` の別 issue を起票** |
| 4 | **platform 側の manifest が exkes の main より先に必要** | `deploy-stg.yml` は対象ファイルが無ければ `::error::` を出してスキップ（落ちないが image 更新されない）。`deploy-prod.yml` は stg manifest が無ければ hard fail | stg が古い image のまま止まる / prod 昇格できない | **platform の manifest 追加 PR を exkes の main マージより先に main へ入れる**（第8章・11.2章） |
| 5 | **platform 側の manifest PR が未マージ** | `kigawa-net/platform` PR #16（`exkes/{stg,main}/` + `apps/`）が未マージ。exkes の main を先にマージすると対象ファイルが無いため stg の image 更新がスキップされる | stg が古い image のまま止まる / prod 昇格できない | **PR #16 を exkes の main マージより先に main へ入れる**（第8章・11.2章） |
| 6 | **`exkes-controller` は単一Active前提** | `replicas: 1` 固定。`WorkspaceReconciler` の試行回数はプロセス内メモリなので、replicas を増やすと同一 workspace を複数 Pod が provision する | 水平スケールすると二重作成 | 第2MSでリーダー選出（Lease）を導入してから `replicas` を増やす。issue #2 では未議論（7.2章） |
| 7 | **両モジュールでの Flyway 同時 migrate** | `exkes-api` と `exkes-controller` が両方とも起動時に migrate する（独立 Deployment なので先に乗った側が動くように） | 同時に走るとロック競合の可能性 | Flyway の `flyway_schema_history` で run は直列化される。問題になった場合は専用 migration job に切り替える（第3.2章） |
| 8 | **`error_message` に K8s API のレスポンス本文が入る** | `ERROR` 遷移時に例外メッセージが DB に入る | DB アクセス権者が内部情報を読める可能性 | API の 500 応答は `internal error` に固定済み（直接の漏洩は無い）。RBAC の events 経由への変更は第2MS |
| 9 | **DNS / 公開エンドポイント** | 第1MSは ClusterIP のみ。将来 terminal-gateway 等は `kigawa.net` の Ingress（class `haproxy`）+ DNS が必要 | 外部公開が必要になった時点で作業 | **認証（Keycloak OIDC）導入フェーズ**とセットで起票（Ingress/DNS はスコープ外） |
| 10 | **認証未実装のままの stg / main が常設** | 第1MS〜認証導入まで API は無認証。ClusterIP のためクラスタ内からのみ到達可能 | クラスタ内からの任意呼び出しリスク | Ingress を張らないこと、NetworkPolicy（7.3章）で namespace 間アクセスを制限することを運用制御。認証フェーズの優先度検討 |
| 11 | **NetworkPolicy / Quota / PSA / RuntimeClass 実例なし** | クラスタに既存の適用例がなく全て新規 | Runtime 導入時の設計漏れ | default-deny は issue #2 で第1MSに定義（9.6章）。残りは第2MS のタスク化。新規 manifest は `kubectl apply --dry-run=client` で検証 |
| 12 | **RBAC 例が platform にない** | `exkes/{stg,main}/rbac.yaml` は Role/RoleBinding の新規パターン | namespace をまたぐ binding（sandbox namespace）が動くか未検証 | 第1MSは同一 namespace 内に閉じる（9.5章）。cross-namespace は第2MSで必要になってから追加し、その時に疎通確認する |
| 13 | **Flyway と fat JAR** | shadowJar は Flyway のクラスパス走査と相性が悪く migration が適用されない（ai-scheduler の実体験） | 起動してもスキーマ無しで落ちる | `installDist` 方式を Dockerfile で強制（第4章）。PR レビューで確認 |
| 14 | **Exposed のバージョン差** | ai-scheduler 0.56.0 / keruta 1.5.0 と実物が割れている | 1.x 移行時に API 差分 | 第1MSは 0.56.0。Renovate の PR でまとめて移行判断 |
| 15 | **`kigawa-net-app-token` アクションの ref** | `@dev` で参照する（ai-scheduler の WIF 版 cd.yml と同様）。action 本体は OIDC ベース（`permissions: id-token: write` 必須、shared secret 不要） | ref が変わると CI が壊れる | 第8.2章のとおり `id-token: write` を必ず付与。@dev 以外の ref 固定化は kinfra 側の運用に従う |
| 16 | **Harbor の `private` project** | image は `harbor.kigawa.net/private/exkes-<module>` に push する（`library` ではない） | project が無い / push 権限が無いと build が失敗 | Harbor 側 事前の確認が必要（11.3章） |

### 次に起票すべき issue（提案）

1. `kigawa-net/platform` — 「exkes の stg/main マニフェストを追加」
   （**issue #2 / #5 の作業、実装の前提。PR #16 で作成済み・未マージ。11.2章**）
2. `kigawa-net/kigawa-net-k8s` — 「secret-provider の `TARGET_NAMESPACES` に platform-exkes-{stg,main} を追加」
   （**PR #251 で作成済み・未マージ**。sandbox namespace は第2MSで Runtime が出た時点で追加）
3. Rook/Ceph 管理側 — 「exkes 用 `CephObjectStoreUser` の整備（第4MS前提）」
4. `kigawa-net/platform` — 「exkes マニフェストの BWS UUID 実値置換（Terraform 化の要否含む）」
5. `kigawa-net/exkes` — 「Keycloak OIDC 認証フェーズ（MVP後続）」
6. `kigawa-net/exkes` — 「`exkes-controller` のリーダー選出（`replicas > 1` 対応、第2MS前提）」
