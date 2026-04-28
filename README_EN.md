<div align="center">

<img src="assets/full-logo.png#gh-light-mode-only" alt="Actionow" width="520" />
<img src="assets/full-logo-dark.png#gh-dark-mode-only" alt="Actionow" width="520" />

<h3><i>Action — Now.</i> &nbsp;The open-source AI studio for screenwriting and storyboard production.</h3>

<p>From script to storyboard, from character to final cut — Actionow turns the<br/>
director's call to <i>action, now</i> into an engineering-grade, agent-driven,<br/>
self-hostable workspace — onboard the latest models without a release, with code-level control and self-hosting baked in.</p>

<p><a href="https://www.actionow.ai"><b>actionow.ai</b></a></p>

<p>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0%20with%20additional%20conditions-blue.svg" alt="License"></a>
  <a href="https://t.me/+m1saPHQZlTIxZDg1"><img src="https://img.shields.io/badge/Telegram-Join%20Group-2CA5E0.svg?logo=telegram" alt="Telegram"></a>
  <a href="https://openjdk.org/projects/jdk/21/"><img src="https://img.shields.io/badge/Java-21-orange.svg" alt="Java 21"></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-3.4.1-6DB33F.svg" alt="Spring Boot"></a>
  <a href="https://spring.io/projects/spring-ai"><img src="https://img.shields.io/badge/Spring%20AI-1.1.2-6E4AFF.svg" alt="Spring AI"></a>
  <a href="https://nextjs.org/"><img src="https://img.shields.io/badge/Next.js-16-black.svg" alt="Next.js 16"></a>
  <a href="https://react.dev/"><img src="https://img.shields.io/badge/React-19-61DAFB.svg" alt="React 19"></a>
  <a href="https://www.docker.com/"><img src="https://img.shields.io/badge/Docker-Compose%20v2-2496ED.svg" alt="Docker Compose"></a>
  <img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs Welcome">
</p>

<p>
  <a href="README.md">中文</a> ·
  <strong>English</strong>
</p>

<p>
  <a href="#why-actionow">Why Actionow</a> ·
  <a href="#demo">Demo</a> ·
  <a href="#capabilities">Capabilities</a> ·
  <a href="#architecture">Architecture</a> ·
  <a href="#quick-start">Quick Start</a> ·
  <a href="#documentation">Docs</a> ·
  <a href="#roadmap">Roadmap</a>
</p>

</div>

---

## Why Actionow

Actionow targets the full pipeline of screenwriting, storyboard collaboration, and AIGC production with an agent-driven, self-hostable open-source workspace.

The platform organizes every creative action around the content graph **Script → Episode → Sequence → Storyboard → Character / Scene / Prop / Style / Asset**. Every entity is versioned and lineage-tracked; every interaction is observable, replayable, and reusable by agents.

| Audience                       | How they benefit                                                                                                    |
|--------------------------------|---------------------------------------------------------------------------------------------------------------------|
| Film, ad, and animation teams  | Connect writers, art, and production through agents; turn storyboards and assets into reusable inventory.           |
| AIGC studios                   | Wrap multimodal model capabilities as Skills and compose them per character or scene.                               |
| Enterprise AI platform teams   | Use this project as a reference implementation for an in-house Agent platform with a billing closed loop.           |
| AI engineers                   | Study how the Spring AI Alibaba Agent Framework can be productized in a multi-tenant environment.                   |

---

## Demo

