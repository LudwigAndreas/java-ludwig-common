# TDD: GitOps CI/CD for the Ludwig Platform

Nov 3, 2026 · @Ludwig Andreas

## 1. Goals and success metrics

One Jenkinsfile builds every commit. Git tags set the version. Jenkins writes image digests into the GitOps repo, and Argo CD deploys from there. Nobody copies an image tag or hash by hand again.

**Goals**

- Remove every manual copy-paste between `jib-image.json`, the OpenShift UI and the GitOps repo.
- One pipeline definition for dev and production: the same gates everywhere, only how strictly they block differs.
- Versions come from git tags (GitVersion), and a release is a tag.
- Auto-deploy to **dev** after a merge to `master`, to **IFT** after a release tag, and to **PSI (UAT)** with one click after IFT sign-off.
- Production changes only through an automatic PR into the GitOps `master`, approved by a second person.
- 4 developers test PRs in parallel without overwriting each other.
- Build once, promote the same image digest: what PSI tested is byte-for-byte what prod runs.

**Non-goals**

- Replacing Jenkins, Bitbucket, Nexus, Argo CD or OpenShift.
- Fully automatic production deploys with no human approval.
- Per-service release versions: one monorepo, one version (decided).

**Success metrics**

| Metric                                | Today                                           | Target                                  |
| ------------------------------------- | ----------------------------------------------- | --------------------------------------- |
| Manual steps per release              | \~5 per service (copy, paste, branch, PR, sync) | 2 (promote to PSI, approve the prod PR) |
| Merge to running on dev               | manual, unbounded                               | ≤ 15 min, no human                      |
| Tag to running on IFT                 | manual                                          | ≤ 30 min, no human                      |
| Jenkins pipeline definitions          | 2 (`backendBuild`, `backendUpload`)             | 1 Jenkinsfile + 1 shared library        |
| Rollback to the previous prod version | manual re-paste                                 | 1 revert PR                             |

## 2. Current state and what replaces each manual step

Today the pipeline stops at "image pushed". Everything after that is done by hand, and that is where the time and the mistakes go.

**Current pipelines.** `backendBuild` (dev) and `backendUpload` (prod) are near-duplicates. Both set the version from the branch name, build and push jib images to Nexus in one step, run AppSec (SAST, SCA quality gates) and Sonar, then publish release notes. The output is an archived `jib-image.json` per service.

**Pain points**

- Two pipelines drift: a gate fixed in one is forgotten in the other.
- The version comes from the branch name, not from tags, so the same commit can get different versions.
- Deploying means copying a tag or hash by hand, either into the OpenShift UI (which bypasses git, so Argo reverts it or drifts) or into the GitOps repo.
- A release copies N digests by hand, and one typo ships the wrong image.
- Any developer can overwrite the shared dev stand with a PR image while someone else is testing.
- There is no record of "what runs where" except the cluster itself.

**Traceability: each manual step to its replacement**

| Manual step today                                                     | Replaced by                                                                                                                   | Section |
| --------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ------- |
| Version set from the branch name                                      | GitVersion over tags; a release = `vX.Y.Z` tag                                                                                | 4       |
| Two pipelines with different gates                                    | One Jenkinsfile; behaviour chosen by PR / `master` / tag                                                                      | 5       |
| Copy a tag from `jib-image.json` into the OpenShift UI                | Forbidden. Every deploy goes through git; Argo `selfHeal` reverts UI edits                                                    | 8, 9    |
| Copy a tag into GitOps, branch, push, sync for dev                    | Jenkins pushes the digests to the GitOps `develop` branch on merge to `master`; Argo auto-syncs                               | 5, 8    |
| Testing a PR on dev                                                   | On-demand preview in a fixed slot, dev-1 to dev-4                                                                             | 6       |
| Copy every digest for a release into helm/kustomize                   | The tag pipeline writes all digests to the `ift` branch; `promote` copies them to `psi`, then opens the prod PR into `master` | 5       |
| Create a branch named after the Jira release ticket, PR into `master` | Done by the tag pipeline; the ticket comes from the Jira fixVersion                                                           | 5, 7    |
| Merge, then sync Argo on prod                                         | A second person approves and merges; Argo syncs (manual button or a sync window)                                              | 9       |
| Publish release notes                                                 | Generated from Jira fixVersion + `CHANGELOG.md` at the tag                                                                    | 5       |

