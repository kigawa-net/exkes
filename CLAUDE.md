# exkes 開発ガイド

このドキュメントは exkes を開発する上での規約・手順をまとめる。
設計の詳細は `docs/implementation-plan.md`（これが仕様書）を参照すること。
デプロイ方針の基準は `docs/issue-2-body.md`。
`docs/issue-{1,2}-body.md` は 2026-10-01 時点の issue 本文スナップショットで、
issue 自体は 2026-10-03 に Epic + 子 issue（#3〜#17）へ再構成されている（plan 10.3 章）。

## コマンド

```bash
./gradlew test              # 全モジュールのテスト
./gradlew build -x test     # ビルドのみ
./gradlew :exkes-api:run            # API 起動（要 docker compose up -d mariadb）
./gradlew :exkes-controller:run     # controller 起動（同上）
./gradlew :exkes-api:installDist        # 配布スクリプト生成（Dockerfile と同じ経路）
./gradlew :exkes-controller:installDist
docker compose up -d        # ローカルスタック（mariadb + exkes-api + exkes-controller）
```

- JDK 21 を使うこと（Gradle wrapper は 8.14。新しい JDK で動かさないこと）。
  既定の `java` が 21 以外なら `JAVA_HOME=<jdk21> ./gradlew ...` のように必ず
  `JAVA_HOME` を明示する。Gradle 8.14 は JDK 25 を Launcher JVM にサポートしない。
- ルート直下でビルドしない。必ずこのリポジトリ内で `./gradlew` を実行する。

## モジュール構成

- モジュールは「制御平面のデプロイ単位」。追加手順:
  1. `settings.gradle.kts` に include する（ディレクトリも作成すること）
  2. `build.gradle.kts` に `application` + mainClass を設定
  3. `Dockerfile` の `--build-arg MODULE=<name>` でビルド可能にする
  4. **`.github/workflows/deploy-stg.yml` の `build` ジョブの matrix に追加する**
  5. **`.github/workflows/deploy-stg.yml` の `env.MODULES` に追加する**
  6. **`.github/workflows/deploy-prod.yml` の `env.MODULES` に追加する**
  7. `kigawa-net/platform` 側に `exkes/{stg,main}/<module>.yaml`（**Deployment 1リソース**
     で `containers[0]` が対象モジュール。CI は index 固定で image を書く）と
     `exkes/{stg,main}/<module>-svc.yaml`（Service）を追加する

  > 4〜6 の 3 箇所を同期し忘れると「stg には manifest が無いのに push される」/
  > 「build はするのに image 更新されない」といった取りこぼしが起きる。issue #2 で
  > 対象となっているのは `exkes-api` と `exkes-controller` の 2 つだけ。
- **`runtime-agent` は `exkes-common` に依存させない**（plan 第3章）。
  Sandbox 側は JSON over HTTP/WebSocket だけで通信し、制御平面のコードを
  リンクしない。
- **`exkes-common` は Kubernetes に依存させない**（issue #2）。client-java を
  持たず、`k8s/` パッケージを置かない。`exkes-api` にも client-java を入れない。
- **RBAC は「コードが触る resource だけ」**を足す。第1MS の
  `exkes-controller` が触るのは `persistentvolumeclaims` だけなので、Role も
  その 1 行に留める（`kigawa-net/platform` PR #16 の `exkes-controller-workspace-pvc`
  がこの方針）。`pods` / `deployments` / `networkpolicies` は第2MS で
  実装と同時に 1 行ずつ追加する。
- DI フレームワークは使わない。`Application.module()` 内で
  repository → service（api）/ repository → provisioner → reconciler（controller）の
  順に生成して渡す（kigawa-net の慣行）。

## 権限の分離（issue #2）

- **Kubernetes API を呼ぶのは `exkes-controller` だけ。**
  `KubernetesClientFactory` / `KubernetesWorkspaceProvisioner` /
  `WorkspaceReconciler` は `exkes-controller` 配下に置き、`exkes-api` から
  参照しない（cross-module import はビルドエラーになる）。
- `exkes-api` は desired state（`CREATING` 行の insert / `DELETING` への遷移）を
  書くだけで、状態遷移を進めない。`exkes-api` の `application.conf` に
  `exkes.kubernetes.*` / `exkes.reconciler.*` section を**書かない**
  （書くと `MissingPropertyException` で起動に失敗する）。
- 設定が必要になったら `ControllerConfig.kt` に `data class` を追加して
  `exkes-controller/src/main/resources/application.conf` にだけ書く。

## Flyway migration の追加手順

