import { Component, DestroyRef, ElementRef, afterNextRender, inject, input, signal, viewChild } from '@angular/core';
import { AppBridge, PostMessageTransport } from '@modelcontextprotocol/ext-apps/app-bridge';
import type { ChartData, ChartSpec, DatasetInfo } from '@shared/types';
import { ApiService } from '../core/api.service';
import { ThemeService } from '../core/theme.service';
import { Topbar } from '../core/topbar';
import { QUESTIONS } from './questions';

interface LogEntry {
  kind: 'tool' | 'context' | 'message' | 'error';
  text: string;
}

/**
 * A local MCP Apps host, for rehearsing and for working on the panel without Claude Desktop. It plays the host's
 * half of the protocol — sandboxed iframe, AppBridge, tool input and result — and answers the panel's tool calls
 * from the API, which is all the MCP server would do with them.
 *
 * `?spec=<base64url ChartSpec>` (the link show_chart hands Claude) opens that chart; `?agent=1` plays a host that
 * drops structuredContent, as Claude Desktop's agent mode does, to exercise the panel's fallback.
 */
@Component({
  selector: 'nms-host-page',
  imports: [Topbar],
  template: `
    <nms-topbar title="Panel test host">
      <a href="/" target="nms-dashboard" class="text-white underline">Open dashboard ↗</a>
    </nms-topbar>
    <main class="grid gap-6 p-6 lg:grid-cols-[340px_minmax(0,860px)]">
      <aside>
        <p class="mt-0 mb-4 text-[0.82rem] text-text-secondary">
          Stands in for Claude Desktop: each question is a <code>show_chart</code> call with the spec Claude would write,
          rendered through the real MCP Apps bridge. Drill-downs go to the API.
        </p>
        <div class="flex flex-col gap-2">
          @for (q of questions; track q.ask) {
            <button
              type="button"
              class="cursor-pointer rounded-lg border border-border bg-surface px-3 py-2.5 text-left text-[0.88rem] font-medium text-text-primary hover:border-link"
              (click)="ask(q.ask, q.spec)"
            >
              {{ q.ask }}
            </button>
          }
        </div>
        <h2 class="mt-6 mb-2 text-[0.85rem] tracking-wider text-text-secondary uppercase">From the panel</h2>
        <ol class="m-0 max-h-[360px] list-none overflow-auto p-0 text-[0.78rem]">
          @for (entry of log(); track $index) {
            <li class="border-b border-border py-1.5">
              <span
                class="inline-block min-w-[58px] rounded px-1.5 text-center font-semibold"
                [class]="entry.kind === 'tool' ? 'bg-link/10 text-link' : entry.kind === 'message' ? 'bg-accent/10 text-accent' : entry.kind === 'error' ? 'bg-status-offline/10 text-status-offline' : 'bg-surface-hover'"
                >{{ entry.kind }}</span
              >
              {{ entry.text }}
            </li>
          }
        </ol>
      </aside>
      <section class="flex flex-col gap-3">
        @if (question()) {
          <div class="max-w-[70%] self-end rounded-[14px_14px_4px_14px] bg-header px-3.5 py-2.5 text-white">{{ question() }}</div>
        }
        <div #slot></div>
      </section>
    </main>
  `,
})
export class HostPage {
  /** Query parameters, bound by the router. */
  readonly spec = input<string>();
  readonly agent = input<string>();

  protected readonly questions = QUESTIONS;
  protected readonly question = signal<string | undefined>(undefined);
  protected readonly log = signal<LogEntry[]>([]);

  private readonly slot = viewChild.required<ElementRef<HTMLElement>>('slot');
  readonly #api = inject(ApiService);
  readonly #theme = inject(ThemeService);
  readonly #panelHtml = this.#api.panelHtml();
  readonly #datasets = this.#api.datasets();
  #bridge: AppBridge | undefined;

  constructor() {
    afterNextRender(() => {
      const linked = this.spec();
      if (!linked) return;
      try {
        const spec = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(linked.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0))));
        void this.ask(spec.title, spec);
      } catch {
        this.#entry('error', 'The chart link is damaged — ask Claude for the chart again.');
      }
    });
    inject(DestroyRef).onDestroy(() => void this.#bridge?.close().catch(() => {}));
  }

  /** One question, one tool call, one fresh panel — as the chat would render it. */
  protected async ask(question: string, spec: ChartSpec): Promise<void> {
    this.question.set(question);
    await this.#bridge?.close().catch(() => {});

    const iframe = document.createElement('iframe');
    iframe.sandbox.add('allow-scripts');
    iframe.title = 'Ask the Dashboard panel';
    iframe.className = 'h-[560px] w-full border-0 bg-transparent';
    this.slot().nativeElement.replaceChildren(iframe);

    const bridge = new AppBridge(null, { name: 'NMS test host', version: '0.2.0' }, { openLinks: {}, serverTools: {}, logging: {} }, {
      hostContext: { theme: this.#theme.theme(), platform: 'web', displayMode: 'inline' },
    });
    this.#bridge = bridge;
    bridge.oncalltool = async ({ name, arguments: args }) => this.#callTool(name, (args ?? {}) as Record<string, unknown>);
    bridge.onmessage = async ({ content }) => (this.#entry('message', content.map((c) => ('text' in c ? c.text : '')).join(' ')), {});
    bridge.onupdatemodelcontext = async ({ content }) => (this.#entry('context', content?.map((c) => ('text' in c ? c.text : '')).join(' ') ?? ''), {});
    bridge.onopenlink = async ({ url }) => (window.open(url, 'nms-dashboard'), {});
    bridge.onsizechange = ({ height }) => {
      if (height) iframe.style.height = `${height}px`;
    };
    bridge.oninitialized = async () => {
      await bridge.sendToolInput({ arguments: spec as unknown as Record<string, unknown> });
      const result = await this.#showChart(spec);
      await bridge.sendToolResult(this.agent() ? { content: result.content } : result);
    };

    await bridge.connect(new PostMessageTransport(iframe.contentWindow!, iframe.contentWindow!));
    iframe.srcdoc = await this.#panelHtml;
  }

  async #showChart(spec: ChartSpec) {
    const data = await this.#api.chart(spec);
    return { content: [{ type: 'text' as const, text: `Showing "${spec.title}"` }], structuredContent: await this.#payload(data) };
  }

  async #callTool(name: string, args: Record<string, unknown>) {
    this.#entry('tool', `${name}(${(args as { title?: string }).title ?? ''})`);
    try {
      if (name === 'run_chart') {
        const payload = await this.#payload(await this.#api.chart(args as unknown as ChartSpec));
        return this.agent()
          ? { content: [{ type: 'text' as const, text: JSON.stringify(payload) }] }
          : { content: [{ type: 'text' as const, text: 'ok' }], structuredContent: payload };
      }
      return { isError: true, content: [{ type: 'text' as const, text: `The test host does not implement ${name}.` }] };
    } catch (error) {
      return { isError: true, content: [{ type: 'text' as const, text: error instanceof Error ? error.message : String(error) }] };
    }
  }

  async #payload(data: ChartData): Promise<Record<string, unknown>> {
    const drill = (await this.#datasets).find((d: DatasetInfo) => d.id === data.spec.query.dataset)?.drill ?? {};
    return { ...data, apiUrl: location.origin, drill };
  }

  #entry(kind: LogEntry['kind'], text: string): void {
    this.log.update((list) => [{ kind, text }, ...list]);
  }
}