## 3. Target architecture

The GitOps repo is the single source of truth for what runs where. Jenkins builds and writes digests into it, Argo CD is the only thing that deploys, and people do two things: click promote after IFT sign-off, and approve the prod PR.

&#91;embedded content: target architecture · who writes what\]

Jenkins never talks to a cluster, so a pipeline credential can't change prod. The clusters pull images from Nexus by digest.

| Repo                                       | Owns                                                                        | Who writes                                                              |
| ------------------------------------------ | --------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| App monorepo (this repo)                   | Code, `Jenkinsfile`, version tags                                           | Developers via PRs; `release-cut` for tags                              |
| `ludwig-gitops`                            | One branch per stand (develop, ift, psi, master), each with one images file | CI bot pushes to develop, ift, psi; master (prod) only by PR + approval |
| `ludwig-pipeline` (Jenkins shared library) | All pipeline logic and utility jobs                                         | Platform owner via PRs, released with `vN` tags                         |

## 4. Versioning and tagging

A release is a `vX.Y.Z` git tag on `master`, and nothing else. Every deploy references an image **digest**, never a tag. The repo already does most of this: `Jenkinsfile` runs GitVersion and turns the result into `x.y.z` or `x.y.z-SNAPSHOT`.

**Rules**

- The branching model is trunk-based: `master` plus short-lived branches `feature/*` and `hotfix/*`, merged through PRs.
- GitVersion computes `MajorMinorPatch`. The pipeline adds `-SNAPSHOT` unless HEAD carries a tag, as it does today.
- `+semver: minor` / `+semver: major` in a commit message bumps the version. Otherwise each version after the last tag is a patch bump.
- The whole monorepo shares one version. A tag releases every library and every service image at once.
- The image tag is set with `-Dludwig.image.tag=...`. The property already exists in `build/ludwig-service-parent/pom.xml`, with `${project.version}` as its default.

| Trigger           | Maven version (`-Drevision`)     | Image tag            | Maven repo | Where it can deploy      |
| ----------------- | -------------------------------- | -------------------- | ---------- | ------------------------ |
| PR #123           | `1.4.0-SNAPSHOT` (not published) | `1.4.0-pr123.<sha7>` | none       | preview slot only        |
| Merge to `master` | `1.4.0-SNAPSHOT`                 | `1.4.0-<sha7>`       | snapshots  | dev                      |
| Tag `v1.4.0`      | `1.4.0` with `-Prelease`         | `1.4.0`              | releases   | IFT, then PSI, then prod |

**Why digests.** A tag can be pushed again over a different image, but a digest can't change. The pipeline reads each service's digest from jib's `target/jib-image.digest` / `jib-image.json` and writes it into Git. This is the step people do by hand today. Nexus also marks release tags (`x.y.z`) as non-overwritable (a repository setting).

**Build once, promote.** The tag pipeline builds the release images once. IFT, PSI and prod get **the same digest**: promoting means copying a line in Git, not rebuilding. `master` images never go to IFT, PSI or prod, because they carry a SNAPSHOT version inside.

**Changes to the repo**

- `gitversion.yml`: remove the `develop` branch block. Trunk-based work has no integration branch, and that block makes `develop` bump the minor version.
- `gitversion.yml`: keep `release/*` only for patching an old release line (a hotfix on `v1.3.x` while `master` is already at 1.4). Otherwise it is unused.

## 5. Pipelines

One multibranch job and one `Jenkinsfile` replace `backendBuild` and `backendUpload`. The trigger (PR, `master`, tag) decides which stages run and which gates **block**. The gates themselves are the same everywhere, so a problem shows on the PR, not at release time.

**Multibranch setup (Bitbucket Branch Source)**

