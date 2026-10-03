<!--
  検証した issue のスナップショット（2026-10-01 時点の issue #1 本文・原文ママ）。
  2026-10-03 に issue #1 は「Epic + 子 issue #3〜#17」へ再構成され、本文は
  「MVP Issues / MVP 完了条件」の形に置き換わっている。
  このスナップショットは再構成前の本文（MVP タスク 23 項目 / 完了条件 14 項目）を
  保持しており、実装計画 10.1 / 10.2 章のフェーズ割当表はこの版を前提に写得てある。
  現行の issue 構成との対応表は docs/implementation-plan.md の 10.3 章を参照。
  デプロイ方針の現行基準（issue #2）については docs/issue-2-body.md を参照。
-->

## 概要

exkes の MVP を実装する。

exkes は、AI Agent や任意コマンドを安全な隔離環境で実行するための Execution Runtime Platform とする。

主な利用元として `ai-scheduler` を想定するが、Scheduler や特定の AI Agent には依存しない。

## 目的

以下を提供できる実行基盤を構築する。

- Runtime の作成・起動・停止・破棄
- Workspace 管理
- Kubernetes 上での隔離コンテナ実行
- Claude Code 等の AI Agent 実行
- ユーザーによる Terminal アクセス
- 任意コマンド実行
- 実行ログ取得
- Repository checkout
- Workspace 永続化
- Resource 制限
- Runtime 単位の Network 制御
- 一時 Credential 注入
- Runtime TTL 管理

## アーキテクチャ

```text
ai-scheduler / User
        |
        v
    exkes-api
        |
        +--------------------+
        |                    |
        v                    v
     MariaDB             Ceph RGW
 Metadata / State       Logs / Artifacts
        ^
        |
 exkes-controller
        |
        v
   Kubernetes
        |
        +---- Runtime Pod
        |       |
        |       +-- runtime-agent
        |       +-- Claude Code / shell
        |       +-- /workspace
        |
        v
     CephFS
    Workspace
```

Control Plane と Sandbox Plane は分離する。

Sandbox から Control Plane、MariaDB、Kubernetes API、他 Runtime へ直接アクセスさせない。

## 永続化

### MariaDB

Control Plane の Source of Truth として利用する。

主要テーブル候補:

- `workspaces`
- `runtime_templates`
- `runtimes`
- `executions`
- `terminal_sessions`
- `credentials`
- `artifacts`
- `audit_logs`

Runtime Pod や Kubernetes Resource 自体は Source of Truth としない。

### CephFS

Workspace の永続化に利用する。

```text
Workspace
   |
   v
CephFS Subvolume / PVC
   |
   v
Runtime Pod
/workspace
```

Runtime が破棄・再作成されても既存 Workspace を再接続できるようにする。

Kubernetes からは Ceph CSI 経由で利用する。

### Ceph RGW

S3 互換 Object Storage として以下を保存する。

- Execution stdout / stderr
- Artifact
- 将来的な大容量ログ

DB には object key、size、content type、checksum 等の metadata のみ保存する。

## Domain Model

### Workspace

Runtime より長いライフサイクルを持つ永続 filesystem。

状態:

- `CREATING`
- `READY`
- `ARCHIVED`
- `DELETING`
- `DELETED`
- `ERROR`

### Runtime

Workspace 上で Execution を実行する隔離環境。

状態:

- `CREATING`
- `STARTING`
- `RUNNING`
- `STOPPING`
- `STOPPED`
- `FAILED`
- `DELETING`
- `DELETED`
- `EXPIRED`

Runtime には必ず TTL を持たせる。

### Execution

Runtime 内で実行される 1 つの処理。

Type:

- `COMMAND`
- `AGENT`
- `SYSTEM`

状態:

- `QUEUED`
- `STARTING`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`
- `TIMED_OUT`

Runtime と Execution は分離し、同一 Runtime 上で複数 Execution を実行可能とする。

Agent Execution は MVP では同一 Runtime あたり同時 1 件までとする。

## Control Plane

### exkes-api

責務:

- Workspace CRUD
- Runtime CRUD / start / stop
- Execution 作成・状態取得・cancel
- Terminal Session 発行
- Artifact metadata / download
- Runtime Template 管理

API request 内で Pod 起動完了を待たず、非同期 state transition とする。

### exkes-controller

Desired State と Kubernetes 上の実状態を reconciliation する。

責務:

- Runtime Pod 作成・削除
- CephFS PVC / Volume 割当
- NetworkPolicy 作成
- Resource 制限適用
- 一時 Secret 作成・削除
- TTL 監視
- Runtime 障害検出
- Runtime 再作成

### exkes-terminal-gateway

WebSocket 経由でユーザー Terminal と Runtime を接続する。

- Authentication
- Authorization
- Session validation
- Runtime validation
- Session TTL
- Audit
- WebSocket relay

Runtime に SSH Server は立てない。

## Runtime

各 Runtime Pod には `runtime-agent` を配置する。

runtime-agent の責務:

- Process 管理
- PTY 管理
- Execution 管理
- Log stream
- Workspace 操作

概念 API:

- `exec(command)`
- `startExecution()`
- `stopExecution()`
- `openTerminal()`
- `getExecutionStatus()`
- `streamLogs()`

Control Plane から Runtime への通信を基本とし、Sandbox から Control Plane へ任意アクセスできる構成にはしない。

## Runtime Provider

Runtime backend は抽象化する。

MVP:

- `KubernetesRuntimeProvider`

将来候補:

- Docker
- Firecracker
- Kata Containers

Claude Code 等の Agent Provider と Runtime Provider は分離する。

## Agent Provider

MVP では最低限以下を扱う。

- Custom Command
- Claude Code

将来的に:

- Codex
- OpenCode
- Custom Agent

Agent 固有処理を Kubernetes 実装へ直接持ち込まない。

## Security

Runtime Pod は原則として以下を適用する。

- non-root
- `allowPrivilegeEscalation=false`
- `privileged=false`
- `readOnlyRootFilesystem=true`
- capabilities drop ALL
- seccomp RuntimeDefault
- `automountServiceAccountToken: false`

書き込み可能領域は原則:

- `/workspace`
- `/tmp`

### NetworkPolicy

default deny とする。

Ingress:

- DENY ALL

Egress:

- DENY ALL

必要に応じて以下のみ許可する。

- DNS
- HTTPS
- GitHub
- Anthropic API
- OpenAI API
- Package Registry

以下は Sandbox から遮断する。

- Kubernetes API
- exkes-api
- exkes-controller
- MariaDB
- Ceph 管理 endpoint
- 他 Runtime

## Credential

Credential を Runtime Image や Workspace に恒久保存しない。

例:

- GitHub App Token
- Claude API Key
- OpenAI API Key
- Package Registry Token

可能な限り短命 Credential を利用し、scope は以下を想定する。

- Workspace Scope
- Runtime Scope
- Execution Scope

Execution Scope を優先する。

## Repository checkout

Workspace に Git Repository を checkout できるようにする。

Credential は checkout 時のみ一時注入し、Workspace に長期保存しない。

## Logging / Artifact

Execution の stdout / stderr は Runtime Pod の単純な Pod Log として扱わず、Execution 単位で収集する。

保存先:

- MariaDB: metadata
- Ceph RGW: log body / artifact body

Artifact 例:

- build.zip
- test-report.xml
- patch.diff
- agent-result.json

## Resource 制限

Runtime 単位で以下を設定可能にする。

- CPU request / limit
- Memory request / limit
- Ephemeral Storage
- Workspace quota

## Runtime TTL / Idle

Runtime には必ず TTL を設定する。

期限到達時:

```text
RUNNING
  |
  v
EXPIRED
  |
  v
STOP / DELETE
```

将来的に Idle Timeout による停止も追加可能とする。

Workspace は Runtime 停止・削除後も保持できる。

## API 初期案

### Workspace

```
POST   /workspaces
GET    /workspaces/{id}
DELETE /workspaces/{id}
POST   /workspaces/{id}/checkout
```

### Runtime

```
POST   /runtimes
GET    /runtimes/{id}
POST   /runtimes/{id}/start
POST   /runtimes/{id}/stop
DELETE /runtimes/{id}
```

### Execution

```
POST /runtimes/{id}/executions
GET  /executions/{id}
POST /executions/{id}/cancel
GET  /executions/{id}/logs
```

### Terminal

```
POST /runtimes/{id}/terminal-sessions
```

### Artifact

```
GET /executions/{id}/artifacts
GET /artifacts/{id}
```

## MVP タスク

- [ ] プロジェクト構成を決定する
- [ ] MariaDB schema / migration を作成する
- [ ] Workspace domain を実装する
- [ ] CephFS + Ceph CSI による Workspace provisioning を実装する
- [ ] Runtime domain / state machine を実装する
- [ ] KubernetesRuntimeProvider を実装する
- [ ] exkes-controller の reconciliation を実装する
- [ ] runtime-agent を実装する
- [ ] Command Execution を実装する
- [ ] Execution stdout / stderr streaming を実装する
- [ ] Ceph RGW への log 保存を実装する
- [ ] Artifact upload / metadata 管理を実装する
- [ ] Repository checkout を実装する
- [ ] 一時 Credential 注入を実装する
- [ ] Runtime SecurityContext を適用する
- [ ] NetworkPolicy default deny を実装する
- [ ] Runtime TTL cleanup を実装する
- [ ] Terminal Gateway / PTY 接続を実装する
- [ ] Claude Code Agent Provider を実装する
- [ ] Runtime / Execution API を実装する
- [ ] Audit Log を実装する
- [ ] 基本 Metrics を追加する
- [ ] E2E テストを追加する

## MVP 完了条件

- [ ] API から Workspace を作成できる
- [ ] Workspace が CephFS 上に永続化される
- [ ] API から Kubernetes Runtime を作成できる
- [ ] Runtime に既存 Workspace を attach できる
- [ ] Runtime 内で任意コマンドを実行できる
- [ ] Claude Code を Execution として起動できる
- [ ] ユーザーが同一 Runtime に Terminal 接続できる
- [ ] stdout / stderr を Execution 単位で取得できる
- [ ] Log / Artifact が Ceph RGW に保存される
- [ ] Runtime を削除しても Workspace が保持される
- [ ] Runtime 再作成時に同じ Workspace を再接続できる
- [ ] CPU / Memory limit が適用される
- [ ] Sandbox から Kubernetes API / Control Plane / 他 Runtime にアクセスできない
- [ ] Runtime TTL 超過時に自動削除される

## MVP では扱わないもの

- Docker Runtime Provider
- Firecracker Runtime Provider
- Kata Containers
- Multi Cluster
- GPU Scheduling
- Runtime Pool
- Warm Runtime
- Workspace Snapshot
- Workspace Migration
- Checkpoint / Restore
- Workflow / Retry Policy
- Agent 間依存関係
- Scheduler 機能

Scheduler、Retry Policy、タスク計画等は `ai-scheduler` 等の上位システムの責務とする。