Live: **[actionow.ai](https://actionow.ai)**

<div align="center">

<table>
  <tr>
    <td width="50%" align="center">
      <img src="assets/demo/screenshot-chat.png" alt="Agent Chat" width="100%" /><br/>
      <sub><b>Agent Chat</b></sub>
    </td>
    <td width="50%" align="center">
      <img src="assets/demo/screenshot-model.png" alt="Model Config" width="100%" /><br/>
      <sub><b>Model Config</b></sub>
    </td>
  </tr>
  <tr>
    <td width="50%" align="center">
      <img src="assets/demo/screenshot-mission.png" alt="Mission Console" width="100%" /><br/>
      <sub><b>Mission Console</b></sub>
    </td>
    <td width="50%" align="center">
      <img src="assets/demo/screenshot-inspiration.png" alt="Inspiration" width="100%" /><br/>
      <sub><b>Inspiration</b></sub>
    </td>
  </tr>
</table>

</div>

---

## Capabilities

<table>
<tr>
<td width="50%" valign="top">

### Multi-agent & custom Skills
Spring AI Alibaba orchestration with a built-in Skill library

- Mission tracing · live SSE progress
- JSON Schema output validation
- Three Skill scopes: system / workspace / user

</td>
<td width="50%" valign="top">

### Real-time team collaboration
WebSocket co-editing on Java virtual threads

- Workspace presence + entity edit locks
- Full collaboration lifecycle events
- Multi-tab session management

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Fine-grained access control
Workspace + Script two-tier permission model

- Workspace: Creator / Admin / Member / Guest
- Script: VIEW / EDIT / ADMIN
- Temporary grants with expiry and provenance

</td>
<td width="50%" valign="top">

### Multi-tenant architecture
PostgreSQL Schema-level isolation

- Dedicated Schema per workspace
- TransmittableThreadLocal context propagation
- Shared schema for cross-tenant data

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Credits & billing
Workspace wallet with dynamic payment channels

- Recharge / spend / refund / transfer / freeze ledger
- Per-member quota with daily / weekly / monthly reset
- Hot-swappable payment providers · gift code redemption · Free / Basic / Pro / Enterprise

</td>
<td width="50%" valign="top">

### Pluggable AI model gateway
Enterprise-grade implementation powered by a Groovy sandbox

- **Onboard new models without a release** — just write a script
- Four response modes: BLOCKING / STREAMING / CALLBACK / POLLING
- Retry · rate limit · circuit breaker · timeout (Resilience4j, runtime-configurable)
- Provider auto-fallback · gradual-rollout allowlist · Bearer / API Key / AK-SK

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Async task orchestration
Unified async runtime for image / video / audio / text

- Priority queue + BatchJob + Pipeline
- Timeout · retry · Compensation rollback
- End-to-end credit accounting

</td>
<td width="50%" valign="top">

### Multi-provider mail gateway
Unified mail abstraction, hot-swappable at runtime

- Resend / SMTP (AWS SES) / Cloudflare
- DynamicMailService routing
- Templates: verification / reset / welcome / security

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Content graph & versioning
Unified models for scripts / storyboards / characters / assets

- Versioning across all entities
- `t_asset_lineage` for full asset lineage
- Canvas nodes and three layout engines modeled in the backend; frontend integration in progress

</td>
<td width="50%" valign="top">

### Multi-cloud object storage
One interface, five providers

- MinIO / AWS S3 / Aliyun OSS
- Cloudflare R2 / Volcengine TOS
- Switchable by configuration

</td>
</tr>
</table>

---

## Architecture

```mermaid
flowchart LR
    Client["Web · Desktop · Mobile"] --> Web["Next.js · 3000"] --> Gateway["Gateway · 8080"]

    Gateway --> Core["Business Services<br/>user / workspace / wallet / billing<br/>project / ai / task / collab / system / canvas"]
    Gateway --> AgentSvc["Agent Plane · 8091<br/>SSE · Mission · Skill · HITL"]

    AgentSvc -. Feign .-> Core
    Core -.-> Infra[("PostgreSQL · Redis · RabbitMQ · OSS")]
    AgentSvc -.-> Infra
    AgentSvc --> LLM["OpenAI · Anthropic · Gemini"]
```

Full topology, critical paths, and tech stack are documented in **[docs/architecture.en.md](docs/architecture.en.md)**.

---

## Quick Start

```bash
git clone https://github.com/actionow-ai/actionow.git
cd actionow

./actionow.sh init        # interactive wizard for docker/.env.prod
./actionow.sh up          # build images and bring up the full production stack
./actionow.sh status      # container status
./actionow.sh backend rebuild xxx # rebuild a backend module, e.g. ./actionow.sh backend rebuild ai
./actionow.sh db migrate  # apply unapplied docker/init-db/*.sql to the running (or external) DB, tracked via t_migration_history
./actionow.sh db reset    # destructive re-init (delegates to existing reset-db)
```

Endpoints:

| Surface       | URL                                  |
|---------------|--------------------------------------|
| Web console   | http://localhost:3000                |
| API gateway   | http://localhost:8080/doc.html       |
| Agent Swagger | http://localhost:8091/swagger-ui.html|

**Default seeded accounts:**

| Username   | Password        | Role              |
|------------|-----------------|-------------------|
| `actionow` | `actionow2026!` | Tenant creator    |
| `admin`    | `actionow2026!` | Tenant admin      |
| `user1`    | `actionow2026!` | Regular user      |
| `user2`    | `actionow2026!` | Regular user      |

> ⚠️ Change these default passwords immediately after first login in any production deployment.

> Local development, deployment modes, and command reference: [docs/development.en.md](docs/development.en.md).

---

## Documentation

| Topic                  | Link                                                              |
|------------------------|-------------------------------------------------------------------|
| Architecture           | [docs/architecture.en.md](docs/architecture.en.md)                |
| Configuration & ports  | [docs/configuration.en.md](docs/configuration.en.md)              |
| Development & build    | [docs/development.en.md](docs/development.en.md)                  |
| Repository layout      | [docs/project-structure.en.md](docs/project-structure.en.md)      |
| Contributing           | [CONTRIBUTING.en.md](CONTRIBUTING.en.md)                          |
| Docker deployment      | [docker/README.md](docker/README.md)                              |

---

## Roadmap

| Capability                        | Goal                                                                                                                          |
|-----------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| Model gateway — shipped           | Groovy sandbox hot-load (**zero-release model onboarding**), provider auto-fallback, gradual-rollout allowlist, Resilience4j runtime configuration. |
| Model gateway — next               | Tenant-level quota & billing, routing strategies, full A/B framework, prompt version management, end-to-end call-chain observability. |
| Infinite canvas                   | High-performance viewports, layered rendering, node grouping, layout templates, and parallel multi-canvas editing.            |
| Team collaboration                | Live cursors and comments, threaded annotations, multi-user conflict merging, activity timelines, and a notification center on top of the existing Presence + edit-lock system. |
| Online video editing              | Timeline, shot-level cutting, transitions and subtitles, plus a cloud rendering pipeline linked back to asset versions.       |
| Image editing                     | Masks, inpainting, layers, reference image management; integrated with the agent toolchain for "edit while generating".       |
| Community                         | User profiles, work showcase, sharing of Skills and templates, likes and subscriptions to grow the open source ecosystem.     |
| One-click film agent              | From a topic or outline, automate the end-to-end pipeline: script, characters, storyboard, assets, and final cut.             |
| Self-improving agent system       | Long-term memory and preferences distilled from sessions and user feedback; self-evaluation, skill evolution, and regression testing. |
| Skill Marketplace                 | Build a public marketplace and versioned distribution on top of the existing system-level and workspace-level Skill scopes, with signing, permission model, dependency declarations, and cross-workspace installation. |
| Internationalization              | Multi-language coverage of error codes, system prompts, mail templates, Skills, and agent prompts.                            |

---

## License

This repository is released under [Apache License 2.0](LICENSE) with a small set of additional conditions (covering multi-tenant commercial usage and frontend LOGO/copyright protections). Please review [LICENSE](LICENSE) carefully before commercial use.

## Community

- Telegram group: [Join the Actionow community](https://t.me/+m1saPHQZlTIxZDg1)
- Discussions: architecture and RFCs
- Issues: defects and feature requests
- Email: `support@actionow.ai`
- Security: `security@actionow.ai`
- Website: [actionow.ai](https://actionow.ai)

## Friendly Links

- [LINUX DO](https://linux.do/) — A new ideal-type community

---

<div align="center">

<img src="assets/logo.png" alt="Actionow" width="56" />

<sub><b>Actionow</b> · <i>Action — Now.</i> · Crafted for filmmakers, by engineers.</sub>

<sub><a href="https://www.actionow.ai">actionow.ai</a></sub>

</div>