- Discover PRs (merged with the target) and branches, but only `master` among the branches. Feature branches build through their PR, so nothing builds twice.
- Discover tags matching `v*`. Tag builds are triggered automatically (the _Build tags_ strategy with a max age of 1 day, so old tags don't rebuild).
- Bitbucket Server webhook. No polling.
- Concurrency: PRs use `disableConcurrentBuilds(abortPrevious: true)`, because the newest commit wins. `master` and tags queue and are never aborted.

**Stages by trigger**

| Stage                                                                         | PR                                      | `master`         | tag `vX.Y.Z`                                       | Notes                                                               |
| ----------------------------------------------------------------------------- | --------------------------------------- | ---------------- | -------------------------------------------------- | ------------------------------------------------------------------- |
| Checkout (full history + tags)                                                | yes                                     | yes              | yes                                                | As today: GitVersion needs it                                       |
| Version (GitVersion)                                                          | yes                                     | yes              | yes                                                | Sets `REVISION`, `IMAGE_TAG`                                        |
| Build + unit + integration tests                                              | yes                                     | yes              | yes                                                | `mvn install`; JUnit + JaCoCo published                             |
| Repo checks (`scripts/gate.sh`: image pins, migrations, protos, API baseline) | yes                                     | yes              | yes                                                | Already in repo                                                     |
| Sonar                                                                         | PR analysis, decorates the Bitbucket PR | branch analysis  | branch analysis                                    | Quality gate: report on PR, **block** on `master`/tag               |
| AppSec SAST + SCA                                                             | yes, report only                        | yes, report only | yes, **block**                                     | Prod-only gate today; earlier feedback, same tool                   |
| Publish Maven artifacts                                                       | no                                      | snapshots        | releases (`-Prelease`)                             |                                                                     |
| Build + push images (jib)                                                     | yes, `pr<id>` tag                       | yes              | yes                                                | Digests collected into `images.json`                                |
| SBOM + image signing                                                          | no                                      | no               | yes                                                | CycloneDX + cosign, see section 10                                  |
| GitOps write                                                                  | preview only, on request                | `develop` branch | `ift` branch; `promote` → `psi` + PR into `master` | Bot commit, see section 8                                           |
| Release notes                                                                 | no                                      | no               | yes                                                | Jira fixVersion + `CHANGELOG.md` section; posted to Confluence/Jira |
| Notify                                                                        | Bitbucket build status                  | chat on failure  | chat + Jira                                        |                                                                     |

**Rule for blocking gates.** Sonar and AppSec run on PRs too, so nobody first learns about a security finding at release time. A PR can merge only with a green build and the Sonar gate passing. The Bitbucket merge check "minimum successful builds = 1" enforces this. AppSec blocks only at the tag, because a scan that takes hours shouldn't block a merge. If it finds a problem at the tag, the fix goes in as a new patch tag.

**What happens after the tag build**

1. The tag pipeline replaces `images/kustomization.yaml` on the GitOps `ift` branch with all release digests and pushes. Argo auto-syncs IFT.
2. It waits for IFT to be healthy: polls the Argo API for `Synced` + `Healthy`, with a 20 min timeout. Optionally it runs a smoke or API test suite against IFT, then comments the result on the Jira release ticket.
3. After IFT sign-off, someone runs `promote` (ift → psi), one click. It copies `images/`, `charts/` and `base/` from `ift` to `psi` and pushes. Argo auto-syncs PSI with the **same digests**.
4. Once PSI is healthy, `promote` creates the branch `<JIRA-RELEASE-KEY>` (for example `REL-1234`) from GitOps `master`, copies the same paths into it and opens a PR into `master`. Release notes and links to the build, Sonar and AppSec go in the description, and the reviewers come from CODEOWNERS.
5. It comments the PR link on the Jira release ticket. A second person approves and merges, and Argo syncs prod.

**Jenkinsfile in the app repo (all logic lives in the shared library)**

```groovy
@Library('ludwig-pipeline@v1') _

ludwigPipeline(
    services: [':crud-service-example', ':notification-service'], // modules that produce images
    gitopsRepo: 'ssh://git@bitbucket/ops/ludwig-gitops.git',
    jiraProject: 'REL'
)
```

The shared library is versioned with tags (`@v1`), so a pipeline change is reviewed and rolled out like code. The comments in today's `Jenkinsfile` (why `install`, why a full checkout, credentials per stage) move into it unchanged.

## 6. Dev isolation for parallel developers

Shared **dev** runs `master` and nothing else. A PR that needs a running stand borrows one of four fixed preview namespaces, `dev-1` to `dev-4`. Your new commit then updates your preview and nobody else's.

**Rules**

- Every PR builds, tests, scans and pushes an image (`x.y.z-pr<id>.<sha7>`). This feeds Sonar, coverage and the Bitbucket build status. Building a PR **never** deploys to shared dev.
- `master` is the only source for dev. Once a PR is merged it reaches dev within one pipeline run, so dev always shows "what is integrated".
- A preview is created **on demand**, not for every PR: most PRs don't need a running stand, and there are only four slots.

**How a preview works**

1. The developer comments `/preview` on the PR. A Bitbucket webhook (`pr:comment:added`) triggers the `preview` Jenkins job through the Generic Webhook Trigger plugin. Running the job by hand with a `PR_ID` parameter works too.
2. The job claims a free slot through the Jenkins lockable-resources plugin and writes `previews/<slot>/` on the GitOps `develop` branch: a `kustomization.yaml` that points to the `preview` base and sets the PR's latest image digests. Then it comments the URL on the PR.
3. An Argo CD ApplicationSet with a **Git directory generator** over `previews/*` deploys the app into that slot's existing namespace. Argo never creates a namespace.
4. Each new PR build that has a preview open updates the digests in its `previews/<slot>/` automatically.
5. When the PR is merged or declined, the `preview-gc` job deletes the directory and releases the slot. Argo prunes the app; the empty namespace stays for the next PR. A nightly sweep also frees slots whose preview had no commit for 3 days.

**Why not Argo's Pull Request generator.** On Bitbucket Server it can only filter by branch name, because there are no labels. It would deploy _every_ open PR, and the image tag would have to be guessed from the head SHA. Having Jenkins write a directory keeps one rule for every environment: Git says what runs, and Jenkins is the only writer.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata: { name: ludwig-previews, namespace: argocd }
spec:
  goTemplate: true
  generators:
    - git:
        repoURL: ssh://git@bitbucket/ops/ludwig-gitops.git
        revision: develop # previews live on the dev stand's branch
        directories: [{ path: previews/* }]
  template:
    metadata: { name: "ludwig-{{.path.basename}}" }
    spec:
      project: previews
      source:
        {
          repoURL: ssh://git@bitbucket/ops/ludwig-gitops.git,
          targetRevision: develop,
          path: "{{.path.path}}",
        }
      destination: {
          server: https://kubernetes.default.svc,
          namespace: "ludwig-{{.path.basename}}",
        } # slot namespace, pre-created
      syncPolicy:
        automated: { prune: true, selfHeal: true }
```

**What a preview contains**

| Component           | In a preview                                                                            | Why                                                         |
| ------------------- | --------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| Services under test | all images from the PR build                                                            | One version: a preview is the whole platform at that commit |
| PostgreSQL          | its own, small (ephemeral chart or a CloudNativePG cluster)                             | Migrations from the PR must not touch the shared dev DB     |
| Kafka               | shared dev Kafka, topics and consumer groups prefixed `pr-<id>.`                        | A broker per PR is too heavy                                |
| Keycloak / identity | shared dev realm, an extra redirect URI by wildcard                                     | Read-only for the preview                                   |
| Route               | `ludwig-<slot>.apps.<dev-cluster>`                                                      | Fixed per slot, posted on the PR                            |
| Limits              | a `ResourceQuota` + `LimitRange` on each slot namespace, set once by the cluster admins | Four slots, so at most four previews at once                |

Why fixed slots: the dev cluster does not allow creating namespaces. So the four namespaces dev-1 to dev-4 are created once by the cluster admins and reused. A claim holds while the PR is open and is released by preview-gc. If all four are taken, /preview replies on the PR that no slot is free. The ApplicationSet works the same; the namespace name comes from the slot, not the PR id.

## 7. Utility pipelines

Eight small jobs cover what the main pipeline shouldn't do: cutting a release, moving or reverting a deploy, and cleanup. All of them live in the shared library and are defined as code with Job DSL or `jobs/*.groovy`, so none of them is set up by clicking.

| Job                      | Trigger                                               | What it does                                                                                                                                                                                                                                                                                                                     | Priority |
| ------------------------ | ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| `release-cut`            | manual, input: Jira release key                       | Checks `master` is green. Runs GitVersion for the next version, or takes a bump the user picks. Checks that `CHANGELOG.md` and `CHANGELOG.ru.md` have the heading, since `-Prelease` requires it. Creates the annotated tag `vX.Y.Z` and pushes it, which starts the tag pipeline. Sets the Jira fixVersion and links the ticket | Must     |
| `promote`                | manual, inputs: version, from env, to env             | Copies `images/`, `charts/` and `base/` from one stand branch to the next: `develop` → `ift` → `psi` → PR into `master`. Never a git merge between stand branches (section 8). Used for the normal IFT → PSI step, to re-deploy an older release, or for a hotfix. Pushes to `ift`/`psi`, opens a PR for `master`                | Must     |
| `rollback`               | manual, input: env                                    | Reverts the last `images/` commit on the stand's branch: pushes the revert to `develop`, `ift` or `psi`, and opens a revert PR for `master`. The DB is not rolled back: see section 9                                                                                                                                            | Must     |
| `preview` / `preview-gc` | PR comment `/preview`; PR merged or declined; nightly | Claim or release a slot; create, update or delete `previews/<slot>/` (section 6)                                                                                                                                                                                                                                                 | Must     |
| `gitops-lint`            | every PR in the GitOps repo                           | `kustomize build` every overlay, `kubeconform` against the OpenShift schemas, a check that every image has a digest (reuse the `scripts/check_image_pins.py` rule), and a policy check (conftest/kyverno CLI: limits set, no `latest`, no privileged)                                                                            | Must     |
| `nightly`                | cron, 02:00                                           | A full AppSec SAST + SCA scan of `master`, a dependency CVE scan of the images that run in **prod** (from `images/` on the `master` branch), and a report to chat                                                                                                                                                                | Should   |
| `registry-cleanup`       | weekly                                                | Nexus cleanup policy: delete `*-pr*` images after 14 days and `x.y.z-<sha>` images after 30 days. Never delete `x.y.z` releases or any digest referenced on any stand branch                                                                                                                                                     | Should   |
| Renovate                 | scheduled bot                                         | PRs for Maven dependency bumps, the base image digest (`ludwig.image.base`), the GitVersion image and Jenkins plugins. Through the normal PR flow                                                                                                                                                                                | Should   |

Later, and not needed on day one: a DORA dashboard built from the GitOps history, and an Argo CD Image Updater. That one isn't needed because Jenkins already writes the digests.

## 8. GitOps repo design

One GitOps repo (`ops/ludwig-gitops`) has **one branch per stand**. Every branch has the same file tree. Each branch holds its own stand's config plus **one images file** with the digest of every service, and that file is the only one CI edits.

| Branch    | Stand               | How it changes                                                       |
| --------- | ------------------- | -------------------------------------------------------------------- |
| `develop` | dev + preview slots | Bot pushes on every app `master` merge and on `/preview`             |
| `ift`     | IFT                 | Bot pushes on every `vX.Y.Z` tag                                     |
| `psi`     | PSI (UAT)           | Bot pushes when `promote ift → psi` runs                             |
| `master`  | prod                | **PR + approve + merge only.** The bot opens the PR and never pushes |

```text
ludwig-gitops/                     # same tree on every branch
├── images/kustomization.yaml      # THE images file: every service's digest. Written by CI only
├── charts/ludwig-service/         # generic chart: Deployment, Service, Route, HPA, PDB, ServiceMonitor
├── base/<service>/                # helmCharts → ../../charts/ludwig-service + values.yaml
├── env/                           # this stand only: replicas, limits, routes, config, ExternalSecrets
│   └── kustomization.yaml         # resources: ../base/*  components: [../images]
├── previews/<slot>/               # develop branch only, written by the preview jobs
├── argocd/                        # master branch only: AppProjects + ApplicationSets
└── CODEOWNERS
```

**The images file** (`images/kustomization.yaml`, on the `ift` branch here)

```yaml
apiVersion: kustomize.config.k8s.io/v1alpha1
kind: Component
# Written by Jenkins. Do not edit by hand; use the promote/rollback jobs.
# version: 1.4.0  build: https://jenkins/job/ludwig/job/v1.4.0/1/
images:
  - name: crud-service-example
    newName: nexus.example.internal/ludwig/crud-service-example
    digest: sha256:3b1f...
  - name: notification-service
    newName: nexus.example.internal/ludwig/notification-service
    digest: sha256:9ac0...
```

Every write replaces the whole file with the full set of services. A commit therefore always describes one complete, consistent release, and a revert restores the previous complete set.

**Promotion copies files; it never merges branches.** `git merge develop → ift` would also carry dev's digests and dev's `env/` into IFT. So `promote` copies a fixed set of paths from the source branch to the target: `images/`, `charts/` and `base/`. It leaves `env/` alone. The chart change that a release needs therefore travels with the release. A change to one stand's own config is a normal PR into that stand's branch.

**How the bot writes**

- A dedicated Bitbucket service account with an SSH key and write access to this repo only. Jenkins credential `gitops-bot`.
- Writes are serialized per branch with `lock(resource: "gitops-<branch>")`: `git pull --rebase`, write, commit, push, retrying 3 times on a push race. Two `master` builds finishing together therefore can't lose a write.
- The commit message names the version, the app commit SHA, the build URL and the Jira key, for example `dev: 1.4.0-a1b2c3d (REL-1234) build #57`. Each branch's history _is_ that stand's deploy log.
- `develop`, `ift`, `psi`: direct push, through a branch-permission exception for the bot only.
- `master`: the bot creates a `<JIRA-RELEASE-KEY>` branch from `master`, commits there and opens the PR. It cannot push to `master`.

**Branch protection**

- `master`: no direct pushes for anyone, the bot included. A PR needs 1 approval from CODEOWNERS (not the author), a green `gitops-lint` and no unresolved tasks. Squash merge, so each release is one commit and one revert.
- `develop`, `ift`, `psi`: people change `env/` through PRs with 1 approval. Only the bot pushes directly.
- `images/` on every branch: `gitops-lint` fails any PR not opened by the bot that changes it. Use `promote` or `rollback` instead.

## 9. Argo CD configuration

Argo CD watches one GitOps branch per stand: `develop` (dev and the preview slots), `ift`, `psi` and `master` (prod). dev, IFT, PSI and previews sync automatically. Prod changes only when an approved PR is merged into `master`, and then syncs either by an operator pressing Sync or automatically inside a sync window (an open question). Manual changes in the OpenShift UI get reverted, which ends the "paste a hash into the UI" path.

| Env     | AppProject      | Source (branch · path)                    | Sync policy                                                           | Who can sync                        |
| ------- | --------------- | ----------------------------------------- | --------------------------------------------------------------------- | ----------------------------------- |
| preview | `dev-1 … dev-4` | `develop` · `previews/*` (ApplicationSet) | auto, prune, selfHeal                                                 | bot (via Git)                       |
| dev     | `ludwig-dev`    | `develop` · `env/`                        | auto, prune, selfHeal                                                 | bot (via Git)                       |
| ift     | `ludwig-ift`    | `ift` · `env/`                            | auto, prune, selfHeal                                                 | bot (via Git)                       |
| psi     | `ludwig-psi`    | `psi` · `env/`                            | auto, prune, selfHeal                                                 | bot (via Git, `promote` job)        |
| prod    | `ludwig-prod`   | `master` · `env/`                         | manual sync (or auto + sync window, Mon–Thu 10:00–16:00), selfHeal on | release approvers (Argo RBAC group) |

**Settings**

- App-of-apps: one root app over `argocd/`, which holds the AppProjects and the ApplicationSets. The Argo config is then also in Git.
- Kustomize with Helm: `kustomize.buildOptions: --enable-helm` in `argocd-cm`, so `base/*` can inflate `charts/ludwig-service`.
- AppProjects restrict destinations (namespace per env) and source repos, and deny all cluster-scoped resources, previews included.
- Health: Argo's built-in Deployment health plus the readiness probes on `/actuator/health/readiness`. The chart sets `progressDeadlineSeconds`, so a crash-looping release reports `Degraded` instead of `Progressing` forever.
- Sync waves: `-1` for config and secrets, `0` for the services. Nothing else needs ordering.
- Notifications (argocd-notifications): sync succeeded or failed and health degraded, to the team chat. A prod sync also comments on the Jira release ticket.

**Database migrations.** The services run Liquibase at startup (`scripts/check_migrations.sh` enforces formatted SQL changesets). This stays as it is. A pod applies pending changesets before it reports ready, and Liquibase's lock table serializes concurrent pods. Two rules make that safe with rolling updates and rollbacks:

- Migrations must be **backward compatible with the previous release** (expand / contract): add a column now, drop the old one one release later. The old pods keep running during the rollout, and a rollback to N-1 still works against the N schema.
- `rollback` therefore reverts images only, never the schema. If a broken migration has to be undone, that is a forward fix: a new changeset and a new patch tag.

If startup migrations ever get too slow for the probes, the alternative is a `PreSync` hook Job running the same image with `spring.main.web-application-type=none`. It isn't needed now.

## 10. Security and compliance

Every credential is scoped to the one stage that needs it, every deployed image is pinned by digest, and the audit trail is the Git history plus the Jira key in each prod branch.

| Concern                   | Control                                                                                                                                                                                                                     |
| ------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| CI credentials            | Jenkins credentials bound per stage with `withCredentials`, as in today's `Jenkinsfile`: `nexus-registry` (push), `sonar-token`, `appsec-token`, `gitops-bot` (SSH, GitOps repo only), `jira-bot`. None in POMs or Git      |
| Cluster access from CI    | **None.** Jenkins never runs `oc apply`. Only Argo CD has deploy rights, so a leaked Jenkins token cannot deploy to prod                                                                                                    |
| Image identity            | Digest-pinned everywhere: `gitops-lint` fails on a tag-only image, and `scripts/check_image_pins.sh` covers the app repo                                                                                                    |
| Image provenance          | At the tag: a CycloneDX SBOM attached to the release, and images signed with cosign (key in Jenkins or the company KMS). Optional enforcement with an OpenShift/Kyverno policy that requires the signature in `ludwig-prod` |
| Release immutability      | Nexus: redeploy disabled for releases (`x.y.z` jars and image tags)                                                                                                                                                         |
| Prod change approval      | GitOps PR + CODEOWNERS + Argo RBAC. The author of the release can't be its only approver                                                                                                                                    |
| Audit trail               | GitOps commits show what ran where and when; app tags show which code; the Jira release ticket name is the prod branch                                                                                                      |
| Runtime secrets           | Not in Git as plaintext. Recommended: External Secrets Operator reading from Vault (or the company secret store), with `ExternalSecret` manifests in GitOps. Fallback: Sealed Secrets. Open question                        |
| Drift                     | `selfHeal` on every env, plus an alert on `OutOfSync` > 15 min in prod                                                                                                                                                      |
| Supply chain of CI itself | Shared library pinned by tag, tool images pinned by digest (already the rule for GitVersion), Renovate to keep them current                                                                                                 |

## 11. Developer workflows

A developer only touches the app repo, PR comments and the prod PR approval. Every other step is a pipeline.

**Feature or fix**

1. Branch `feature/<JIRA-KEY>-short-name` from `master`, push, open a PR.
2. The PR build runs tests, Sonar and AppSec and reports to Bitbucket. Need a running stand? Comment `/preview` and get a URL.
3. Review, green build, merge (squash). The preview is deleted, and within \~15 min `master` is on dev.

**Release**

1. Add the `## X.Y.Z` heading to `CHANGELOG.md` and `CHANGELOG.ru.md` in a normal PR.
2. Run `release-cut` with the Jira release key. It tags `vX.Y.Z`.
3. The tag pipeline builds once and deploys to IFT through the GitOps `ift` branch.
4. QA signs off IFT, then runs `promote` (ift → psi). PSI gets the same digests, and once PSI is healthy the prod PR `REL-1234` into GitOps `master` opens.
5. UAT on PSI. A second person approves and merges the prod PR, and Argo syncs prod.

**Hotfix on prod while `master` has moved on**

1. Branch `release/1.3.x` from tag `v1.3.0`, cherry-pick the fix, then tag `v1.3.1` on that branch.
2. The same flow runs: IFT, promote to PSI, then the prod PR. Merge the fix to `master` as well.

**Rollback**

1. Run `rollback` with `env=prod`. It opens a revert PR for the last image change.
2. Approve and merge it, and Argo syncs. The schema stays as it is (section 9).

**New service**

- [ ] Module under `services/`, inheriting `ludwig-service-parent` (jib config comes from there).
- [ ] Add `:<artifactId>` to `services:` in the `Jenkinsfile`.
- [ ] `base/<service>/` with its `values.yaml` in a GitOps PR into `develop`. `promote` carries it to `ift`, `psi` and `master` with the first release.
- [ ] On each stand branch, an entry in `env/kustomization.yaml` plus the route, secrets (`ExternalSecret`) and DB schema if needed: a PR per branch.
- [ ] First deploy happens on the next merge to `master`.

**New starter (library)**

Nothing deploy-related: it is a module under `sources/`, built, tested and published to Nexus with the rest. A consumer picks it up with the next platform version.

## 12. Migration plan

The migration runs in six phases, and each one is useful on its own. `backendBuild` and `backendUpload` keep running until phase 4 has carried one real release from tag to prod.

&#91;embedded content: migration · 6 phases, each with its exit criterion\]

Phases 1 to 3 change nothing for prod, so they can start at once. Phase 5 depends on the four slot namespaces and their quota being set up (section 13).

**Changes to this repo along the way**

- `Jenkinsfile`: shrinks to the `ludwigPipeline(...)` call. Its stages and comments move into the shared library.
- `gitversion.yml`: remove the `develop` branch block (section 4).
- `build/ludwig-service-parent/pom.xml`: no change. `ludwig.image.tag` and `ludwig.image.registry` are already overridable from the command line.

## 13. Open questions and assumptions

The design assumes Bitbucket Server/Data Center, one Argo CD instance with access to every cluster, and permission for Jenkins to push to the GitOps repo. Each item below either confirms an assumption or picks between two options.

- [ ] Bitbucket **Server/Data Center** or **Cloud**? Assumed Server: this affects webhooks, the PR comment trigger and path permissions.
- [ ] Argo CD: one instance for all clusters, or a separate instance for prod? Which version? It needs ApplicationSet + `goTemplate` (2.6+).
- [ ] Answered: the dev cluster cannot create namespaces, so previews use the fixed `dev-1`..`dev-4` slots. Still open: who creates the four namespaces, and how much quota does each get?
- [ ] Previews: is a shared dev Kafka with prefixed topics acceptable, or must each preview get its own?
- [ ] Secrets: is Vault (or another store) available for External Secrets Operator, or Sealed Secrets?
- [ ] AppSec tool: does it have a CLI/API Jenkins can call with a pass/fail result, and how long does a full scan take?
- [ ] Prod sync: must it be a manual button by company policy, or is auto-sync inside a window after an approved merge acceptable?
- [ ] Jira: is the release ticket key always known before the tag (as an input to `release-cut`), or created by the pipeline?
- [ ] Smoke/API tests against IFT before promotion to PSI: do they exist, or are they a follow-up?
- [ ] Is cosign signing required by security, or nice-to-have?

* [ ] Can the bot push directly to `develop`, `ift` and `psi`? Assumed yes. If not, it opens a PR there and Bitbucket auto-merges it on a green `gitops-lint`.
* [ ] IFT → PSI: a one-click `promote` after IFT sign-off (assumed), or automatic once the IFT smoke suite passes?
* [ ] Is the UAT branch named `psi` or `uat`? The doc uses `psi`.
