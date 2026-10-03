<!--
  検証した issue のスナップショット（2026-10-01 時点の issue #2 本文・原文ママ）。
  デプロイ方針（stg 自動デプロイ / prod 手動昇格 / 再ビルドなし / 環境は 2 つだけ）の
  基準として実装計画・CI・platform マニフェストが参照している。
  2026-10-03 に issue #2 は「Epic + 子 issue #3 / #4 / #5」へ再構成されたが、
  デプロイ規則（2 環境・手動昇格・同一 image の昇格）自体は変わらない。
  本文の image 記法 `private/exkes:main-<sha>` は単一 Deployment を前提としたもので、
  本リポジトリは 2 プロセス（exkes-api / exkes-controller）を別 image に分割するため
  `private/exkes-<module>:main-<sha>` に変えている（意図的な逸脱。理由は
  docs/implementation-plan.md の 0 章・行 4）。
-->

## 概要

`exkes` を `kigawa-net/platform` 経由で Kubernetes クラスタへデプロイできるようにする。

デプロイ規則は以下とする。

- `main` への push → **stg へ自動デプロイ**
- `main` 上で `workflow_dispatch` → **prod へ手動昇格**
- prod では再ビルドせず、**現在 stg で利用中の同一イメージをそのまま promote する**

## 目的

stg と prod で同一バイナリを保証しつつ、既存の `platform` GitOps 運用へ exkes を統合する。

## デプロイフロー

### stg

```text
kigawa-net/exkes
    |
    | main push
    v
GitHub Actions
    |
    +-- test
    +-- container build
    +-- Harbor push
    |
    v
harbor.kigawa.net/private/exkes:main-<sha>
    |
    v
kigawa-net/platform
    |
    | exkes/stg の image tag 更新
    v
Argo CD
    |
    v
platform-exkes-stg
```

### prod

```text
kigawa-net/exkes
    |
    | workflow_dispatch on main
    v
GitHub Actions
    |
    | platform/exkes/stg の現在の image を取得
    v
platform/exkes/main へ同一 image を反映
    |
    v
Argo CD
    |
    v
platform-exkes-main
```

prod 用 workflow ではイメージの再ビルドを行わない。

## platform 側構成

```text
platform/
├─ apps/
│  ├─ exkes-stg-app.yml
│  └─ exkes-main-app.yml
│
└─ exkes/
   ├─ stg/
   │  ├─ ns.yaml
   │  ├─ exkes.yaml
   │  ├─ service.yaml
   │  ├─ ingress.yaml
   │  ├─ mariadb.yaml
   │  ├─ rbac.yaml
   │  ├─ network-policy.yaml
   │  └─ ...
   │
   └─ main/
      ├─ ns.yaml
      ├─ exkes.yaml
      ├─ service.yaml
      ├─ ingress.yaml
      ├─ mariadb.yaml
      ├─ rbac.yaml
      ├─ network-policy.yaml
      └─ ...
```

Namespace:

- stg: `platform-exkes-stg`
- prod: `platform-exkes-main`

Argo CD Application:

- `platform-exkes-stg-app`
- `platform-exkes-main-app`

## exkes 側 workflow

### .github/workflows/deploy-stg.yml

Trigger:

```yaml
on:
  push:
    branches:
      - main
```

処理:

1. Test
2. Container build
3. Harbor login
4. `harbor.kigawa.net/private/exkes:main-${GITHUB_SHA}` を push
5. GitHub App token を発行
6. `kigawa-net/platform` を checkout
7. `platform/exkes/stg` の image tag を更新
8. commit / push
9. Argo CD により自動同期

### .github/workflows/deploy-prod.yml

Trigger:

```yaml
on:
  workflow_dispatch:
```

main 以外からは実行しない。

```yaml
if: github.ref == 'refs/heads/main'
```

処理:

1. `kigawa-net/platform` を checkout
2. `exkes/stg` の現在の image を取得
3. 同じ image を `exkes/main` へ反映
4. commit / push
5. Argo CD により自動同期

**prod では build / push を行わない。**

