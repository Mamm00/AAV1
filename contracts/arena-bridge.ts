/**
 * Arena Android ↔ Web bridge contract (TypeScript mirror).
 *
 * The Kotlin side lives in
 * `core/webview/src/main/java/com/arena/core/webview/BridgeContract.kt`
 * (`BridgeCodec` + `BridgeMessage`). UPDATE BOTH FILES TOGETHER.
 *
 * Transport
 * --------
 * Kotlin → JS: `WebView.postWebMessage(WebMessage, Uri("https://arena.ai"))`
 *              which materialises as `ArenaBridge.onNativeMessage(envelope)`.
 * JS → Kotlin: `ArenaBridge.postMessage(json)` — injected by
 *              `WebViewCompat.addWebMessageListener` with origin rules
 *              `https://arena.ai` and `https://*.arena.ai`.
 *
 * Envelope
 * --------
 * ```json
 * { "v": 1, "type": "sendPrompt", "id": "cmd-42", "payload": { … } }
 * ```
 *
 * Security rules enforced by Kotlin (defence in depth):
 *  - `sourceOrigin` must be `https://arena.ai` or `https://*.arena.ai`.
 *  - Unknown types degrade to `Unknown` — never throw mid-stream.
 *  - Payloads are NEVER logged (they contain prompts/tokens).
 */

export const ARENA_BRIDGE_VERSION = 1 as const;
export const ARENA_BRIDGE_OBJECT = "ArenaBridge" as const;
export const ARENA_ORIGIN_RULES = ["https://arena.ai", "https://*.arena.ai"] as const;

export type BridgeEnvelope<T extends BridgeMessage = BridgeMessage> = {
  v: typeof ARENA_BRIDGE_VERSION;
  type: T["type"];
  id?: string;
  payload: T["payload"];
};

// -------------------------------------------------------------------------- //
// Message union — directions annotated per contract
// -------------------------------------------------------------------------- //

export type BridgeMessage =
  // ---------------------------------------------------- Kotlin → JS (native
  // surfaces driving the page: voice, clipboard, model picker, notification
  // votes, attachment hand-off)
  | { type: "sendPrompt"; payload: { text: string; modelId?: string } }
  | {
      type: "vote";
      payload: { choice: "a" | "b" | "tie" | "both_bad"; generationId?: string };
    }
  | { type: "switchModel"; payload: { modelId: string; slot: 0 | 1 } }
  | {
      type: "attachFile";
      payload: { name: string; mimeType: string; sizeBytes: number; uri: string };
    }
  // -------------------------------------------------------- JS → Kotlin (page
  // reporting bootstrap / streaming / agent progress to native UI)
  | { type: "ready"; payload: { route?: string } }
  | { type: "stream"; payload: { raw: string; slot?: 0 | 1 } }
  | {
      type: "agentTaskUpdate";
      payload: {
        taskId: string;
        stepId: string;
        kind: string;
        title: string;
        status: "PENDING" | "RUNNING" | "DONE" | "FAILED";
        detail?: string;
      };
    }
  | { type: "ack"; payload: { ofId: string; ok: boolean; error?: string } };

export type BridgeMessageType = BridgeMessage["type"];

// -------------------------------------------------------------------------- //
// Page-side adapter
// -------------------------------------------------------------------------- */

export type StreamListener = (raw: string, slot: 0 | 1) => void;
export type AgentListener = (update: Extract<BridgeMessage, { type: "agentTaskUpdate" }>["payload"]) => void;

type NativeChannel = {
  postMessage(json: string): void;
  onNativeMessage?(json: string): void;
};

declare global {
  interface Window {
    ArenaBridge?: NativeChannel;
    /** Set by the adapter; Kotlin commands land here. */
    __arenaNative?: {
      onSendPrompt?: (text: string, modelId?: string) => void;
      onVote?: (choice: string, generationId?: string) => void;
      onSwitchModel?: (modelId: string, slot: number) => void;
      onAttachFile?: (file: { name: string; mimeType: string; sizeBytes: number; uri: string }) => void;
    };
  }
}

