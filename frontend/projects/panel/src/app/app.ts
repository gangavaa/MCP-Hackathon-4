import { Component, computed, effect, signal } from '@angular/core';
// The pre-bundled entry: the plain one pulls in the SDK's OAuth code as a lazy chunk, which a single-file panel can't hold.
import { App as McpApp, applyDocumentTheme } from '@modelcontextprotocol/ext-apps/app-with-deps';
import { ChartComponent } from '@shared/chart.component';
import { formatNumber, humanize, toModel, type Theme } from '@shared/render';
import type { ChartData, ChartSpec, ChartType } from '@shared/types';

/*
 * The MCP App: the panel `show_chart` renders inside the conversation. The person and the model drive the same
 * view — the model through tool calls, the person through these buttons — and every change the person makes is
 * told back to the model with updateModelContext, so "why are these offline?" knows what "these" are.
 */

interface PanelPayload extends ChartData {
  apiUrl?: string;
  drill?: Record<string, string>;
}

interface Level {
  data: ChartData;
  /** The category clicked to get here, for the breadcrumb. */
  via?: string;
}

interface ToolResult {
  isError?: boolean;
  structuredContent?: unknown;
  content?: { type: string; text?: string }[];
}

const TYPE_LABELS: Record<ChartType, string> = { bar: 'Bar', hbar: 'Ranking', line: 'Line', area: 'Area', pie: 'Pie', donut: 'Donut', table: 'Table', kpi: 'KPI' };

@Component({
  selector: 'app-root',
  imports: [ChartComponent],
  template: `
    <main class="relative flex flex-col gap-3 rounded-[10px] border border-border bg-surface p-4" [class.opacity-90]="busy()">
      <header>
        <div class="text-[0.72rem] font-semibold tracking-wider text-text-secondary uppercase">
          <span class="mr-1 rounded bg-header px-1.5 py-px text-white">NMS</span> Ask the Dashboard
        </div>
        <h1 class="mt-1.5 mb-0 text-lg font-bold">{{ title() }}</h1>
        @if (insight()) {
          <p class="mt-1.5 mb-0 rounded-r-md border-l-[3px] border-accent bg-accent/[0.07] px-3 py-2 text-[0.9rem]">{{ insight() }}</p>
        }
        @if (stack().length > 1) {
          <nav class="mt-2 flex flex-wrap items-center gap-1 text-[0.85rem]">
            @for (level of stack(); track $index; let i = $index, last = $last) {
              @if (last) {
                <span class="rounded-full bg-surface-hover px-2 py-0.5">{{ i === 0 ? 'All' : humanize(level.via) }}</span>
              } @else {
                <button type="button" class="cursor-pointer rounded-full border-0 bg-surface-hover px-2 py-0.5 text-link" (click)="back(i)">
                  {{ i === 0 ? 'All' : humanize(level.via) }}
                </button>
                <span class="text-text-secondary">›</span>
              }
            }
          </nav>
        }
      </header>

      <div class="flex flex-wrap items-center justify-between gap-2">
        <div class="inline-flex rounded-lg bg-surface-hover p-[3px]" role="group" aria-label="Chart type">
          @for (t of types(); track t) {
            <button
              type="button"
              class="cursor-pointer rounded-md border-0 px-3 py-1 text-[0.82rem] font-medium"
              [class]="t === type() ? 'bg-surface text-text-primary shadow-sm' : 'bg-transparent text-text-secondary'"
              [attr.aria-pressed]="t === type()"
              (click)="type.set(t)"
            >
              {{ typeLabels[t] }}
            </button>
          }
        </div>
        <span class="text-[0.78rem] text-text-secondary">{{ hint() }}</span>
      </div>

      <section class="h-[340px]" aria-live="polite" [class.pointer-events-none]="busy()">
        @if (current(); as level) {
          <nms-chart [data]="level.data" [type]="type()" [theme]="theme()" [drillable]="!!drillTo()" (select)="drillInto($event)" />
        } @else {
          <p class="empty">{{ status() }}</p>
        }
      </section>

      <footer class="flex flex-wrap items-start justify-between gap-2">
        <div class="ml-auto flex gap-2" [class.opacity-60]="busy()">
          <button type="button" [class]="btn" (click)="explain()" [disabled]="!current() || busy()">Explain this</button>
          <button type="button" [class]="btn" (click)="openDashboard()">Open dashboard</button>
        </div>
      </footer>

      @if (toast(); as t) {
        <div role="status" class="absolute right-4 bottom-16 rounded-lg px-3.5 py-2 text-[0.85rem] font-semibold text-white shadow-lg" [class]="t.error ? 'bg-status-offline' : 'bg-wizard'">
          {{ t.text }}
        </div>
      }
    </main>
  `,
})
export class App {
  protected readonly typeLabels = TYPE_LABELS;
  protected readonly humanize = humanize;
  protected readonly btn = 'cursor-pointer rounded-lg border border-border bg-surface px-3.5 py-2 text-[0.85rem] font-semibold text-text-primary hover:bg-surface-hover disabled:cursor-default';