スキーマ変更は必ず Flyway migration で行う（手での DDL 変更は禁止）。

1. `exkes-common/src/main/resources/db/migration/` に
   `V<番号>__<説明>.sql` を作成する（番号は連番・欠番禁止・既存番号の変更禁止。
   `validateMigrationNaming(true)` で検証される）。
2. DDL は **H2 (`MODE=MySQL`) でも動く記述**にすること。
   - `ENGINE=` / `CHARSET=` / `ON UPDATE CURRENT_TIMESTAMP` 句は書かない
   - 文字コードは mariadb-operator 側で担保する
   - `updated_at` はアプリ側（`WorkspaceRepository`）で更新する
3. `exkes-common` の `WorkspacesTable` を同じ定義に更新する。
4. `MigrationH2Test` に追加の検証を足す（H2 への適用と DDL 互換性）。
5. migration は `exkes-common` に置くが、**起動時 migrate は `exkes-api` と
   `exkes-controller` の両方が実行する**（トップレベル関数
   `net.kigawa.exkes.common.db.runMigrations(dataSource)` を呼ぶのは
   `net.kigawa.exkes.api.Application.module()` と
   `net.kigawa.exkes.controller.ApplicationKt.module()`）。
   - 理由: 独立 Deployment なので、先に立ち上がった側が動くようにしておかないと
     `workspace` テーブルが無い状態で controller が起動する（issue #2 の 2 Deployment 構成）。
   - Flyway は `flyway_schema_history` で run を直列化する。
   - 将来これが問題になったら「片方から削除」ではなく**専用 migration job** を用意する。

## Dockerfile: なぜ shadowJar を使わないか

`installDist`（スクリプト + JAR を1個ずつ配置）方式を使う。
**shadowJar（fat JAR）は Flyway のクラスパス走査と相性が悪く、**
`db/migration` 配下の SQL ファイル名を認識できず全マイグレーションが
適用されない不具合がある（ai-scheduler の実体験。plan 第4章・第12章）。
Dockerfile のコメントも参照。レビューで shadowJar への変更が入っていないか
確認すること。

## テスト方針

- JUnit 5 + `kotlin-test-junit5`。**Kotest / Testcontainers は不使用**。
- テスト関メッド名は英語のバッククォート形式:
  ```kotlin
  @Test
  fun `create returns accepted with creating status`() { ... }
  ```
- DB が必要なテストは **H2（`MODE=MySQL`）+ Flyway** を使う。
  - schema は毎回 Flyway で作る（`SchemaUtils.create` は使わない。
    DDL 互換性の検証が目的を兼ねる）
  - URL は `jdbc:h2:mem:<一意名>;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1`
    - `CASE_INSENSITIVE_IDENTIFIERS=TRUE` が必要: Exposed は `name` 列を
      引用符付き（`"name"`）で発行するが、Flyway の DDL は未引用のため H2 は
      大文字（`NAME`）として格納し、そのままでは列未検エラーになる
  - アサーションは「id ・一意な name」基準で書き、DB がテスト間で共有されても
    崩れないようにする
- Ktor のルートは `testApplication` + `ktor-server-test-host` で検証する。
- reconciler 等の非同期コンポーネントは **fake の依存を注入**して
  `reconcileOnce()` を同期的に呼び、状態遷移を検証する（実 K8s は使わない）。
  時刻依存の検証は `clock: () -> Instant` を注入する（`creatingTimeoutSeconds` のテスト）。
- repository のテストは `exkes-common`、service のテストは `exkes-api`、
  reconciler のテストは `exkes-controller` に置く（**テストも実装と同じモジュールに**）。
- `./gradlew test` が通らない状態でコミット / PR にしない（現在 51 件）。

## API 開発規約

- ルートは `/v1` プレフィックス。DTO は `exkes-api` の `routes` 配下に
  `@Serializable` で置く。
- **API request 内で PVC / Pod の完了を待たない。** 作成系は 202 Accepted で
  即時応答し、状態遷移は `WorkspaceReconciler`（controller）が非同期で進める。
- **ブロッキング JDBC は Netty のイベントループで走らせない。**
  `WorkspaceService` の公開メソッドは全て `suspend` で、
  内部で `withContext(Dispatchers.IO)` する。これが無いと 1 リクエストで
  同じイベントループの接続が全部止まる。
  `WorkspaceService` を呼ぶテストは `runBlocking` で囲む。