function postToKotlin(message: BridgeMessage, id?: string): boolean {
  const channel = window.ArenaBridge;
  if (!channel?.postMessage) return false; // page outside the app WebView
  const envelope: BridgeEnvelope = {
    v: ARENA_BRIDGE_VERSION,
    type: message.type,
    ...(id ? { id } : {}),
    payload: message.payload,
  };
  channel.postMessage(JSON.stringify(envelope));
  return true;
}

/**
 * Installs the page side of the bridge:
 *  - forwards EventSource / fetch-SSE chunks as `stream` messages,
 *  - forwards agent progress as `agentTaskUpdate`,
 *  - dispatches Kotlin → JS commands to `window.__arenaNative` handlers.
 *
 * Call once after the SPA boots:
 * ```ts
 * const bridge = installArenaBridge();
 * bridge.attachEventSource(myEventSource);
 * bridge.notifyReady("/chat");
 * ```
 */
export function installArenaBridge() {
  const streamListeners = new Set<StreamListener>();
  const agentListeners = new Set<AgentListener>();
  const native = (window.__arenaNative ??= {});

  // ---- Kotlin → JS dispatch ------------------------------------------------
  const channel = window.ArenaBridge;
  if (channel) {
    channel.onNativeMessage = (json: string) => {
      let envelope: BridgeEnvelope;
      try {
        envelope = JSON.parse(json) as BridgeEnvelope;
      } catch {
        return; // malformed — ignore, never throw into the renderer
      }
      switch (envelope.type) {
        case "sendPrompt":
          native.onSendPrompt?.(
            (envelope.payload as { text: string }).text,
            (envelope.payload as { modelId?: string }).modelId,
          );
          break;
        case "vote":
          native.onVote?.(
            (envelope.payload as { choice: string }).choice,
            (envelope.payload as { generationId?: string }).generationId,
          );
          break;
        case "switchModel":
          native.onSwitchModel?.(
            (envelope.payload as { modelId: string }).modelId,
            (envelope.payload as { slot: number }).slot,
          );
          break;
        case "attachFile":
          native.onAttachFile?.(envelope.payload as {
            name: string; mimeType: string; sizeBytes: number; uri: string;
          });
          break;
        default:
          break;
      }
    };
  }

  return {
    /** Forward one SSE `data:` payload (including its `a0:`-style prefix). */
    emitStream(raw: string, slot: 0 | 1 = 0): void {
      postToKotlin({ type: "stream", payload: { raw, slot } });
      streamListeners.forEach((listener) => listener(raw, slot));
    },

    emitAgentUpdate(update: Extract<BridgeMessage, { type: "agentTaskUpdate" }>["payload"]): void {
      postToKotlin({ type: "agentTaskUpdate", payload: update });
      agentListeners.forEach((listener) => listener(update));
    },

    /** Call when the SPA has mounted its route and is ready for commands. */
    notifyReady(route: string): void {
      postToKotlin({ type: "ready", payload: { route } });
    },

    /** Ack a Kotlin command (correlation id). */
    ack(ofId: string, ok: boolean, error?: string): void {
      postToKotlin({ type: "ack", payload: { ofId, ok, error } });
    },

    attachEventSource(source: EventSource): void {
      source.addEventListener("message", (event: MessageEvent<string>) => {
        // Arena multiplexes channels via data prefixes; forward verbatim —
        // Kotlin's SseParser decodes them into typed ArenaEvents.
        this.emitStream(event.data, 0);
      });
    },

    onStream(listener: StreamListener): () => void {
      streamListeners.add(listener);
      return () => streamListeners.delete(listener);
    },

    onAgent(listener: AgentListener): () => void {
      agentListeners.add(listener);
      return () => agentListeners.delete(listener);
    },
  };
}