  protected readonly stack = signal<Level[]>([]);
  protected readonly type = signal<ChartType>('bar');
  protected readonly theme = signal<Theme>('light');
  protected readonly busy = signal(false);
  protected readonly status = signal('Waiting for a chart…');
  protected readonly toast = signal<{ text: string; error: boolean } | undefined>(undefined);

  protected readonly current = computed(() => this.stack().at(-1));
  readonly #model = computed(() => {
    const level = this.current();
    return level ? toModel(level.data) : undefined;
  });
  protected readonly title = computed(() => this.current()?.data.spec.title ?? this.#requested()?.title ?? 'Ask the Dashboard');
  protected readonly insight = computed(() => (this.stack().length === 1 ? this.current()?.data.spec.insight : undefined));
  protected readonly types = computed<ChartType[]>(() => {
    const model = this.#model();
    if (!model) return [];
    return model.categoryKey == null ? ['kpi', 'table'] : model.isTime ? ['line', 'area', 'bar', 'table'] : ['bar', 'hbar', 'pie', 'donut', 'table'];
  });
  protected readonly drillTo = computed(() => {
    const model = this.#model();
    return model?.categoryKey && !model.isTime && this.type() !== 'table' ? this.#drill()[model.categoryKey] : undefined;
  });
  protected readonly hint = computed(() => {
    const to = this.drillTo();
    const model = this.#model();
    return to && model ? `Click a ${model.categoryLabel.toLowerCase()} to see it by ${to.replaceAll('_', ' ')}` : '';
  });

  readonly #requested = signal<ChartSpec | undefined>(undefined);
  readonly #drill = signal<Record<string, string>>({});
  #apiUrl = 'http://localhost:4300';
  #fallback: ReturnType<typeof setTimeout> | undefined;
  #toastTimer: ReturnType<typeof setTimeout> | undefined;
  readonly #app = new McpApp({ name: 'NMS Ask the Dashboard', version: '0.2.0' });

  constructor() {
    /*
     * Some hosts — Claude Desktop's agent mode among them — deliver the tool input but drop structuredContent from
     * the result. So the panel keeps the spec it was asked for, and when no chart data arrives with the result (or
     * no result arrives at all) it fetches the chart itself through run_chart.
     */
    this.#app.ontoolinput = ({ arguments: args }) => {
      this.#requested.set(args as unknown as ChartSpec);
      this.status.set('Loading chart…');
      clearTimeout(this.#fallback);
      this.#fallback = setTimeout(() => void this.#loadRequested(), 4000);
    };
    this.#app.ontoolresult = (result) => {
      clearTimeout(this.#fallback);
      const payload = payloadOf(result as ToolResult);
      if (payload) this.#show(payload);
      else if (result.isError) this.status.set(textOf(result as ToolResult));
      else void this.#loadRequested();
    };
    this.#app.onhostcontextchanged = (context) => {
      if (context.theme) this.#setTheme(context.theme);
    };

    // Keep the model's picture of the panel current: what is on screen, how it is drawn, the top of the data.
    effect(() => {
      const level = this.current();
      const type = this.type();
      if (level) void this.#tellModel(level, type);
    });
    // A type the new chart can't be drawn as falls back to the first it can.
    effect(() => {
      const types = this.types();
      if (types.length && !types.includes(this.type())) this.type.set(types[0]);
    });

    void this.#app.connect().then(() => {
      const theme = this.#app.getHostContext()?.theme;
      if (theme) this.#setTheme(theme);
    });
  }

  // ------------------------------------------------------------------------------------------- actions

  protected back(index: number): void {
    this.stack.update((s) => s.slice(0, index + 1));
    this.type.set(this.current()!.data.spec.chartType);
  }

  protected async drillInto(raw: string): Promise<void> {
    const dimension = this.drillTo();
    const level = this.current();
    const model = this.#model();
    if (!dimension || !level || !model?.categoryKey || this.busy()) return;
    const { spec } = level.data;
    const next: ChartSpec = {
      title: `${spec.title.replace(/ — .*$/, '')} — ${humanize(raw)}`,
      chartType: dimension === 'farm' ? 'hbar' : this.type() === 'kpi' ? 'bar' : this.type(),
      compare: spec.compare,
      query: { ...spec.query, groupBy: [dimension], filters: [...(spec.query.filters ?? []), { field: model.categoryKey, op: 'eq', value: raw }] },
    };
    await this.#busy(async () => {
      const payload = payloadOf(await this.#callTool('run_chart', next));
      if (!payload) throw new Error('run_chart returned no chart data.');
      this.stack.update((s) => [...s, { data: payload, via: raw }]);
      this.type.set(next.chartType);
    });
  }

  protected explain(): Promise<void> {
    return this.#busy(async () => {
      const { spec } = this.current()!.data;
      await this.#app.sendMessage({
        role: 'user',
        content: [{ type: 'text', text: `Explain the "${spec.title}" chart I'm looking at${this.#pathText()}: what stands out, what is the likely cause, and what should I check next?` }],
      });
    });
  }

  protected openDashboard(): void {
    void this.#app.openLink({ url: this.#apiUrl });
  }

  // ------------------------------------------------------------------------------------------- internals

  #show(payload: PanelPayload): void {
    this.#apiUrl = payload.apiUrl ?? this.#apiUrl;
    this.#drill.set(payload.drill ?? this.#drill());
    this.stack.set([{ data: payload }]);
    this.type.set(payload.spec.chartType);
  }

  async #loadRequested(): Promise<void> {
    clearTimeout(this.#fallback);
    if (this.stack().length) return;
    const requested = this.#requested();
    if (!requested) return this.status.set('The host sent no chart request to this panel.');
    try {
      const payload = payloadOf(await this.#callTool('run_chart', requested));
      if (!payload) throw new Error('run_chart returned no chart data.');
      this.#show(payload);
    } catch (error) {
      this.status.set(`Couldn't load the chart: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  async #callTool(name: string, args: object): Promise<ToolResult> {
    const result = (await this.#app.callServerTool({ name, arguments: args as Record<string, unknown> })) as ToolResult;
    if (result.isError) throw new Error(textOf(result));
    return result;
  }

  async #busy(work: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    try {
      await work();
    } catch (error) {
      this.#toast(error instanceof Error ? error.message : String(error), true);
    } finally {
      this.busy.set(false);
    }
  }

  #toast(text: string, error = false): void {
    this.toast.set({ text, error });
    clearTimeout(this.#toastTimer);
    this.#toastTimer = setTimeout(() => this.toast.set(undefined), 3500);
  }

  #setTheme(theme: Theme): void {
    this.theme.set(theme);
    applyDocumentTheme(theme);
  }

  #pathText(): string {
    const path = this.stack().slice(1);
    return path.length ? ` (drilled into ${path.map((l) => humanize(l.via)).join(' › ')})` : '';
  }

  async #tellModel(level: Level, type: ChartType): Promise<void> {
    const { spec, result } = level.data;
    const metric = result.columns.find((c) => c.kind === 'metric')!;
    const dims = result.columns.filter((c) => c.kind === 'dimension');
    const top = result.rows.slice(0, 10).map((row) => `${dims.map((d) => humanize(row[d.key])).join(' / ') || 'total'}: ${formatNumber(row[metric.key])}`);
    try {
      await this.#app.updateModelContext({
        content: [{ type: 'text', text: `The user is viewing "${spec.title}"${this.#pathText()} as a ${TYPE_LABELS[type].toLowerCase()} chart. ${metric.label} — ${top.join('; ')}.` }],
        structuredContent: { spec: { ...spec, chartType: type }, drillPath: this.stack().slice(1).map((l) => l.via) },
      });
    } catch {
      // a host without model-context support still renders the panel
    }
  }
}

/** The chart data from structuredContent, or — when a host dropped it — from the JSON run_chart also sends as text. */
function payloadOf(result: ToolResult): PanelPayload | undefined {
  const isPayload = (v: unknown): v is PanelPayload => !!v && typeof v === 'object' && 'spec' in v && 'result' in v;
  if (isPayload(result.structuredContent)) return result.structuredContent;
  for (const block of result.content ?? []) {
    if (block.type !== 'text' || !block.text?.startsWith('{')) continue;
    try {
      const parsed: unknown = JSON.parse(block.text);
      if (isPayload(parsed)) return parsed;
    } catch {
      // not JSON — a summary for the model
    }
  }
  return undefined;
}

function textOf(result: ToolResult): string {
  return result.content?.find((c) => c.type === 'text')?.text ?? 'The tool failed.';
}