- エラー共通形式 `{"code": "...", "message": "..."}` を使う
  （`NOT_FOUND` / `CONFLICT` / `VALIDATION_ERROR` / `INTERNAL`）。
  マッピングは `routes/ErrorPages.kt` の `installErrorPages()`（Ktor
  `StatusPages`）に集約する。ルート側で try/catch してマッピングしないこと。
  `exception<Throwable>` は例外メッセージを**クライアントに返さず**、詳細は
  サーバ側ログ（`logger.error`）に残す。JDBC URL や K8s API のレスポンス本文が
  漏洩するため。
- **`409` は「同一 workspace の重複」だけに返す。** `WorkspaceRepository` の
  `isDuplicateWorkspaceKeyViolation` は制約名 `uk_workspaces_name` で絞り、
  NOT NULL 違反を 409 に倒さない。
- 状態遷移は必ず `WorkspaceRepository.transition()`（
  `WHERE id = ? AND status = ?` の条件付き UPDATE）経由にする。
  直接 `UPDATE` しないこと。

## PR / CI ワークフロー

- **main への直接 push は不可。必ず PR 経由**（Approvals 0 でも PR 必須）。
  ブランチ命名は `feat|fix|docs|chore/<subject>`。
- **環境は stg / main の 2 つだけ**（issue #2）。dev / PR preview 環境や
  ApplicationSet は**作らない**。`deploy-preview` ラベルも GitHub App の
  exkes へのインストールも不要。
- **`.github/workflows/deploy-stg.yml`**
  - PR → `test` だけ（image は push しない、platform へコミットしない）
  - main push → `test` → `build`（matrix: `exkes-api` / `exkes-controller`、
    `harbor.kigawa.net/private/exkes-<module>:main-<sha>`）→
    `deploy-stg`（WIF で鋳造したトークンで `kigawa-net/platform` の
    `./exkes/stg/exkes-{api,controller}.yaml` を `yq` 更新して commit/push。
    ArgoCD が stg へ同期）
  - 更新対象は列挙した **2 ファイルだけ**。`exkes*.yaml` の glob は使わない
    （`exkes-api-svc.yaml` / `rbac.yaml` / `mariadb.yaml` まで巻き込むため）。
  - platform の manifest は **1 リソース 1 ファイル**。`yq -r '.kind'` が
    `Deployment` 以外なら hard fail する。Deployment だけを更新する式は
    `(select(.kind == "Deployment") | .spec.template.spec.containers[0].image)`
    — 素の `.spec.template...` を全ドキュメントに適用すると Service に
    `spec.template` が注入され、ArgoCD が `unknown field` で弾く。
  - image を yq 式内で `"$image"` と書くと yq の変数として解釈される。
    環境変数は `strenv(image)` で読む。**`image` は `export` 必須**
    （単なる shell 変数だと空文字で上書きされる）。
  - 書き込み後に読み戻し検証し、`git diff --cached --name-only` で
    意図しないファイルが混ざっていないか確認する。判定は**完全一致**で行う
    （`git add` / `git diff` は先頭の `./` を落とすので、記録する側も
    `${path#./}` で揃えておく）
  - **更新対象が無いときは `exit 0` だけでは足りない。** `run` ステップを抜けると
    次の `push` ステップが実行され、`git commit` が `nothing to commit` で
    失敗してジョブが赤になる。`changed` を `GITHUB_OUTPUT` に出力し、
    `push` 側に `if: steps.<id>.outputs.changed == 'true'` を付けること。
  - `git commit --author=.` は kigawa-net の流儀（既存 author に解決される）。
    履歴が 1 件もない checkout（初回 clone 直後）では解決できないので失敗する
  - **依存順序**: platform 側に `exkes/stg/` が未マージの場合、対象ファイルが
    無いため該当モジュールの更新はスキップされる（`::error::` は出すが
    ジョブは失敗しない）。platform のマニフェスト PR を先に main に入れること。
- **`.github/workflows/deploy-prod.yml`**
  - `workflow_dispatch` のみ。`refs/heads/main` 以外は `guard` ジョブで明示的に落とす
  - **build / push はしない。** `exkes/stg/` の image を `exkes/main/` へ
    そのまま転記して commit/push（ArgoCD が `platform-exkes-main` へ同期）
  - stg manifest が無い / image が取れない / `main-<40 hex>` 以外なら hard fail
  - 既に同一 image を参照している場合は `changed=false` で commit をスキップする
    （冪等。`push` ステップの `if` ガードがあるので、再実行してもジョブは緑のまま）
  - **依存順序**: stg デプロイが成功していること。stg が未反映なら prod は上げない
- コード内コメントは**英語**、README / CLAUDE.md / docs は**日本語**。