## Container Registry

```text
harbor.kigawa.net/private/exkes:main-<commit-sha>
```

stg / prod で同一タグを利用する。

## Kubernetes / platform 共通設定

既存の `platform` 規約に合わせる。

- Argo CD project: `platform`
- Ingress Class: `haproxy`
- Registry: `harbor.kigawa.net`
- Secret: Bitwarden Secrets Manager
- DB: MariaDB Operator
- MariaDB storage: `rook-ceph-rbd`
- Workspace storage: `rook-cephfs`

## MariaDB

環境ごとに専用 MariaDB を作成する。

例:

```text
platform-exkes-stg
  exkes-db

platform-exkes-main
  exkes-db
```

DB:

```text
database: exkes
user: exkes
```

## Workspace

Runtime Workspace は CephFS を利用する。

```yaml
storageClassName: rook-cephfs
```

Workspace は Runtime Pod と独立して永続化できるようにする。

## Controller RBAC

`exkes-controller` のみ Sandbox Runtime の管理権限を持つ。

必要権限候補:

- Pods
- PersistentVolumeClaims
- Secrets
- Services
- NetworkPolicies

`exkes-api`、`terminal-gateway`、Runtime Pod には Kubernetes Runtime 作成権限を与えない。

Runtime Pod は以下を基本とする。

```yaml
automountServiceAccountToken: false
```

## Sandbox Namespace

Control Plane と Runtime を分離する。

候補:

```text
platform-exkes-stg
platform-exkes-stg-sandbox

platform-exkes-main
platform-exkes-main-sandbox
```

Controller から対応する Sandbox Namespace のみ操作可能にする。

## タスク

### exkes repository

- [ ] 最小起動可能な exkes アプリを実装する
- [ ] `/health` endpoint を実装する
- [ ] Dockerfile を追加する
- [ ] `.github/workflows/deploy-stg.yml` を追加する
- [ ] main push で test を実行する
- [ ] Harbor へ `main-<sha>` を push する
- [ ] GitHub App 経由で platform repository を更新する
- [ ] `.github/workflows/deploy-prod.yml` を追加する
- [ ] prod workflow を main branch 限定にする
- [ ] prod では stg image をそのまま promote する
- [ ] prod workflow では再ビルドしない

### platform repository

- [ ] `exkes/stg/` を追加する
- [ ] `exkes/main/` を追加する
- [ ] `apps/exkes-stg-app.yml` を追加する
- [ ] `apps/exkes-main-app.yml` を追加する
- [ ] stg / main Namespace を作成する
- [ ] MariaDB Operator resource を追加する
- [ ] BitwardenSecret を追加する
- [ ] exkes Deployment / Service を追加する
- [ ] Ingress を追加する
- [ ] Controller RBAC を追加する
- [ ] Sandbox Namespace を追加する
- [ ] Sandbox NetworkPolicy を追加する
- [ ] Workspace 用 CephFS 設定を追加する
- [ ] 必要な namespace を Bitwarden secret sync 対象へ追加する
- [ ] Harbor pull secret が利用可能なことを確認する

## 完了条件

- [ ] `main` push で exkes image が Harbor に push される
- [ ] `main` push 後、platform の stg manifest が自動更新される
- [ ] Argo CD が `platform-exkes-stg` を同期する
- [ ] stg の `/health` が 200 を返す
- [ ] stg から MariaDB へ接続できる
- [ ] `workflow_dispatch` で prod へ promote できる
- [ ] prod で stg と同一 image digest / tag が利用される
- [ ] prod workflow で container build が行われない
- [ ] Argo CD が `platform-exkes-main` を同期する
- [ ] Control Plane と Sandbox の Namespace / RBAC が分離されている

## 対象外

この Issue では以下の Runtime 機能そのものの完成は必須としない。

- Claude Code execution
- Terminal Gateway
- Execution log persistence
- Ceph RGW artifact upload
- Runtime TTL
- Agent Provider

まずは exkes Control Plane を platform 上へ継続デプロイできる CI/CD と Kubernetes 基盤を整える。

